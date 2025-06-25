/*
 * Copyright (c) 2025 Meshtastic LLC
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

package com.geeksville.mesh.repository.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.geeksville.mesh.android.Logging
import com.geeksville.mesh.util.exceptionReporter

/**
 * Handles Bluetooth pairing requests to ensure proper pairing dialog behavior.
 * 
 * This receiver is essential for handling system-level Bluetooth pairing requests.
 * Without it, the pairing dialog may close prematurely or fail to respond to user input.
 */
class BluetoothPairingReceiver : BroadcastReceiver(), Logging {
    
    override fun onReceive(context: Context, intent: Intent) = exceptionReporter {
        when (intent.action) {
            BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                val passkey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
                
                debug("Received pairing request for device: ${device?.name}, variant: $pairingVariant, passkey: $passkey")
                
                // Let the system handle the pairing dialog
                // This receiver ensures the app stays responsive during pairing
                when (pairingVariant) {
                    BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION,
                    BluetoothDevice.PAIRING_VARIANT_CONSENT -> {
                        // For passkey confirmation and consent, let system dialog handle it
                        debug("Allowing system to handle pairing variant: $pairingVariant")
                    }
                    BluetoothDevice.PAIRING_VARIANT_DISPLAY_PASSKEY,
                    BluetoothDevice.PAIRING_VARIANT_DISPLAY_PIN -> {
                        // For display variants, system will show the PIN/passkey
                        debug("System will display pairing code for variant: $pairingVariant")
                    }
                    BluetoothDevice.PAIRING_VARIANT_PASSKEY,
                    BluetoothDevice.PAIRING_VARIANT_PIN -> {
                        // For input variants, system dialog will handle user input
                        debug("System will request user input for variant: $pairingVariant")
                    }
                    else -> {
                        debug("Unknown pairing variant: $pairingVariant")
                    }
                }
            }
        }
    }
}
