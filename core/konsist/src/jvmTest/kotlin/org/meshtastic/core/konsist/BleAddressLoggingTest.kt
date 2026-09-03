/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.konsist

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A BLE MAC address is a stable hardware identifier for the user's radio, and Kermit forwards every `Logger` call to
 * Datadog and Crashlytics on analytics the user is opted into by default. So an address must never be interpolated into
 * log or exception text raw — it goes through `Any?.anonymize()`, which keeps only a short suffix.
 *
 * This is enforced as an architecture rule rather than by review because the failure mode is missing a site: a previous
 * attempt anonymised the hand-written log statements in `core/ble` and missed the Kable `identifier`, which stamps the
 * address onto *every* line the BLE library emits, plus further sites in the DFU transports and WiFi provisioning.
 *
 * Scoped to BLE-adjacent modules and the two address-bearing transport/history sources so matching on the `address`
 * suffix stays low-noise.
 */
class BleAddressLoggingTest {

    private val scannedPathFragments =
        listOf(
            "/core/ble/",
            "/core/data/src/commonMain/kotlin/org/meshtastic/core/data/manager/HistoryManagerImpl.kt",
            "/core/network/src/commonMain/kotlin/org/meshtastic/core/network/radio/BleRadioTransport.kt",
            "/feature/firmware/",
            "/feature/wifi-provision/",
            "/feature/connections/",
        )

    /**
     * Files where an address is used as an identity or domain value rather than as diagnostic text — building the
     * connection string, a device label the user themselves is looking at, or a firmware image load address (UF2 base
     * addresses are hex integers, not device identifiers). Anonymising these would break functionality.
     */
    private val identityUseAllowlist = listOf("DeviceListEntry.kt", "FirmwareRetriever.kt")

    /** Kotlin identifiers inside a braced interpolation. */
    private val interpolationIdentifier = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")

    /** Identifiers whose values are already anonymized before interpolation. */
    private val safeAddressIdentifiers = setOf("logAddress", "anonymizedAddress")

    /**
     * Files this rule covers.
     *
     * Extracted and asserted non-empty by [the scan actually reaches the BLE sources] because a rule whose scope
     * silently matches nothing passes for the wrong reason — which is the whole failure mode this test exists to catch.
     */
    private fun scannedFiles() = Konsist.scopeFromProject()
        .files
        // scopeFromProject sweeps .claude/worktrees/ checkouts too; stale copies there
        // resurface long-fixed lines as phantom offenders (paths match "/core/ble/").
        .filterNot { it.isNestedAgentWorktree() }
        .filter { file -> scannedPathFragments.any { it in file.scanPath } }
        .filterNot { file -> identityUseAllowlist.any { file.scanPath.endsWith(it) } }

    @Test
    fun `the scan actually reaches the BLE sources`() {
        val paths = scannedFiles().map { it.scanPath }

        assertTrue(paths.isNotEmpty(), emptyScanMessage("BLE-scoped scan"))
        assertTrue(
            paths.any { it.endsWith("KableBleConnection.kt") },
            "expected core/ble sources in scope; got ${paths.size} files, e.g. ${paths.take(3)}",
        )
        assertTrue(paths.any { it.endsWith("BleRadioTransport.kt") }, "BLE transport logging escaped the scan")
        assertTrue(paths.any { it.endsWith("HistoryManagerImpl.kt") }, "history logging escaped the scan")
    }

    @Test
    fun `a BLE address is never interpolated into log or exception text without anonymize`() {
        val offenders =
            scannedFiles().flatMap { file ->
                rawAddressDiagnosticOffenders(file.scanPath.substringAfterLast("/kotlin/"), file.text)
            }

        assertTrue(
            offenders.isEmpty(),
            "BLE addresses must be anonymised in diagnostic text. Offending lines:\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `an anonymized prefix cannot hide a raw address on the same diagnostic line`() {
        val sources =
            listOf(
                """Logger.w { "${'$'}logAddress failed for ${'$'}address" }""",
                """Logger.w { "${'$'}{address.anonymize()} failed for ${'$'}address" }""",
            )

        sources.forEach { source ->
            val offenders = rawAddressDiagnosticOffenders("MixedAddressFixture.kt", source)

            assertTrue(
                offenders.isNotEmpty(),
                "the privacy guard must reject mixed anonymized and raw address tokens: $source",
            )
        }
    }

    @Test
    fun `a raw address on a diagnostic continuation line is rejected`() {
        val source =
            """
            Logger.w {
                "connection failed for " +
                    "${'$'}address"
            }
            """
                .trimIndent()

        val offenders = rawAddressDiagnosticOffenders("ContinuationFixture.kt", source)

        assertTrue(offenders.isNotEmpty(), "the privacy guard must scan the complete multi-line diagnostic")
    }

    @Test
    fun `explicitly anonymized address expressions remain valid diagnostics`() {
        val sources =
            listOf(
                """Logger.i { "Targets: ${'$'}{targetAddresses.map { it.anonymize() }}" }""",
                """Logger.i { "Bonding ${'$'}{entry.device.address.anonymize}" }""",
                """Logger.i { "${'$'}logAddress connected" }""",
            )

        sources.forEach { source ->
            assertTrue(
                rawAddressDiagnosticOffenders("AnonymizedAddressFixture.kt", source).isEmpty(),
                "the privacy guard rejected an explicitly anonymized expression: $source",
            )
        }
    }

    private val diagnosticMarkers = listOf("Logger.", "logger.", "historyLog(", "addr=", "throw ", "check(", "require(")

    private fun rawAddressDiagnosticOffenders(path: String, source: String): List<String> {
        val lines = source.lines()
        val offenders = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (diagnosticMarkers.any { it in line }) {
                val block = collectDiagnosticBlock(lines, index)
                if (containsRawAddressInterpolation(block.joinToString("\n"))) {
                    offenders += "$path:${index + 1}: ${line.trim()}"
                }
                index += block.size
            } else {
                index += 1
            }
        }
        return offenders
    }

    /** Collects one multi-line diagnostic expression, including Logger lambdas and parenthesized calls. */
    private fun collectDiagnosticBlock(lines: List<String>, start: Int): List<String> {
        val block = mutableListOf<String>()
        var delimiterDepth = 0
        var cursor = start
        do {
            val line = lines[cursor]
            block += line
            delimiterDepth += delimiterDelta(line)
            cursor += 1
        } while (cursor < lines.size && (delimiterDepth > 0 || block.last().trimEnd().endsWith("+")))
        return block
    }

    private fun delimiterDelta(line: String): Int =
        line.count { it == '(' || it == '[' || it == '{' } - line.count { it == ')' || it == ']' || it == '}' }

    /**
     * Returns true when a string-template interpolation contains more address references than explicit anonymization
     * operations. Braced expressions are scanned with balanced braces so collection expressions with lambdas remain
     * intact, while a mixed expression such as `${address.anonymize()} / $address` still exposes the second reference.
     */
    private fun containsRawAddressInterpolation(line: String): Boolean {
        var cursor = 0
        var rawAddressFound = false
        while (cursor < line.length && !rawAddressFound) {
            val dollar = line.indexOf('$', startIndex = cursor)
            if (dollar < 0 || dollar + 1 >= line.length) {
                cursor = line.length
            } else if (line[dollar + 1] == '{') {
                val end = findInterpolationEnd(line, expressionStart = dollar + 2)
                if (end < 0) {
                    rawAddressFound = true
                } else {
                    val expression = line.substring(dollar + 2, end)
                    val residual =
                        safeAddressIdentifiers.fold(expression) { current, identifier ->
                            current.replace(Regex("""\b$identifier\b"""), "")
                        }
                    val addressReferences =
                        interpolationIdentifier.findAll(residual).count { match ->
                            match.value.endsWith("address", ignoreCase = true) ||
                                match.value.endsWith("addresses", ignoreCase = true)
                        }
                    val anonymizations = Regex("""\banonymize\b""").findAll(residual).count()
                    rawAddressFound = addressReferences > anonymizations
                    cursor = end + 1
                }
            } else {
                val identifier = line.substring(dollar + 1).takeWhile { it == '_' || it.isLetterOrDigit() }
                val safe = identifier in safeAddressIdentifiers
                val addressLike =
                    identifier.endsWith("address", ignoreCase = true) ||
                        identifier.endsWith("addresses", ignoreCase = true)
                rawAddressFound = identifier.isNotEmpty() && addressLike && !safe
                cursor = dollar + 1 + identifier.length
            }
        }
        return rawAddressFound
    }

    private fun findInterpolationEnd(line: String, expressionStart: Int): Int {
        var depth = 1
        for (index in expressionStart until line.length) {
            when (line[index]) {
                '{' -> depth += 1

                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private val loggerMarkers = listOf("Logger.", "logger.")

    /** The paren form takes a throwable as its first argument; its message and trace text are never curated. */
    private val loggerParenForm = Regex("""(?:Logger|logger)\.[vdiwe]\s*\(""")

    /** Identifiers whose direct interpolation forwards raw throwable text into diagnostics. */
    private val throwableRoots = setOf("e", "ex", "error", "exception", "throwable", "cause", "failure")

    /** Curated throwable-derived expressions: exception class names and sanitized log types. */
    private val curatedThrowableExpression = Regex("""::class|\.safeLogType\(""")

    /** Payload or serialization access on a packet value, or raw-message field names, in diagnostic text. */
    private val packetDumpAccess =
        Regex("""\.(?:toByteArray|copyBytes|bytes|payload|decoded|data|raw)\b|raw_message|rawMessage""")

    /** Interpolation roots that refer to a packet value, e.g. `packet`, `packetToSave`, or `meshPacket`. */
    private val packetLikeRoot = Regex("""(?i)[a-z_]*packet[a-z_]*""")

    /** Runs [isOffender] over each logger diagnostic block in [lines], reporting 1-based start line numbers. */
    private fun loggerDiagnosticOffenders(lines: List<String>, isOffender: (String) -> Boolean): List<Int> {
        val offenders = mutableListOf<Int>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (loggerMarkers.any { it in line }) {
                val block = collectDiagnosticBlock(lines, index)
                if (isOffender(block.joinToString("\n"))) offenders += index + 1
                index += block.size
            } else {
                index += 1
            }
        }
        return offenders
    }

    /**
     * True when a diagnostic block logs the paren form or interpolates a throwable beyond curated expressions, so `$ex`
     * and `${ex.message}` fail while `${e::class.simpleName}` and `${e.safeLogType()}` pass.
     */
    private fun containsThrowableInterpolation(block: String): Boolean {
        if (loggerParenForm.containsMatchIn(block)) return true
        var cursor = 0
        while (cursor < block.length) {
            val dollar = block.indexOf('$', cursor)
            if (dollar < 0 || dollar + 1 >= block.length) return false
            if (block[dollar + 1] == '{') {
                val end = findInterpolationEnd(block, dollar + 2)
                val expression = if (end < 0) block.substring(dollar + 2) else block.substring(dollar + 2, end)
                cursor = if (end < 0) block.length else end + 1
                val root = expression.takeWhile { it == '_' || it.isLetterOrDigit() }
                if (root in throwableRoots && !curatedThrowableExpression.containsMatchIn(expression)) return true
            } else {
                val identifier = block.substring(dollar + 1).takeWhile { it == '_' || it.isLetterOrDigit() }
                cursor = dollar + 1 + identifier.length
                if (identifier in throwableRoots) return true
            }
        }
        return false
    }

    /**
     * True when a diagnostic block dumps the raw packet payload: raw-message field names, packet serialization, or a
     * packet-rooted interpolation that is not bounded by explicit member access.
     */
    private fun containsRawPacketDump(block: String): Boolean {
        if (packetDumpAccess.containsMatchIn(block)) return true
        var cursor = 0
        while (cursor < block.length) {
            val dollar = block.indexOf('$', cursor)
            if (dollar < 0 || dollar + 1 >= block.length) return false
            if (block[dollar + 1] == '{') {
                val end = findInterpolationEnd(block, dollar + 2)
                val expression = if (end < 0) block.substring(dollar + 2) else block.substring(dollar + 2, end)
                cursor = if (end < 0) block.length else end + 1
                if (hasUnboundedPacketAccess(expression)) return true
            } else {
                val identifier = block.substring(dollar + 1).takeWhile { it == '_' || it.isLetterOrDigit() }
                cursor = dollar + 1 + identifier.length
                if (packetLikeRoot.matches(identifier)) return true
            }
        }
        return false
    }

    /** True when [expression] interpolates a packet value bare or through payload-bearing members. */
    private fun hasUnboundedPacketAccess(expression: String): Boolean {
        val root = expression.takeWhile { it == '_' || it.isLetterOrDigit() }
        if (!packetLikeRoot.matches(root)) return false
        if (packetDumpAccess.containsMatchIn(expression)) return true
        // A bare packet root stringifies the whole packet; bounded metadata always goes through explicit members.
        return expression.trim() == root
    }

    @Test
    fun `throwable interpolation inside lambda diagnostics is rejected without the paren form`() {
        val rejected =
            listOf("""Logger.w { "send failed: ${'$'}ex" }""", """Logger.w { "send failed: ${'$'}{ex.message}" }""")
        rejected.forEach { source ->
            assertTrue(containsThrowableInterpolation(source), "the throwable guard must reject: $source")
        }

        val curated =
            listOf(
                """Logger.w { "failed (${'$'}{e::class.simpleName ?: "Exception"})" }""",
                """Logger.w { "cause=${'$'}{e.safeLogType()}" }""",
            )
        curated.forEach { source ->
            assertTrue(!containsThrowableInterpolation(source), "curated failure diagnostics must pass: $source")
        }
    }

    @Test
    fun `privacy-sensitive BLE diagnostics never forward arbitrary throwable text`() {
        val protectedFiles = setOf("BleRadioTransport.kt", "HistoryManagerImpl.kt")
        val offenders =
            scannedFiles()
                .filter { file -> protectedFiles.any { file.scanPath.endsWith(it) } }
                .flatMap { file ->
                    val lines = file.text.lines()
                    loggerDiagnosticOffenders(lines, ::containsThrowableInterpolation).map { start ->
                        "${file.scanPath.substringAfterLast("/kotlin/")}:$start: ${lines[start - 1].trim()}"
                    }
                }

        assertTrue(
            offenders.isEmpty(),
            "BLE diagnostics must log curated failure types, not throwable messages. Offending lines:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `BLE transport diagnostic identifiers are derived from the anonymized address`() {
        val source =
            scannedFiles().single { it.scanPath.endsWith("BleRadioTransport.kt") }.text.replace(Regex("""\s+"""), " ")

        assertTrue(
            Regex("""val\s+anonymizedAddress\s*=\s*address\.anonymize\(\)""").containsMatchIn(source),
            "BLE transport must derive its diagnostic identifier through anonymize()",
        )
        assertTrue(
            Regex("""val\s+logAddress\s*=\s*"\[\$\{?anonymizedAddress}?]"""").containsMatchIn(source),
            "BLE transport log prefix must use only the anonymized identifier",
        )
    }

    @Test
    fun `packet handler diagnostics never dump the raw packet payload`() {
        val source =
            Konsist.scopeFromProject()
                .files
                .filterNot { it.isNestedAgentWorktree() }
                .single { it.scanPath.endsWith("data/manager/PacketHandlerImpl.kt") }
        val lines = source.text.lines()
        val offenders =
            loggerDiagnosticOffenders(lines, ::containsRawPacketDump).map { start ->
                "$start: ${lines[start - 1].trim()}"
            }

        assertTrue(
            offenders.isEmpty(),
            "packet inserts must log bounded metadata (type, port, anonymized sender), never the raw packet " +
                "payload. Offending lines:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `packet handler guard rejects direct packet interpolation and serialization`() {
        val rejected =
            listOf(
                """Logger.d { "packet=${'$'}packet" }""",
                """Logger.d { "payload=${'$'}{packet.toByteArray()}" }""",
                """Logger.d { "raw=${'$'}{packet.raw_message}" }""",
            )
        rejected.forEach { source ->
            assertTrue(containsRawPacketDump(source), "the packet privacy guard must reject: $source")
        }

        val bounded =
            listOf(
                """Logger.d { "insert: ${'$'}{packetToSave.message_type} port=${'$'}{packetToSave.portNum}" }""",
                """Logger.d { "packet id=${'$'}{packet.id.toUInt()} from=${'$'}{packet.fromNum.anonymize()}" }""",
                """Logger.d { "[queueStatus] ${'$'}{queueStatus.toOneLineString()}" }""",
            )
        bounded.forEach { source ->
            assertTrue(!containsRawPacketDump(source), "bounded packet metadata must pass: $source")
        }
    }

    /**
     * Kable stamps its `Logging.identifier` onto every line it emits, so passing a raw address there leaks it from
     * library-internal logging that no per-call-site review would catch.
     */
    @Test
    fun `the Kable logging identifier is never a raw address`() {
        val offenders =
            Konsist.scopeFromProject()
                .files
                .filterNot { it.isNestedAgentWorktree() } // see scannedFiles()
                .filter { "/core/ble/" in it.scanPath }
                .flatMap { file ->
                    file.text.lines().withIndex().mapNotNull { (index, line) ->
                        if ("identifier =" in line && "address" in line && "anonymize" !in line) {
                            "${file.scanPath.substringAfterLast("/kotlin/")}:${index + 1}: ${line.trim()}"
                        } else {
                            null
                        }
                    }
                }

        assertTrue(
            offenders.isEmpty(),
            "Kable's logging identifier must be anonymised. Offending lines:\n" + offenders.joinToString("\n"),
        )
    }
}
