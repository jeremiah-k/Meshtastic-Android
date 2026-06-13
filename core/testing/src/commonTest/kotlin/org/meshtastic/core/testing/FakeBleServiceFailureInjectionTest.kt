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
package org.meshtastic.core.testing

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.ble.BleCharacteristic
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FakeBleServiceFailureInjectionTest {
    private val char = BleCharacteristic(Uuid.random())
    private val service = FakeBleService()

    @Test
    fun writeExceptionIsPersistent() = runTest {
        service.writeException = Exception("write-fail")
        assertFailsWith<Exception> { service.write(char, ByteArray(0)) }
        // writeException is persistent — tests must explicitly clear it
        service.writeException = null
        service.write(char, ByteArray(1))
        assertTrue(service.writes.size == 1)
    }

    @Test
    fun readExceptionIsThrownAndReset() = runTest {
        service.readException = Exception("read-fail")
        assertFailsWith<Exception> { service.read(char) }
        assertTrue(service.read(char).isEmpty()) // normal after reset
    }

    @Test
    fun observeExceptionCausesFlowException() = runTest {
        service.observeException = Exception("observe-fail")
        val flow = service.observe(char)
        assertFailsWith<Exception> { flow.collect() }
    }
}
