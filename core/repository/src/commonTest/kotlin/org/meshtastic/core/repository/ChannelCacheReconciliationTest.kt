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
package org.meshtastic.core.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.testing.FakeRadioConfigRepository
import org.meshtastic.proto.ChannelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChannelCacheReconciliationTest {
    private val normalizedSettings = listOf(ChannelSettings(name = "Primary"))

    @Test
    fun `does not reconcile before a channel write is accepted`() = runTest {
        val backing = FakeRadioConfigRepository()
        var replacementCalls = 0
        val repository =
            object : RadioConfigRepository by backing {
                override suspend fun replaceAllSettings(settingsList: List<ChannelSettings>) {
                    replacementCalls += 1
                    backing.replaceAllSettings(settingsList)
                }
            }

        repository.withChannelCacheReconciliation(normalizedSettings) {}

        assertEquals(0, replacementCalls)
    }

    @Test
    fun `normal cleanup reconciles once when only the write was marked`() = runTest {
        val backing = FakeRadioConfigRepository()
        var replacementCalls = 0
        val repository =
            object : RadioConfigRepository by backing {
                override suspend fun replaceAllSettings(settingsList: List<ChannelSettings>) {
                    replacementCalls += 1
                    backing.replaceAllSettings(settingsList)
                }
            }

        repository.withChannelCacheReconciliation(normalizedSettings) { markChannelWriteIssued() }

        assertEquals(1, replacementCalls)
        assertEquals(normalizedSettings, backing.currentChannelSet.settings)
    }

    @Test
    fun `successful explicit reconciliation runs exactly once`() = runTest {
        val backing = FakeRadioConfigRepository()
        var replacementCalls = 0
        val repository =
            object : RadioConfigRepository by backing {
                override suspend fun replaceAllSettings(settingsList: List<ChannelSettings>) {
                    replacementCalls += 1
                    backing.replaceAllSettings(settingsList)
                }
            }

        repository.withChannelCacheReconciliation(normalizedSettings) {
            markChannelWriteIssued()
            reconcileChannelCache()
        }

        assertEquals(1, replacementCalls)
        assertEquals(normalizedSettings, backing.currentChannelSet.settings)
    }

    @Test
    fun `retries a failed explicit reconciliation during cleanup`() = runTest {
        val backing = FakeRadioConfigRepository()
        val firstFailure = IllegalStateException("first reconciliation failed")
        var replacementCalls = 0
        val repository =
            object : RadioConfigRepository by backing {
                override suspend fun replaceAllSettings(settingsList: List<ChannelSettings>) {
                    replacementCalls += 1
                    if (replacementCalls == 1) throw firstFailure
                    backing.replaceAllSettings(settingsList)
                }
            }

        val failure =
            assertFailsWith<IllegalStateException> {
                repository.withChannelCacheReconciliation(normalizedSettings) {
                    markChannelWriteIssued()
                    reconcileChannelCache()
                }
            }

        assertSame(firstFailure, failure)
        assertEquals(2, replacementCalls)
        assertEquals(normalizedSettings, backing.currentChannelSet.settings)
    }

    @Test
    fun `does not self-suppress when explicit reconciliation and cleanup throw the same failure`() = runTest {
        val backing = FakeRadioConfigRepository()
        val reconciliationFailure = IllegalStateException("reconciliation failed")
        var replacementCalls = 0
        val repository =
            object : RadioConfigRepository by backing {
                override suspend fun replaceAllSettings(settingsList: List<ChannelSettings>) {
                    replacementCalls += 1
                    throw reconciliationFailure
                }
            }

        val failure =
            assertFailsWith<IllegalStateException> {
                repository.withChannelCacheReconciliation(normalizedSettings) {
                    markChannelWriteIssued()
                    reconcileChannelCache()
                }
            }

        assertSame(reconciliationFailure, failure)
        assertEquals(2, replacementCalls)
        assertTrue(failure.suppressedExceptions.isEmpty())
    }

    @Test
    fun `preserves a primary failure and suppresses reconciliation failure`() = runTest {
        val backing = FakeRadioConfigRepository()
        val primaryFailure = IllegalArgumentException("install failed")
        val cleanupFailure = IllegalStateException("reconciliation failed")
        val repository =
            object : RadioConfigRepository by backing {
                override suspend fun replaceAllSettings(settingsList: List<ChannelSettings>): Unit =
                    throw cleanupFailure
            }

        val failure =
            assertFailsWith<IllegalArgumentException> {
                repository.withChannelCacheReconciliation(normalizedSettings) {
                    markChannelWriteIssued()
                    throw primaryFailure
                }
            }

        assertSame(primaryFailure, failure)
        assertEquals(listOf(cleanupFailure), failure.suppressedExceptions)
    }

    @Test
    fun `preserves cancellation identity after reconciliation`() = runTest {
        val backing = FakeRadioConfigRepository()
        val cancellation = CancellationException("cancelled")

        val failure =
            assertFailsWith<CancellationException> {
                backing.withChannelCacheReconciliation(normalizedSettings) {
                    markChannelWriteIssued()
                    throw cancellation
                }
            }

        assertSame(cancellation, failure)
        assertEquals(normalizedSettings, backing.currentChannelSet.settings)
    }
}
