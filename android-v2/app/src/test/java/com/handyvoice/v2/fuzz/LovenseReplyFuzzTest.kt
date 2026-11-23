package com.handyvoice.v2.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.handyvoice.v2.api.LovenseProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Jazzer fuzz target for what a toy sends back over Bluetooth: notification
 * chunks through [LovenseProtocol.ReplyBuffer], then every reply parser. The
 * toy (or whatever answers at its address) controls these bytes, so they are
 * untrusted. The fuzzer picks the text and where it is cut into chunks.
 * Invariants:
 *
 * - cutting the stream differently never changes the replies that come out;
 * - every reply ends in its one and only `;`, and nothing is lost or invented:
 *   the replies are a prefix of the stream and the remainder holds no `;`;
 * - `clear` drops a half-received reply;
 * - no parser throws, a battery level is 0..100, and the link-loss setting
 *   reads as safe only for the literal `1:0` (stop on drop, no resume).
 *
 * Without `JAZZER_FUZZ=1` this only replays the seed corpus as a JUnit test.
 */
class LovenseReplyFuzzTest {

    @FuzzTest(maxDuration = "10m")
    fun chunkingNeverChangesTheReplies(data: FuzzedDataProvider) {
        val chunks = ArrayList<String>()
        while (data.remainingBytes() > 0 && chunks.size < 64) {
            chunks.add(data.consumeString(data.consumeInt(0, 24)))
        }
        val stream = chunks.joinToString("")

        val buffer = LovenseProtocol.ReplyBuffer()
        val fromChunks = chunks.flatMap { buffer.feed(it) }
        val fromWhole = LovenseProtocol.ReplyBuffer().feed(stream)
        assertEquals(fromWhole, fromChunks, "chunk boundaries changed the replies")

        for (reply in fromChunks) {
            assertTrue(reply.endsWith(";"), "reply without its terminator")
            assertEquals(reply.length - 1, reply.indexOf(';'), "reply holds more than one command")
        }
        val joined = fromChunks.joinToString("")
        assertTrue(stream.startsWith(joined), "replies are not a prefix of the stream")
        assertFalse(stream.substring(joined.length).contains(';'), "a complete reply was held back")

        buffer.clear()
        assertEquals(listOf("x;"), buffer.feed("x;"), "clear kept part of an old reply")

        for (reply in fromChunks) checkParsers(reply)
        checkParsers(stream)
    }

    private fun checkParsers(reply: String) {
        LovenseProtocol.isErrorReply(reply)

        LovenseProtocol.parseBattery(reply)?.let {
            assertTrue(it in 0..100, "battery $it out of range")
        }

        val body = reply.trim().removeSuffix(";").removePrefix("AutoSwith:")
        val auto = LovenseProtocol.parseAutoSwitch(reply)
        if (auto != null) {
            assertTrue(body in setOf("0:0", "0:1", "1:0", "1:1"), "accepted a malformed AutoSwith reply")
            assertEquals(body == "1:0", auto.isLinkLossSafe, "link-loss safety misread")
        }

        LovenseProtocol.parseDeviceType(reply)?.let {
            for (part in listOf(it.model, it.firmware, it.address)) {
                assertTrue(part.isNotEmpty(), "empty DeviceType field")
                assertFalse(part.contains(':'), "DeviceType field holds a separator")
            }
        }
    }
}
