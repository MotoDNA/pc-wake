package io.github.motodna.pcwake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundTripTest {
    private val server = "https://ntfy.sh"

    @Test fun macNormalize() {
        assertEquals("3C:7C:3F:12:AB:CD", Wol.normalizeMac("3c-7c-3f-12-ab-cd"))
        assertEquals("3C:7C:3F:12:AB:CD", Wol.normalizeMac("3C7C3F12ABCD"))
        assertNull(Wol.normalizeMac("3C-7C-3F"))
        assertTrue(Wol.isValidIp("192.168.0.10"))
        assertTrue(!Wol.isValidIp("192.168.0.300"))
    }

    @Test fun signature() {
        val key = "a".repeat(64)
        val msg = Proto.make(key, "wake", "n1", mapOf("mac" to "AA:BB:CC:DD:EE:FF", "ip" to ""))
        assertNotNull(Proto.open(key, msg))
        assertNull(Proto.open("b".repeat(64), msg))               // 다른 열쇠 → 거부
        assertNull(Proto.open(key, msg.replace("AA:BB", "11:22"))) // 변조 → 거부
    }

    @Test fun relayRoundTrip() {
        val key = "c".repeat(64)
        val topic = "pcw_test_" + System.nanoTime()
        val reply = topic + "_r"
        val since = System.currentTimeMillis() / 1000 - 5
        // 가짜 중계기: 신호를 받으면 sent_checking → on 순서로 대답
        val relay = Thread {
            Ntfy.listen(server, topic, since, 30000) { raw ->
                val o = Proto.open(key, raw) ?: return@listen false
                val n = o.optString("n")
                Ntfy.publish(server, reply, Proto.make(key, "reply", n, mapOf("st" to "sent_checking")))
                Thread.sleep(1500)
                Ntfy.publish(server, reply, Proto.make(key, "reply", n, mapOf("st" to "on")))
                true
            }
        }
        relay.start()
        Thread.sleep(2000)
        val n = Proto.nonce()
        Ntfy.publish(server, topic, Proto.make(key, "wake", n, mapOf("mac" to "AA:BB:CC:DD:EE:FF", "ip" to "192.168.0.10")))
        val seen = mutableListOf<String>()
        val done = Ntfy.listen(server, reply, since, 25000) { raw ->
            val o = Proto.open(key, raw)
            if (o != null && o.optString("n") == n) seen.add(o.optString("st"))
            "on" in seen
        }
        relay.join()
        println("받은 순서: $seen")
        assertTrue(done)
        assertEquals(listOf("sent_checking", "on"), seen)
    }
}
