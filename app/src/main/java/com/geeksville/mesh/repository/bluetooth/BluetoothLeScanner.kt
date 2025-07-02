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

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import androidx.annotation.RequiresPermission
import com.geeksville.mesh.android.Logging
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

@RequiresPermission("android.permission.BLUETOOTH_SCAN")
internal fun BluetoothLeScanner.scan(
    filters: List<ScanFilter> = emptyList(),
    scanSettings: ScanSettings = ScanSettings.Builder().build(),
): Flow<ScanResult> = callbackFlow {
    val logger = object : Logging {}
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            logger.debug("BluetoothLeScanner: onScanResult received")
            trySend(result)
        }

        override fun onScanFailed(errorCode: Int) {
            logger.errormsg("BluetoothLeScanner: onScanFailed with errorCode: $errorCode")
            cancel("onScanFailed() called with errorCode: $errorCode")
        }
    }

    logger.debug("BluetoothLeScanner: starting scan with callback $callback")
    try {
        startScan(filters, scanSettings, callback)
        logger.debug("BluetoothLeScanner: startScan() completed successfully")
    } catch (ex: Exception) {
        logger.errormsg("BluetoothLeScanner: startScan() failed: ${ex.message}")
        throw ex
    }

    awaitClose {
        logger.debug("BluetoothLeScanner: awaitClose called, stopping scan with callback $callback")
        try {
            stopScan(callback)
            logger.debug("BluetoothLeScanner: stopScan() completed successfully")
        } catch (ex: Exception) {
            logger.warn("BluetoothLeScanner: stopScan() failed: ${ex.message}")
        }
    }
}
