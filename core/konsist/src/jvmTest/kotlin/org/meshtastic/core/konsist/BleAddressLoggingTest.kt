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
     * Files where an address is used as an identity rather than as diagnostic text — building the connection string or
     * a device label the user themselves is looking at. Anonymising these would break functionality.
     */
    private val identityUseAllowlist = listOf("DeviceListEntry.kt")

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
        .filterNot { "/.claude/" in it.path }
        .filter { file -> scannedPathFragments.any { it in file.path } }
        .filterNot { file -> identityUseAllowlist.any { file.path.endsWith(it) } }

    @Test
    fun `the scan actually reaches the BLE sources`() {
        val paths = scannedFiles().map { it.path }

        assertTrue(paths.isNotEmpty(), "scoped scan matched no files at all — the path filter is wrong")
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
                rawAddressDiagnosticOffenders(file.path.substringAfterLast("/kotlin/"), file.text)
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

    @Test
    fun `privacy-sensitive BLE diagnostics never forward arbitrary throwable text`() {
        val throwableLoggerCall = Regex("""(?:Logger|logger)\.[vdiwe]\s*\(""")
        val protectedFiles = setOf("BleRadioTransport.kt", "HistoryManagerImpl.kt")
        val offenders =
            scannedFiles()
                .filter { file -> protectedFiles.any { file.path.endsWith(it) } }
                .flatMap { file ->
                    file.text.lines().withIndex().mapNotNull { (index, line) ->
                        line.takeIf(throwableLoggerCall::containsMatchIn)?.let {
                            "${file.path.substringAfterLast("/kotlin/")}:${index + 1}: ${line.trim()}"
                        }
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
            scannedFiles().single { it.path.endsWith("BleRadioTransport.kt") }.text.replace(Regex("""\s+"""), " ")

        assertTrue(
            Regex("""val\s+anonymizedAddress\s*=\s*address\.anonymize\(\)""").containsMatchIn(source),
            "BLE transport must derive its diagnostic identifier through anonymize()",
        )
        assertTrue(
            Regex("""val\s+logAddress\s*=\s*"\[\$\{?anonymizedAddress}?]"""").containsMatchIn(source),
            "BLE transport log prefix must use only the anonymized identifier",
        )
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
                .filterNot { "/.claude/" in it.path } // see scannedFiles()
                .filter { "/core/ble/" in it.path }
                .flatMap { file ->
                    file.text.lines().withIndex().mapNotNull { (index, line) ->
                        if ("identifier =" in line && "address" in line && "anonymize" !in line) {
                            "${file.path.substringAfterLast("/kotlin/")}:${index + 1}: ${line.trim()}"
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
