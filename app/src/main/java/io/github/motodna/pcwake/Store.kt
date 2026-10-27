package io.github.motodna.pcwake

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 켜고 싶은 컴퓨터 한 대 */
data class Pc(val name: String, val mac: String, val ip: String)

/** 리모컨과 중계기를 이어주는 연결 정보 */
data class Pairing(val server: String, val topic: String, val key: String) {
    val replyTopic get() = topic + "_r"

    fun toCode(): String {
        val json = JSONObject().put("s", server).put("t", topic).put("k", key).toString()
        val b64 = Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return PREFIX + b64
    }

    companion object {
        const val PREFIX = "PCWAKE1-"
        const val DEFAULT_SERVER = "https://ntfy.sh"

        fun fromCode(raw: String): Pairing? = try {
            val start = raw.indexOf(PREFIX)
            if (start < 0) throw IllegalArgumentException()
            val body = raw.substring(start + PREFIX.length).trim().takeWhile { !it.isWhitespace() }
            val o = JSONObject(String(Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
            val p = Pairing(o.getString("s").trimEnd('/'), o.getString("t"), o.getString("k"))
            if (start < 0 || p.topic.length < 16 || p.key.length < 32) null else p
        } catch (e: Exception) {
            null
        }

        fun newRandom(server: String = DEFAULT_SERVER): Pairing {
            val rnd = SecureRandom()
            val abc = "abcdefghijkmnopqrstuvwxyz23456789"
            val topic = "pcw_" + (1..24).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
            val keyBytes = ByteArray(32).also { rnd.nextBytes(it) }
            val key = keyBytes.joinToString("") { "%02x".format(it) }
            return Pairing(server, topic, key)
        }
    }
}

object Mode {
    const val NONE = ""
    const val REMOTE = "remote"
    const val RELAY = "relay"
}

/** 앱 설정을 폰 안에 저장하는 곳 */
class Store(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("pcwake", Context.MODE_PRIVATE)

    var mode: String
        get() = sp.getString("mode", Mode.NONE) ?: Mode.NONE
        set(v) = sp.edit().putString("mode", v).apply()

    var pairing: Pairing?
        get() = sp.getString("pairing", null)?.let { Pairing.fromCode(it) }
        set(v) = sp.edit().putString("pairing", v?.toCode()).apply()

    var relayRunning: Boolean
        get() = sp.getBoolean("relayRunning", false)
        set(v) = sp.edit().putBoolean("relayRunning", v).apply()

    var pcs: List<Pc>
        get() {
            val arr = JSONArray(sp.getString("pcs", "[]"))
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Pc(o.optString("name"), o.optString("mac"), o.optString("ip"))
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("name", it.name).put("mac", it.mac).put("ip", it.ip)) }
            sp.edit().putString("pcs", arr.toString()).apply()
        }

    /** 중계기 활동 기록 (최근 30줄) */
    fun log(line: String) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        val old = sp.getString("log", "") ?: ""
        val lines = ("$time  $line\n$old").lines().filter { it.isNotBlank() }.take(30)
        sp.edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun readLog(): String = sp.getString("log", "") ?: ""
}
