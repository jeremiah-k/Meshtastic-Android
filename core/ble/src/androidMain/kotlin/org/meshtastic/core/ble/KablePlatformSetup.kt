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
@file:Suppress("TooManyFunctions")

package org.meshtastic.core.ble

import android.bluetooth.BluetoothGatt
import co.touchlab.kermit.Logger
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.PooledThreadingStrategy
import com.juul.kable.toIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Shared thread pool for Kable BLE connections.
 *
 * [PooledThreadingStrategy] reuses handler threads across reconnect cycles, avoiding the overhead of creating a new
 * thread per connection attempt that [OnDemandThreadingStrategy][com.juul.kable.OnDemandThreadingStrategy] incurs. Idle
 * threads are evicted after 1 minute (default).
 *
 * A single app-wide instance is used because Kable recommends exactly one pool per application.
 */
private val sharedThreadingStrategy = PooledThreadingStrategy()

internal actual fun PeripheralBuilder.platformConfig(device: BleDevice, autoConnect: () -> Boolean) {
    // Bonded devices without a fresh advertisement must use autoConnect = true. Otherwise,
    // Android's direct connect algorithm often fails with GATT 133 or times out, especially
    // if the device uses random resolvable addresses. Scanned devices (advertisement != null)
    // use direct connection (autoConnect = false) for faster initial connects.
    autoConnectIf(autoConnect)

    threadingStrategy = sharedThreadingStrategy

    // We intentionally keep Kable's defaults for `transport` (Le) and `phy` (Le1M).
    // Meshtastic radios (nRF52, ESP32-S3, RP2040+nRF) advertise BLE-only and don't support
    // the LE 2M PHY in any first-party firmware, so changing these would be a regression risk
    // with no upside. If a future hardware revision exposes 2M PHY, override `phy = Phy.Le2M`
    // here after confirming the firmware advertises it.

    onServicesDiscovered {
        try {
            // Android defaults to 23 bytes MTU. Meshtastic packets can be 512 bytes.
            // Requesting the max MTU is critical for preventing dropped packets and stalls.
            @Suppress("MagicNumber")
            val negotiatedMtu = requestMtu(512)
            Logger.i { "[${device.address}] Negotiated MTU: $negotiatedMtu" }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Logger.w(e) { "[${device.address}] Failed to request MTU" }
        }
    }
}

internal actual fun createPeripheral(address: String, builderAction: PeripheralBuilder.() -> Unit): Peripheral =
    com.juul.kable.Peripheral(address.toIdentifier(), builderAction)

/** ATT protocol header size (opcode + handle) subtracted from MTU to get the usable payload. */
private const val ATT_HEADER_SIZE = 3

internal actual fun Peripheral.negotiatedMaxWriteLength(): Int? {
    val mtu = (this as? AndroidPeripheral)?.mtu?.value ?: return null
    return (mtu - ATT_HEADER_SIZE).takeIf { it > 0 }
}

internal actual fun Peripheral.requestHighConnectionPriority(): Boolean {
    val androidPeripheral = this as? AndroidPeripheral ?: return false
    return runCatching { androidPeripheral.requestConnectionPriority(AndroidPeripheral.Priority.High) }
        .onFailure { Logger.w(it) { "requestConnectionPriority(High) threw" } }
        .getOrDefault(false)
}

internal actual fun Peripheral.requestBalancedConnectionPriority(): Boolean {
    val androidPeripheral = this as? AndroidPeripheral ?: return false
    return runCatching { androidPeripheral.requestConnectionPriority(AndroidPeripheral.Priority.Balanced) }
        .onFailure { Logger.w(it) { "requestConnectionPriority(Balanced) threw" } }
        .getOrDefault(false)
}

@Suppress("ReturnCount")
internal actual fun Peripheral.refreshGattCache(connectionScope: CoroutineScope?): Boolean {
    return try {
        // Kable stores BluetoothGatt on its internal Connection object, which is owned by AndroidPeripheral. The
        // connection scope alone is only a coroutine wrapper in current Kable releases, so it is just a fallback
        // diagnostic root here.
        // If Kable exposes a public cache-refresh/invalidate API later, replace this reflection with that API.
        val roots = gattSearchRoots(connectionScope)
        val rootNames = roots.joinToString { it.javaClass.name }
        val target = findBluetoothGatt(roots)
        if (target == null) {
            Logger.w {
                "refreshGattCache: no BluetoothGatt field found from roots [$rootNames]; " +
                    "androidPeripheral=${this is AndroidPeripheral}"
            }
            return false
        }

        val refreshMethod = target.gatt.javaClass.getMethod("refresh")
        val result = refreshMethod.invoke(target.gatt) as? Boolean ?: false
        Logger.i { "refreshGattCache: found BluetoothGatt on ${target.ownerClassName}; refresh() returned $result" }
        result
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Logger.w(e) { "refreshGattCache: failed to invoke BluetoothGatt.refresh()" }
        false
    }
}

private const val MAX_GATT_SEARCH_DEPTH = 2

private data class GattSearchNode(val owner: Any, val depth: Int)

private data class GattTarget(val gatt: BluetoothGatt, val ownerClassName: String)

private fun Peripheral.gattSearchRoots(connectionScope: CoroutineScope?): List<Any> {
    val roots = mutableListOf<Any>(this)
    kableConnectionObject()?.let(roots::add)
    connectionScope?.let(roots::add)
    return roots
}

@Suppress("ReturnCount")
private fun Peripheral.kableConnectionObject(): Any? {
    invokeNoArgMethod("connectionOrThrow")?.let {
        return it
    }
    val connectionHolder =
        javaClass.allInstanceFields().firstOrNull { it.name == "connection" }?.readValue(this) ?: return null

    return if (connectionHolder is StateFlow<*>) connectionHolder.value else connectionHolder
}

@Suppress("ReturnCount")
private fun findBluetoothGatt(roots: List<Any>): GattTarget? {
    val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    val queue = ArrayDeque<GattSearchNode>()
    roots.forEach { queue.add(GattSearchNode(it, depth = 0)) }

    while (queue.isNotEmpty()) {
        val (owner, depth) = queue.removeFirst()
        if (!visited.add(owner)) continue

        if (owner is BluetoothGatt) return GattTarget(owner, owner.javaClass.name)

        owner.javaClass.allInstanceFields().forEach { field ->
            val value = field.readValue(owner) ?: return@forEach
            if (value is BluetoothGatt) {
                return GattTarget(value, owner.javaClass.name)
            }
            if (depth < MAX_GATT_SEARCH_DEPTH && shouldInspectGattOwner(value)) {
                queue.add(GattSearchNode(value, depth + 1))
            }
        }
    }

    return null
}

private fun Any.invokeNoArgMethod(name: String): Any? = javaClass
    .allDeclaredMethods()
    .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
    ?.runCatchingInvoke(this)

private fun Class<*>.allInstanceFields(): Sequence<Field> = sequence {
    var clazz: Class<*>? = this@allInstanceFields
    while (clazz != null && clazz != Any::class.java) {
        clazz.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { yield(it) }
        clazz = clazz.superclass
    }
}

private fun Class<*>.allDeclaredMethods() = sequence {
    var clazz: Class<*>? = this@allDeclaredMethods
    while (clazz != null && clazz != Any::class.java) {
        clazz.declaredMethods.forEach { yield(it) }
        clazz = clazz.superclass
    }
}

private fun Field.readValue(owner: Any): Any? = runCatching {
    isAccessible = true
    get(owner)
}
    .getOrNull()

private fun java.lang.reflect.Method.runCatchingInvoke(owner: Any): Any? = runCatching {
    isAccessible = true
    invoke(owner)
}
    .getOrNull()

@Suppress("ReturnCount")
private fun shouldInspectGattOwner(value: Any): Boolean {
    val clazz = value.javaClass
    if (clazz.isArray || clazz.isEnum) return false
    if (value is String || value is Number || value is Boolean || value is Char) return false

    val className = clazz.name
    return !className.startsWith("android.") &&
        !className.startsWith("java.") &&
        !className.startsWith("javax.") &&
        !className.startsWith("kotlin.") &&
        !className.startsWith("kotlinx.coroutines.")
}
