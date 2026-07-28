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

import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.Position
import org.meshtastic.core.repository.AwaitedSendResult
import org.meshtastic.core.repository.AwaitedSendStatus
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.LocalConfig

/** Shared [CommandSender] fake with packet capture and lockdown-command evidence. */
@Suppress("TooManyFunctions")
class FakeCommandSender :
    BaseFake(),
    CommandSender {
    val sentPackets = mutableListOf<DataPacket>()
    val sentAdminMessages = mutableListOf<AdminMessage>()

    var packetIdSnapshot = 0L
    var localConfigSnapshot = LocalConfig()
    var channelSetSnapshot = ChannelSet()
    var nextPacketId = 1
    var awaitedSendResult = AwaitedSendResult(AwaitedSendStatus.ACCEPTED, dispatched = true)

    var lastPassphrase: String? = null
    var lastBoots = 0
    var lastHours = 0
    var lastMaxSessionSeconds = 0
    var lastDisable = false
    var lockNowCalled = false

    init {
        registerResetAction {
            sentPackets.clear()
            sentAdminMessages.clear()
            packetIdSnapshot = 0L
            localConfigSnapshot = LocalConfig()
            channelSetSnapshot = ChannelSet()
            nextPacketId = 1
            awaitedSendResult = AwaitedSendResult(AwaitedSendStatus.ACCEPTED, dispatched = true)
            lastPassphrase = null
            lastBoots = 0
            lastHours = 0
            lastMaxSessionSeconds = 0
            lastDisable = false
            lockNowCalled = false
        }
    }

    override fun getCurrentPacketId(): Long = packetIdSnapshot

    override fun getCachedLocalConfig(): LocalConfig = localConfigSnapshot

    override fun getCachedChannelSet(): ChannelSet = channelSetSnapshot

    override fun generatePacketId(): Int {
        val id = nextPacketId.coerceAtLeast(1)
        nextPacketId = if (id == Int.MAX_VALUE) 1 else id + 1
        packetIdSnapshot = id.toLong()
        return id
    }

    override suspend fun sendData(p: DataPacket) {
        sentPackets.add(p)
    }

    override suspend fun sendAdmin(destNum: Int, requestId: Int, wantResponse: Boolean, initFn: () -> AdminMessage) {
        sentAdminMessages.add(initFn())
    }

    override fun sendAdminImmediate(destNum: Int, initFn: () -> AdminMessage) {
        sentAdminMessages.add(initFn())
    }

    override suspend fun sendAdminAwaitResult(
        destNum: Int,
        requestId: Int,
        wantResponse: Boolean,
        initFn: () -> AdminMessage,
    ): AwaitedSendResult {
        sentAdminMessages.add(initFn())
        return awaitedSendResult
    }

    override suspend fun sendPosition(pos: org.meshtastic.proto.Position, destNum: Int?, wantResponse: Boolean) = Unit

    override suspend fun requestPosition(destNum: Int, currentPosition: Position) = Unit

    override suspend fun setFixedPosition(destNum: Int, pos: Position) = Unit

    override suspend fun requestUserInfo(destNum: Int) = Unit

    override suspend fun requestTraceroute(requestId: Int, destNum: Int) = Unit

    override suspend fun requestTelemetry(requestId: Int, destNum: Int, typeValue: Int) = Unit

    override suspend fun requestNeighborInfo(requestId: Int, destNum: Int) = Unit

    override fun sendLockdownPassphrase(
        passphrase: String,
        boots: Int,
        hours: Int,
        maxSessionSeconds: Int,
        disable: Boolean,
    ) {
        lastPassphrase = passphrase
        lastBoots = boots
        lastHours = hours
        lastMaxSessionSeconds = maxSessionSeconds
        lastDisable = disable
    }

    override fun sendLockNow() {
        lockNowCalled = true
    }
}
