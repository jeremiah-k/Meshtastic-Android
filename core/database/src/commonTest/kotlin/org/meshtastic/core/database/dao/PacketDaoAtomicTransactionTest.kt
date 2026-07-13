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
package org.meshtastic.core.database.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.database.MeshtasticDatabase
import org.meshtastic.core.database.entity.ContactSettings
import org.meshtastic.core.database.entity.MyNodeEntity
import org.meshtastic.core.database.entity.Packet
import org.meshtastic.core.database.entity.ReactionEntity
import org.meshtastic.core.database.getInMemoryDatabaseBuilder
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.proto.PortNum
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the atomic read-modify-write transactions in [PacketDao]: [updateLastReadMessage], [updatePacketByKey],
 * [updateReactionByKey], [applySFPPStatus], [applySFPPStatusByHash], and [deleteMessagesAtomic]. Each test exercises
 * the transaction boundary — monotonic guards, identity-key matching, no-downgrade invariants, and chunk-boundary
 * deletes — that a non-atomic implementation would race.
 */
class PacketDaoAtomicTransactionTest {
    private lateinit var database: MeshtasticDatabase
    private lateinit var packetDao: PacketDao

    private val myNodeNum = 42424242

    private val sfppHash: ByteString = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).toByteString()
    private val otherHash: ByteString = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16).toByteString()

    private val myNodeEntity =
        MyNodeEntity(
            myNodeNum = myNodeNum,
            model = null,
            firmwareVersion = null,
            couldUpdate = false,
            shouldUpdate = false,
            currentPacketId = 1L,
            messageTimeoutMsec = 5 * 60 * 1000,
            minAppVersion = 1,
            maxChannels = 8,
            hasWifi = false,
        )

    @BeforeTest
    fun setUp() {
        database = getInMemoryDatabaseBuilder().build()
        packetDao = database.packetDao()
    }

    /** Seeds the MyNodeInfo row required by packet queries. Must be called inside each test's runTest block. */
    private suspend fun seedMyNodeInfo() {
        database.nodeInfoDao().setMyNodeInfo(myNodeEntity)
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun textPacket(
        contact: String,
        text: String,
        time: Long,
        packetId: Int = 0,
        from: String? = NodeAddress.ID_LOCAL,
        to: String? = NodeAddress.ID_BROADCAST,
        status: MessageStatus = MessageStatus.UNKNOWN,
        routingError: Int = -1,
        sfppHash: ByteString? = null,
    ) = Packet(
        uuid = 0L,
        myNodeNum = myNodeNum,
        port_num = PortNum.TEXT_MESSAGE_APP.value,
        contact_key = contact,
        received_time = time,
        read = true,
        data =
        DataPacket(
            to = to,
            bytes = text.encodeToByteArray().toByteString(),
            dataType = PortNum.TEXT_MESSAGE_APP.value,
            from = from,
            id = packetId,
            status = status,
            sfppHash = sfppHash,
        ),
        packetId = packetId,
        routingError = routingError,
        sfpp_hash = sfppHash,
        messageText = text,
    )

    private fun reaction(
        replyId: Int,
        userId: String,
        emoji: String,
        timestamp: Long,
        packetId: Int = replyId,
        to: String? = null,
        status: MessageStatus = MessageStatus.UNKNOWN,
        sfppHash: ByteString? = null,
    ) = ReactionEntity(
        myNodeNum = myNodeNum,
        replyId = replyId,
        userId = userId,
        emoji = emoji,
        timestamp = timestamp,
        packetId = packetId,
        to = to,
        status = status,
        sfpp_hash = sfppHash,
    )

    // ── updateLastReadMessage ────────────────────────────────────────────────────

    @Test
    fun updateLastReadMessageCreatesSettingsWhenAbsent() = runTest {
        seedMyNodeInfo()
        val contact = "0${NodeAddress.ID_BROADCAST}"
        packetDao.updateLastReadMessage(contact, messageUuid = 42L, lastReadTimestamp = 1000L)

        val settings = packetDao.getContactSettings(contact)
        assertNotNull(settings)
        assertEquals(42L, settings!!.lastReadMessageUuid)
        assertEquals(1000L, settings.lastReadMessageTimestamp)
    }

    @Test
    fun updateLastReadMessageAcceptsNewerTimestamp() = runTest {
        seedMyNodeInfo()
        val contact = "0${NodeAddress.ID_BROADCAST}"
        packetDao.updateLastReadMessage(contact, messageUuid = 42L, lastReadTimestamp = 2000L)
        packetDao.updateLastReadMessage(contact, messageUuid = 99L, lastReadTimestamp = 3000L)

        val settings = packetDao.getContactSettings(contact)
        assertNotNull(settings)
        assertEquals(99L, settings!!.lastReadMessageUuid)
        assertEquals(3000L, settings.lastReadMessageTimestamp)
    }

    @Test
    fun updateLastReadMessageRejectsOlderTimestamp() = runTest {
        seedMyNodeInfo()
        val contact = "0${NodeAddress.ID_BROADCAST}"
        packetDao.updateLastReadMessage(contact, messageUuid = 42L, lastReadTimestamp = 2000L)
        packetDao.updateLastReadMessage(contact, messageUuid = 99L, lastReadTimestamp = 1000L)

        val settings = packetDao.getContactSettings(contact)
        assertNotNull(settings)
        assertEquals(42L, settings!!.lastReadMessageUuid)
        assertEquals(2000L, settings.lastReadMessageTimestamp)
    }

    /**
     * Verifies that [PacketDao.updateLastReadMessage] preserves unrelated contact-settings columns (muteUntil,
     * filteringDisabled) that were set before the last-read update. The INSERT-IGNORE + conditional-UPDATE pattern must
     * never overwrite these fields with defaults.
     */
    @Test
    fun updateLastReadMessagePreservesUnrelatedFields() = runTest {
        seedMyNodeInfo()
        val contact = "0${NodeAddress.ID_BROADCAST}"

        // Seed with non-default muteUntil and filteringDisabled
        packetDao.insertContactSettingsIgnore(
            listOf(ContactSettings(contact_key = contact, muteUntil = 999_999_999L, filteringDisabled = true)),
        )

        // Perform the last-read update (newer timestamp)
        packetDao.updateLastReadMessage(contact, messageUuid = 42L, lastReadTimestamp = 3000L)

        val settings = packetDao.getContactSettings(contact)
        assertNotNull(settings)
        assertEquals(42L, settings!!.lastReadMessageUuid, "lastReadMessageUuid should be updated")
        assertEquals(3000L, settings.lastReadMessageTimestamp, "lastReadMessageTimestamp should be updated")
        assertEquals(999_999_999L, settings.muteUntil, "muteUntil must be preserved")
        assertTrue(settings.filteringDisabled, "filteringDisabled must be preserved")
    }

    /** Verifies that updating contact A's last-read position does not alter contact B. */
    @Test
    fun updateLastReadMessageIsContactScoped() = runTest {
        seedMyNodeInfo()
        val contactA = "0${NodeAddress.ID_BROADCAST}"
        val contactB = "0!abcdef01"

        packetDao.updateLastReadMessage(contactA, messageUuid = 10L, lastReadTimestamp = 1000L)
        packetDao.updateLastReadMessage(contactB, messageUuid = 20L, lastReadTimestamp = 2000L)
        packetDao.updateLastReadMessage(contactA, messageUuid = 30L, lastReadTimestamp = 3000L)

        val settingsA = packetDao.getContactSettings(contactA)
        assertNotNull(settingsA)
        assertEquals(30L, settingsA!!.lastReadMessageUuid, "contact A updated to 30")
        assertEquals(3000L, settingsA.lastReadMessageTimestamp)

        val settingsB = packetDao.getContactSettings(contactB)
        assertNotNull(settingsB)
        assertEquals(20L, settingsB!!.lastReadMessageUuid, "contact B unchanged at 20")
        assertEquals(2000L, settingsB.lastReadMessageTimestamp)
    }

    @Test
    fun updateLastReadMessageEqualTimestampIsNoOp() = runTest {
        seedMyNodeInfo()
        val contact = "0${NodeAddress.ID_BROADCAST}"
        packetDao.updateLastReadMessage(contact, messageUuid = 42L, lastReadTimestamp = 2000L)
        packetDao.updateLastReadMessage(contact, messageUuid = 99L, lastReadTimestamp = 2000L)

        val settings = packetDao.getContactSettings(contact)
        assertNotNull(settings)
        assertEquals(42L, settings!!.lastReadMessageUuid)
        assertEquals(2000L, settings.lastReadMessageTimestamp)
    }

    // ── updatePacketByKey ────────────────────────────────────────────────────────

    @Test
    fun updatePacketByKeyUpdatesOnlyMatchingIdentity() = runTest {
        seedMyNodeInfo()
        val matching = textPacket("contact", "match", time = 100, packetId = 50, from = "!aa", to = "^all")
        val sameIdDiffFrom = textPacket("contact", "other", time = 200, packetId = 50, from = "!bb", to = "^all")
        packetDao.insert(matching)
        packetDao.insert(sameIdDiffFrom)

        val newData = matching.data.copy(status = MessageStatus.DELIVERED)
        packetDao.updatePacketByKey(newData, routingError = -1)

        val packets = packetDao.findPacketsWithId(50)
        val updated = packets.find { it.data.from == "!aa" }
        val untouched = packets.find { it.data.from == "!bb" }
        assertEquals(MessageStatus.DELIVERED, updated?.data?.status)
        assertEquals(MessageStatus.UNKNOWN, untouched?.data?.status)
    }

    @Test
    fun updatePacketByKeyAppliesRoutingErrorWhenNonNegative() = runTest {
        seedMyNodeInfo()
        val packet = textPacket("contact", "msg", time = 100, packetId = 60, from = "!aa", to = "^all")
        packetDao.insert(packet)

        packetDao.updatePacketByKey(packet.data, routingError = 7)

        val updated = packetDao.findPacketsWithId(60).first()
        assertEquals(7, updated.routingError)
    }

    @Test
    fun updatePacketByKeyLeavesRoutingErrorUnchangedWhenMinusOne() = runTest {
        seedMyNodeInfo()
        val packet =
            textPacket("contact", "msg", time = 100, packetId = 70, from = "!aa", to = "^all", routingError = 3)
        packetDao.insert(packet)

        val newData = packet.data.copy(status = MessageStatus.DELIVERED)
        packetDao.updatePacketByKey(newData, routingError = -1)

        val updated = packetDao.findPacketsWithId(70).first()
        assertEquals(3, updated.routingError, "routingError should be preserved, not reset to -1")
        assertEquals(MessageStatus.DELIVERED, updated.data.status)
    }

    // ── updateReactionByKey ──────────────────────────────────────────────────────

    @Test
    fun updateReactionByKeyUpdatesMatchingReaction() = runTest {
        seedMyNodeInfo()
        packetDao.insert(textPacket("contact", "msg", time = 100, packetId = 80))
        packetDao.insert(reaction(replyId = 80, userId = "!u", emoji = "👍", timestamp = 1, packetId = 80))

        val replacement =
            reaction(
                replyId = 80,
                userId = "!u",
                emoji = "👍",
                timestamp = 999,
                packetId = 80,
                status = MessageStatus.SFPP_CONFIRMED,
                sfppHash = sfppHash,
            )
                .copy(myNodeNum = 0) // placeholder — DAO must borrow from existing
        packetDao.updateReactionByKey(replacement)

        val updated = packetDao.findReactionsWithId(80).find { it.userId == "!u" && it.emoji == "👍" }
        assertNotNull(updated)
        assertEquals(999, updated!!.timestamp)
        assertEquals(MessageStatus.SFPP_CONFIRMED, updated.status)
        assertEquals(sfppHash, updated.sfpp_hash)
    }

    @Test
    fun updateReactionByKeyPreservesExistingMyNodeNum() = runTest {
        seedMyNodeInfo()
        packetDao.insert(textPacket("contact", "msg", time = 100, packetId = 81))
        packetDao.insert(reaction(replyId = 81, userId = "!u", emoji = "❤️", timestamp = 1, packetId = 81))

        val replacement =
            ReactionEntity(
                myNodeNum = 0, // placeholder — DAO must overwrite with existing
                replyId = 81,
                userId = "!u",
                emoji = "❤️",
                timestamp = 555,
                packetId = 81,
            )
        packetDao.updateReactionByKey(replacement)

        val updated = packetDao.findReactionsWithId(81).find { it.userId == "!u" && it.emoji == "❤️" }
        assertNotNull(updated)
        assertEquals(myNodeNum, updated!!.myNodeNum, "myNodeNum must be borrowed from existing row, not 0")
        assertEquals(555, updated.timestamp)
    }

    @Test
    fun updateReactionByKeyLeavesNonMatchingEmojiUntouched() = runTest {
        seedMyNodeInfo()
        packetDao.insert(textPacket("contact", "msg", time = 100, packetId = 82))
        packetDao.insert(reaction(replyId = 82, userId = "!u", emoji = "👍", timestamp = 1, packetId = 82))

        val replacement =
            ReactionEntity(
                myNodeNum = myNodeNum,
                replyId = 82,
                userId = "!u",
                emoji = "❤️", // different emoji — no match
                timestamp = 999,
                packetId = 82,
            )
        packetDao.updateReactionByKey(replacement)

        val reactions = packetDao.findReactionsWithId(82)
        assertEquals(1, reactions.size)
        assertEquals("👍", reactions.first().emoji)
        assertEquals(1, reactions.first().timestamp, "original reaction untouched")
    }

    @Test
    fun updateReactionByKeyIsNoOpWhenNoMatch() = runTest {
        seedMyNodeInfo()
        packetDao.insert(reaction(replyId = 83, userId = "!u", emoji = "👍", timestamp = 1, packetId = 83))

        val replacement =
            ReactionEntity(
                myNodeNum = myNodeNum,
                replyId = 83,
                userId = "!other", // different user — no match
                emoji = "👍",
                timestamp = 999,
                packetId = 83,
            )
        packetDao.updateReactionByKey(replacement)

        val reactions = packetDao.findReactionsWithId(83)
        assertEquals(1, reactions.size)
        assertEquals(1, reactions.first().timestamp, "no reaction updated")
    }

    // ── applySFPPStatus ──────────────────────────────────────────────────────────

    @Test
    fun applySFPPStatusUpdatesMatchingPacketAndReactionInOneTransaction() = runTest {
        seedMyNodeInfo()
        val packetId = 100
        packetDao.insert(
            textPacket(
                "contact",
                "msg",
                time = 1000,
                packetId = packetId,
                from = NodeAddress.ID_LOCAL,
                to = NodeAddress.ID_BROADCAST,
            ),
        )
        packetDao.insert(
            reaction(
                replyId = packetId,
                userId = NodeAddress.ID_LOCAL,
                emoji = "👍",
                timestamp = 1,
                packetId = packetId,
                to = NodeAddress.ID_BROADCAST,
            ),
        )

        packetDao.applySFPPStatus(
            packetId = packetId,
            from = myNodeNum,
            to = 0,
            hash = sfppHash,
            status = MessageStatus.SFPP_ROUTING,
            rxTime = 5L,
            myNodeNum = myNodeNum,
        )

        val packet = packetDao.findPacketsWithId(packetId).first()
        assertEquals(MessageStatus.SFPP_ROUTING, packet.data.status)
        assertEquals(sfppHash, packet.data.sfppHash)
        assertEquals(sfppHash, packet.sfpp_hash)
        assertEquals(5_000L, packet.data.time, "rxTime should be converted to millis")
        assertEquals(5_000L, packet.received_time)

        val reaction = packetDao.findReactionsWithId(packetId).first()
        assertEquals(MessageStatus.SFPP_ROUTING, reaction.status)
        assertEquals(sfppHash, reaction.sfpp_hash)
    }

    @Test
    fun applySFPPStatusMatchesLocalNodeWithIdLocal() = runTest {
        seedMyNodeInfo()
        val packetId = 110
        // Packet stored with from = "^local" — local node sends with ID_LOCAL.
        packetDao.insert(
            textPacket(
                "contact",
                "msg",
                time = 1000,
                packetId = packetId,
                from = NodeAddress.ID_LOCAL,
                to = NodeAddress.ID_BROADCAST,
            ),
        )

        // from = myNodeNum triggers isFromLocalNode = true, which matches data.from == ID_LOCAL.
        packetDao.applySFPPStatus(
            packetId = packetId,
            from = myNodeNum,
            to = 0,
            hash = sfppHash,
            status = MessageStatus.SFPP_ROUTING,
            rxTime = 0L,
            myNodeNum = myNodeNum,
        )

        val packet = packetDao.findPacketsWithId(packetId).first()
        assertEquals(MessageStatus.SFPP_ROUTING, packet.data.status)
    }

    @Test
    fun applySFPPStatusBroadcastDestinationMatchesIdBroadcast() = runTest {
        seedMyNodeInfo()
        val packetId = 120
        // to = 0 (broadcast) should match data.to == "^all" (ID_BROADCAST).
        packetDao.insert(
            textPacket(
                "contact",
                "msg",
                time = 1000,
                packetId = packetId,
                from = NodeAddress.ID_LOCAL,
                to = NodeAddress.ID_BROADCAST,
            ),
        )

        packetDao.applySFPPStatus(
            packetId = packetId,
            from = myNodeNum,
            to = 0,
            hash = sfppHash,
            status = MessageStatus.SFPP_ROUTING,
            rxTime = 0L,
            myNodeNum = myNodeNum,
        )

        val packet = packetDao.findPacketsWithId(packetId).first()
        assertEquals(MessageStatus.SFPP_ROUTING, packet.data.status)
    }

    @Test
    fun applySFPPStatusDoesNotDowngradeConfirmedToRouting() = runTest {
        seedMyNodeInfo()
        val packetId = 130
        packetDao.insert(
            textPacket(
                "contact",
                "msg",
                time = 1000,
                packetId = packetId,
                from = NodeAddress.ID_LOCAL,
                to = NodeAddress.ID_BROADCAST,
                status = MessageStatus.SFPP_CONFIRMED,
            ),
        )
        packetDao.insert(
            reaction(
                replyId = packetId,
                userId = NodeAddress.ID_LOCAL,
                emoji = "👍",
                timestamp = 1,
                packetId = packetId,
                to = NodeAddress.ID_BROADCAST,
                status = MessageStatus.SFPP_CONFIRMED,
            ),
        )

        packetDao.applySFPPStatus(
            packetId = packetId,
            from = myNodeNum,
            to = 0,
            hash = sfppHash,
            status = MessageStatus.SFPP_ROUTING,
            rxTime = 5L,
            myNodeNum = myNodeNum,
        )

        val packet = packetDao.findPacketsWithId(packetId).first()
        assertEquals(MessageStatus.SFPP_CONFIRMED, packet.data.status, "packet must not be downgraded")

        val reaction = packetDao.findReactionsWithId(packetId).first()
        assertEquals(MessageStatus.SFPP_CONFIRMED, reaction.status, "reaction must not be downgraded")
    }

    @Test
    fun applySFPPStatusLeavesNonMatchingAddressesUntouched() = runTest {
        seedMyNodeInfo()
        val packetId = 140
        // Packet from node 123 (!0000007b), not from local node.
        packetDao.insert(
            textPacket(
                "contact",
                "msg",
                time = 1000,
                packetId = packetId,
                from = "!0000007b",
                to = NodeAddress.ID_BROADCAST,
            ),
        )

        // Call with from = 456 (!000001c8) — should NOT match.
        packetDao.applySFPPStatus(
            packetId = packetId,
            from = 456,
            to = 0,
            hash = sfppHash,
            status = MessageStatus.SFPP_ROUTING,
            rxTime = 0L,
            myNodeNum = myNodeNum,
        )

        val packet = packetDao.findPacketsWithId(packetId).first()
        assertEquals(MessageStatus.UNKNOWN, packet.data.status, "non-matching packet should be untouched")
        assertNull(packet.sfpp_hash)
    }

    // ── applySFPPStatusByHash ────────────────────────────────────────────────────

    @Test
    fun applySFPPStatusByHashMatchesByHashPrefix() = runTest {
        seedMyNodeInfo()
        val packetId = 200
        packetDao.insert(textPacket("contact", "msg", time = 1000, packetId = packetId, sfppHash = sfppHash))
        packetDao.insert(
            reaction(
                replyId = packetId,
                userId = "!u",
                emoji = "👍",
                timestamp = 1,
                packetId = packetId,
                sfppHash = sfppHash,
            ),
        )

        packetDao.applySFPPStatusByHash(sfppHash, status = MessageStatus.SFPP_ROUTING, rxTime = 5L)

        val packet = packetDao.findPacketBySfppHash(sfppHash)
        assertNotNull(packet)
        assertEquals(MessageStatus.SFPP_ROUTING, packet!!.data.status)

        val reaction = packetDao.findReactionBySfppHash(sfppHash)
        assertNotNull(reaction)
        assertEquals(MessageStatus.SFPP_ROUTING, reaction!!.status)
    }

    @Test
    fun applySFPPStatusByHashDoesNotDowngradeConfirmedToRouting() = runTest {
        seedMyNodeInfo()
        val packetId = 210
        packetDao.insert(
            textPacket(
                "contact",
                "msg",
                time = 1000,
                packetId = packetId,
                status = MessageStatus.SFPP_CONFIRMED,
                sfppHash = sfppHash,
            ),
        )

        packetDao.applySFPPStatusByHash(sfppHash, status = MessageStatus.SFPP_ROUTING, rxTime = 5L)

        val packet = packetDao.findPacketBySfppHash(sfppHash)
        assertNotNull(packet)
        assertEquals(MessageStatus.SFPP_CONFIRMED, packet!!.data.status, "confirmed packet must not be downgraded")
    }

    @Test
    fun applySFPPStatusByHashLeavesUnrelatedHashUntouched() = runTest {
        seedMyNodeInfo()
        val packetId = 220
        packetDao.insert(textPacket("contact", "msg", time = 1000, packetId = packetId, sfppHash = sfppHash))

        // Apply with a completely different hash prefix — should not touch the packet.
        packetDao.applySFPPStatusByHash(otherHash, status = MessageStatus.SFPP_ROUTING, rxTime = 5L)

        val packet = packetDao.findPacketBySfppHash(sfppHash)
        assertNotNull(packet)
        assertEquals(MessageStatus.UNKNOWN, packet!!.data.status, "unrelated hash should be untouched")
    }

    // ── deleteMessagesAtomic ─────────────────────────────────────────────────────

    @Test
    fun deleteMessagesAtomicDeletesPacketsAndReactions() = runTest {
        seedMyNodeInfo()
        val packetId = 300
        packetDao.insert(textPacket("contact", "msg", time = 100, packetId = packetId))
        packetDao.insert(reaction(replyId = packetId, userId = "!u", emoji = "👍", timestamp = 1, packetId = packetId))

        val uuids = packetDao.getMessagesFrom("contact").first().map { it.packet.uuid }
        assertTrue(uuids.isNotEmpty())

        packetDao.deleteMessagesAtomic(uuids)

        assertEquals(0, packetDao.getMessageCount("contact"))
        assertTrue(packetDao.findReactionsWithId(packetId).isEmpty(), "reactions should be deleted with their packets")
    }

    @Test
    fun deleteMessagesAtomicEmptyListIsNoOp() = runTest {
        seedMyNodeInfo()
        val contact = "empty-test"
        packetDao.insert(textPacket(contact, "msg", time = 100, packetId = 310))
        val before = packetDao.getMessageCount(contact)

        packetDao.deleteMessagesAtomic(emptyList())

        assertEquals(before, packetDao.getMessageCount(contact))
    }

    @Test
    fun deleteMessagesAtomicCrossesChunkBoundaryAndDeletesAll() = runTest {
        seedMyNodeInfo()
        // 1200 UUIDs exceeds SQLITE_MAX_BIND_PARAMETERS (999), forcing at least 2 chunks.
        val total = 1200
        val contact = "chunk-boundary"
        for (i in 1..total) {
            packetDao.insert(textPacket(contact, "msg-$i", time = 100L + i, packetId = i))
        }
        // Also insert a packet that should survive the delete.
        val survivorContact = "survivor"
        packetDao.insert(textPacket(survivorContact, "keep", time = 999_999, packetId = 9_999))

        val insertedUuids = packetDao.getAllPacketsSnapshot().filter { it.contact_key == contact }.map { it.uuid }
        assertEquals(total, insertedUuids.size, "all packets should have been inserted")

        packetDao.deleteMessagesAtomic(insertedUuids)

        assertEquals(0, packetDao.getMessageCount(contact), "all chunked packets deleted")
        assertEquals(1, packetDao.getMessageCount(survivorContact), "non-selected packet untouched")
    }
}
