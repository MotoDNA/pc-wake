package io.github.motodna.pcwake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection

/**
 * 중계기 모드: 탭/남는 폰에서 계속 켜져서
 * 리모컨이 보낸 '켜줘' 메시지를 기다렸다가 같은 네트워크의 PC에 매직 패킷을 보냅니다.
 */
class RelayService : Service() {

    companion object {
        private const val CH = "relay"
        private const val NOTI_ID = 7
        @Volatile var running = false
        @Volatile var lastContact: Long = 0

        fun start(ctx: Context) {
            Store(ctx).relayRunning = true
            val i = Intent(ctx, RelayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            Store(ctx).relayRunning = false
            ctx.stopService(Intent(ctx, RelayService::class.java))
        }
    }

    private lateinit var store: Store
    private var worker: Thread? = null
    @Volatile private var stopping = false
    @Volatile private var conn: HttpURLConnection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val seen = LinkedHashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "중계기 실행 중", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val noti = notification("연결 준비 중…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, noti)
        }
        if (worker?.isAlive != true) {
            stopping = false
            acquireLocks()
            worker = Thread({ loop() }, "relay").also { it.start() }
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        running = false
        try { conn?.disconnect() } catch (_: Exception) {}
        worker?.interrupt()
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        store.log("중계기 멈춤")
        super.onDestroy()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pcwake:relay").apply {
                setReferenceCounted(false); acquire()
            }
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pcwake:relay").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("PC 원격켜기 · 중계기 대기 중")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNoti(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, notification(text))
    }

    /** 끊기면 다시 붙는 기다림 반복 */
    private fun loop() {
        // 잠깐 끊겼던 사이에 온 신호도 받도록 2분 전부터 (오래된 신호는 서명 시간 검사로 걸러짐)
        var since = (System.currentTimeMillis() / 1000 - 120).toString()
        var backoff = 2000L
        store.log("중계기 시작")
        while (!stopping) {
            val p = store.pairing
            if (p == null) {
                updateNoti("연결 코드가 없습니다. 앱을 열어주세요.")
                sleep(10000); continue
            }
            try {
                val c = Ntfy.openStream(p.server, p.topic, since)
                conn = c
                BufferedReader(InputStreamReader(c.inputStream)).use { r ->
                    lastContact = System.currentTimeMillis()
                    updateNoti("인터넷 연결됨 · 신호를 기다리는 중")
                    backoff = 2000L
                    while (!stopping) {
                        val line = r.readLine() ?: break
                        lastContact = System.currentTimeMillis()
                        val msg = Ntfy.parseMessage(line) ?: continue
                        since = msg.first
                        handle(p, msg.second)
                    }
                }
            } catch (e: Exception) {
                if (stopping) break
                updateNoti("인터넷 연결 끊김 · 다시 연결 중…")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
            if (!stopping) {
                sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(60000L)
            }
        }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private fun reply(p: Pairing, n: String, state: String, info: String = "") {
        try {
            Ntfy.publish(p.server, p.replyTopic, Proto.make(p.key, "reply", n, mapOf("st" to state, "info" to info)))
        } catch (_: Exception) {
        }
    }

    private fun handle(p: Pairing, raw: String) {
        val o = Proto.open(p.key, raw)
        if (o == null) {
            store.log("확인되지 않은 신호를 무시함")
            return
        }
        val n = o.optString("n")
        synchronized(seen) {
            if (!seen.add(n)) return
            while (seen.size > 200) seen.remove(seen.first())
        }
        when (o.optString("t")) {
            "ping" -> {
                store.log("리모컨 연결 확인 요청 → 응답함")
                reply(p, n, "pong", Build.MODEL)
            }
            "wake" -> Thread { doWake(p, n, o.optString("mac"), o.optString("ip")) }.start()
        }
    }

    private fun doWake(p: Pairing, n: String, macRaw: String, ip: String) {
        val mac = Wol.normalizeMac(macRaw)
        if (mac == null) {
            reply(p, n, "error", "MAC 주소 형식이 잘못됨")
            return
        }
        val count = try { Wol.send(mac, ip) } catch (e: Exception) { 0 }
        store.log("켜기 신호 보냄 → $mac ${if (ip.isNotBlank()) "($ip)" else ""}")
        updateNoti("방금 켜기 신호 보냄: $mac")
        if (count == 0) {
            reply(p, n, "error", "와이파이 연결을 확인해 주세요")
            return
        }
        if (!Wol.isValidIp(ip)) {
            reply(p, n, "sent")
            return
        }
        reply(p, n, "sent_checking")
        // 최대 약 2분 동안 켜졌는지 확인. 중간에 한 번 더 신호를 보냄.
        val until = System.currentTimeMillis() + 120000
        var resent = false
        while (System.currentTimeMillis() < until && !stopping) {
            if (Wol.isUp(ip)) {
                store.log("PC 켜짐 확인 → $ip")
                reply(p, n, "on")
                return
            }
            if (!resent && System.currentTimeMillis() > until - 90000) {
                try { Wol.send(mac, ip) } catch (_: Exception) {}
                resent = true
            }
            sleep(4000)
        }
        store.log("PC 응답 없음 → $ip")
        reply(p, n, "timeout")
    }
}
