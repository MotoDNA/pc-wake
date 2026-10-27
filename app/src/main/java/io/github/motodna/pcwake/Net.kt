package io.github.motodna.pcwake

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 리모컨 ↔ 중계기 메시지.
 * 무료 알림 서버(ntfy)를 우편함처럼 씁니다. 연결 코드 안의 '비밀 열쇠'로 서명해서
 * 코드를 모르는 사람이 보낸 가짜 명령은 중계기가 무시합니다.
 */
object Proto {
    private const val MAX_AGE_SEC = 300

    fun nonce(): String {
        val b = ByteArray(9).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun hmac(key: String, text: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun canonical(o: JSONObject): String =
        listOf("t", "mac", "ip", "ts", "n", "st", "info").joinToString("|") { o.optString(it) }

    /** 메시지 만들기 (서명 포함) */
    fun make(key: String, type: String, n: String, fields: Map<String, String> = emptyMap()): String {
        val o = JSONObject().put("t", type).put("ts", (System.currentTimeMillis() / 1000).toString()).put("n", n)
        fields.forEach { (k, v) -> o.put(k, v) }
        o.put("sig", hmac(key, canonical(o)))
        return o.toString()
    }

    /** 서명과 시간이 맞으면 내용을, 아니면 null */
    fun open(key: String, raw: String): JSONObject? = try {
        val o = JSONObject(raw)
        val expect = hmac(key, canonical(o))
        val ok = MessageDigest.isEqual(expect.toByteArray(), o.optString("sig").toByteArray())
        val age = System.currentTimeMillis() / 1000 - o.optString("ts").toLong()
        if (ok && age in -MAX_AGE_SEC..MAX_AGE_SEC) o else null
    } catch (e: Exception) {
        null
    }
}

/** ntfy 서버와 주고받기 */
object Ntfy {
    fun publish(server: String, topic: String, body: String) {
        val c = URL("$server/$topic").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val detail = try { c.errorStream?.bufferedReader()?.readText()?.take(200) } catch (_: Exception) { null }
                throw RuntimeException("서버 응답 $code ${detail ?: ""}")
            }
        } finally {
            c.disconnect()
        }
    }

    /** since(초 단위 시각) 이후에 온 메시지들의 본문 목록 */
    fun poll(server: String, topic: String, sinceSec: Long): List<String> {
        val c = URL("$server/$topic/json?poll=1&since=$sinceSec").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            val out = mutableListOf<String>()
            BufferedReader(InputStreamReader(c.inputStream)).useLines { lines ->
                lines.forEach { line -> parseMessage(line)?.let { out.add(it.second) } }
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    /** 한 줄(JSON)에서 (id, 본문) 꺼내기. 메시지가 아니면 null */
    fun parseMessage(line: String): Pair<String, String>? = try {
        val o = JSONObject(line)
        if (o.optString("event") == "message") o.optString("id") to o.optString("message") else null
    } catch (e: Exception) {
        null
    }

    /**
     * 정해진 시간 동안 연결을 열어두고 새 메시지를 기다립니다. (요청 횟수를 아끼기 위해 반복 조회 대신 사용)
     * onMessage가 true를 돌려주면 바로 멈춥니다. 멈췄으면 true, 시간이 다 됐으면 false.
     */
    fun listen(server: String, topic: String, sinceSec: Long, timeoutMs: Long, onMessage: (String) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var since = sinceSec.toString()
        while (System.currentTimeMillis() < deadline) {
            val c = openStream(server, topic, since)
            val watchdog = Thread {
                try {
                    Thread.sleep((deadline - System.currentTimeMillis()).coerceAtLeast(1))
                    c.disconnect()
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; start() }
            try {
                BufferedReader(InputStreamReader(c.inputStream)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        val m = parseMessage(line) ?: continue
                        since = m.first
                        if (onMessage(m.second)) return true
                    }
                }
            } catch (_: Exception) {
                if (System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                }
            } finally {
                watchdog.interrupt()
                c.disconnect()
            }
        }
        return false
    }

    /** 계속 열어두고 새 메시지를 기다리는 연결 */
    fun openStream(server: String, topic: String, since: String): HttpURLConnection {
        val c = URL("$server/$topic/json?since=$since").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 120000 // 서버가 약 45초마다 '살아있음' 신호를 보내므로 넉넉히
        return c
    }
}
