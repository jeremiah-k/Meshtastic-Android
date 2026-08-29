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
package org.meshtastic.core.network.radio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.ble.BleConnection
import org.meshtastic.core.ble.BleConnectionFactory
import org.meshtastic.core.ble.MeshtasticBleConstants.FROMNUM_CHARACTERISTIC
import org.meshtastic.core.ble.MeshtasticBleConstants.FROMRADIO_CHARACTERISTIC
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.testing.FakeBleConnection
import org.meshtastic.core.testing.FakeBleDevice
import org.meshtastic.core.testing.FakeBleScanner
import org.meshtastic.core.testing.FakeBluetoothRepository
import org.meshtastic.core.testing.FakeRadioInterfaceService
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class BleRadioTransportConnectionPriorityTest {

    /** Counts connection-priority requests while delegating every other [BleConnection] operation to the fake. */
    private class PriorityCountingBleConnection(private val delegate: BleConnection) : BleConnection by delegate {
        var highPriorityRequests = 0
            private set

        var balancedPriorityRequests = 0
            private set

        override fun requestHighConnectionPriority(): Boolean {
            highPriorityRequests++
            return delegate.requestHighConnectionPriority()
        }

        override fun requestBalancedConnectionPriority(): Boolean {
            balancedPriorityRequests++
            return delegate.requestBalancedConnectionPriority()
        }
    }

    private class PriorityCountingBleConnectionFactory(private val connection: BleConnection) : BleConnectionFactory {
        override fun create(scope: CoroutineScope, tag: String): BleConnection = connection
    }

    @Test
    fun `normal radio sessions leave BLE connection priority negotiation to the peripheral`() = runTest {
        val address = "00:11:22:33:44:55"
        val scanner = FakeBleScanner()
        val bluetoothRepository = FakeBluetoothRepository()
        val fakeConnection = FakeBleConnection()
        val connection = PriorityCountingBleConnection(fakeConnection)
        val device = FakeBleDevice(address = address, name = "Test Device")
        bluetoothRepository.setHasPermissions(true)
        bluetoothRepository.setBluetoothEnabled(true)
        bluetoothRepository.bond(device)
        scanner.emitDevice(device)
        fakeConnection.service.addCharacteristic(FROMNUM_CHARACTERISTIC)
        fakeConnection.service.addCharacteristic(FROMRADIO_CHARACTERISTIC)
        val callback = FakeRadioInterfaceService()
        val bleTransport =
            BleRadioTransport(
                scope = backgroundScope,
                scanner = scanner,
                bluetoothRepository = bluetoothRepository,
                connectionFactory = PriorityCountingBleConnectionFactory(connection),
                callback = callback,
                address = address,
            )

        try {
            bleTransport.start()
            // 3 s reconnect settle delay plus connect and profile setup — still far short of the legacy 30 s downgrade
            // timer, so a scheduled downgrade would already be observable within this window.
            advanceTimeBy(4_000)
            runCurrent()

            assertEquals(
                ConnectionState.Connected,
                callback.connectionState.value,
                "the normal session must reach the ready state for these guards to be meaningful",
            )
            assertEquals(
                0,
                connection.highPriorityRequests,
                "normal radio setup must not issue a competing central-side connection parameter request",
            )
            assertEquals(
                0,
                connection.balancedPriorityRequests,
                "normal radio setup must not schedule a priority downgrade; the peripheral owns connection parameters",
            )
        } finally {
            bleTransport.close()
        }
    }
}
