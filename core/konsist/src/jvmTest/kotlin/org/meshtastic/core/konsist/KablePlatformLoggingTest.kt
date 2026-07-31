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

class KablePlatformLoggingTest {
    @Test
    fun `Kable platform diagnostics never forward arbitrary throwable text`() {
        val matches =
            Konsist.scopeFromProject().files.filter {
                "/androidMain/" in it.path && it.path.endsWith("KablePlatformSetup.kt")
            }
        assertTrue(
            matches.size == 1,
            "expected exactly one androidMain KablePlatformSetup.kt in scope; found ${matches.map { it.path }}",
        )
        val source = matches.single().text
        val throwableLoggerCall = Regex("""(?:Logger|logger)\.[vdiwe]\s*\(""")
        val offenders =
            source.lines().withIndex().mapNotNull { (index, line) ->
                line.takeIf(throwableLoggerCall::containsMatchIn)?.let {
                    "KablePlatformSetup.kt:${index + 1}: ${line.trim()}"
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "Kable platform diagnostics must log curated failure types, not throwable messages. Offending lines:\n" +
                offenders.joinToString("\n"),
        )
    }
}
