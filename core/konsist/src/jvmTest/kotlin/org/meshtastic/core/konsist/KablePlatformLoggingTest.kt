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
 * BLE failures are expected events, so their diagnostics carry only the exception class and status — never the
 * [Throwable] object, whose message text and stack trace leak platform internals into analytics.
 *
 * The paren form `Logger.x(throwable) { … }` forwards the throwable directly, and the lambda form forwards it just as
 * well through `$ex` or `${ex.message}` interpolations. This bans both in the two files that report BLE platform
 * failures: Android's peripheral setup (MTU and connection-priority requests) and the shared Kable connection
 * (observation, teardown, and attempt failures). Curated expressions — exception class names and sanitized log types —
 * stay allowed.
 */
class KablePlatformLoggingTest {

    private val scopedFiles =
        listOf("/androidMain/" to "KablePlatformSetup.kt", "/commonMain/" to "KableBleConnection.kt")

    private val loggerMarkers = listOf("Logger.", "logger.")

    /** The paren form takes a throwable as its first argument; its message and trace text are never curated. */
    private val loggerParenForm = Regex("""(?:Logger|logger)\.[vdiwe]\s*\(""")

    /** Identifiers whose direct interpolation forwards raw throwable text into diagnostics. */
    private val throwableRoots = setOf("e", "ex", "error", "exception", "throwable", "cause", "failure")

    /** Curated throwable-derived expressions: exception class names and sanitized log types. */
    private val curatedThrowableExpression = Regex("""::class|\.safeLogType\(""")

    @Test
    fun `Kable platform diagnostics never forward arbitrary throwable text`() {
        val matches =
            Konsist.scopeFromProject()
                .files
                .filterNot { it.isNestedAgentWorktree() }
                .filter { file ->
                    scopedFiles.any { (sourceSet, name) ->
                        file.scanPath.contains(sourceSet) && file.scanPath.endsWith(name)
                    }
                }
        assertTrue(
            matches.size == scopedFiles.size,
            "expected exactly one match per scoped file in scope; found ${matches.map { it.scanPath }}",
        )
        val offenders =
            matches
                .sortedBy { it.scanPath }
                .flatMap { source ->
                    val lines = source.text.lines()
                    loggerDiagnosticOffenders(lines) { containsThrowableInterpolation(it) }
                        .map { start ->
                            "${source.scanPath.substringAfterLast('/')}:$start: ${lines[start - 1].trim()}"
                        }
                }

        assertTrue(
            offenders.isEmpty(),
            "Kable platform diagnostics must log curated failure types, not throwable messages. Offending lines:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `throwable interpolation inside lambda diagnostics is rejected without the paren form`() {
        val rejected =
            listOf(
                """Logger.e { "BLE setup failed: ${'$'}ex" }""",
                """Logger.w { "BLE setup failed: ${'$'}{ex.message}" }""",
            )
        rejected.forEach { source ->
            assertTrue(containsThrowableInterpolation(source), "the throwable guard must reject: $source")
        }

        val curated =
            listOf(
                """Logger.w { "Failed to request MTU (${'$'}{e::class.simpleName ?: "Exception"})" }""",
                """Logger.w { "cause=${'$'}{e.safeLogType()}" }""",
            )
        curated.forEach { source ->
            assertTrue(!containsThrowableInterpolation(source), "curated failure diagnostics must pass: $source")
        }
    }

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

    private fun findInterpolationEnd(text: String, expressionStart: Int): Int {
        var depth = 1
        for (index in expressionStart until text.length) {
            when (text[index]) {
                '{' -> depth += 1

                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return -1
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
}
