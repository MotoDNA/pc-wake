package io.github.motodna.pcwake

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ShotTest {
    private fun shot(name: String, setup: (Store) -> Unit, h: Int = 2673, after: (MainActivity) -> Unit = {}) {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("pcwake", 0).edit().clear().commit()
        val s = Store(ctx)
        setup(s)
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        after(act)
        shadowOf(Looper.getMainLooper()).idle()
        val root = act.window.decorView
        val w = 1233
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File("/tmp/claude-0/shots"); dir.mkdirs()
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val demoPcs = listOf(Pc("사무실 PC", "0A:1B:2C:3D:4E:5F", "192.168.0.10"), Pc("집 데스크탑", "0A:1B:2C:3D:4E:60", "192.168.0.20"))

    @Test fun g1_choose() = shot("g1_choose", { it.mode = Mode.NONE })

    @Test fun g2_relayRunning() = shot("g2_relay", {
        it.mode = Mode.RELAY; it.pairing = Pairing.newRandom(); it.relayRunning = true
        RelayService.running = true; RelayService.lastContact = System.currentTimeMillis()
    })

    @Test fun g2b_relayFull() = shot("g2b_relay_full", {
        it.mode = Mode.RELAY; it.pairing = Pairing.newRandom(); it.relayRunning = true
        RelayService.running = true; RelayService.lastContact = System.currentTimeMillis()
    }, h = 5200)

    @Test fun g3_remoteUnpaired() = shot("g3_remote_unpaired", { it.mode = Mode.REMOTE })

    @Test fun g4_remotePaired() = shot("g4_remote_paired", {
        it.mode = Mode.REMOTE; it.pairing = Pairing.newRandom(); it.pcs = demoPcs
    })

    @Test fun g5_success() = shot("g5_success", {
        it.mode = Mode.REMOTE; it.pairing = Pairing.newRandom(); it.pcs = demoPcs
    }) { act ->
        val tone = Class.forName("io.github.motodna.pcwake.MainActivity\$Tone")
        val ok = tone.enumConstants!!.first { (it as Enum<*>).name == "OK" }
        val m = MainActivity::class.java.getDeclaredMethod("setPcState", Int::class.javaPrimitiveType, tone, String::class.java)
        m.isAccessible = true
        m.invoke(act, 0, ok, "켜짐 확인 · 방금 켜졌어요")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w533dp-h853dp-xxxhdpi")
class TabletShotTest {
    @Test fun relayTablet() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("pcwake", 0).edit().clear().commit()
        val s = Store(ctx); s.mode = Mode.RELAY; s.pairing = Pairing.newRandom(); s.relayRunning = true
        RelayService.running = true; RelayService.lastContact = System.currentTimeMillis()
        // 실제로 다 설정된 상태처럼: 충전 중 · 와이파이 · 절전 예외 · 알림 허용
        ctx.sendStickyBroadcast(android.content.Intent(android.content.Intent.ACTION_BATTERY_CHANGED).putExtra(android.os.BatteryManager.EXTRA_PLUGGED, android.os.BatteryManager.BATTERY_PLUGGED_AC))
        shadowOf(ctx.getSystemService(android.os.PowerManager::class.java)).setIgnoringBatteryOptimizations(ctx.packageName, true)
        shadowOf(ctx).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = act.window.decorView
        val w = 1600; val h = 2560
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        FileOutputStream(File("/tmp/claude-0/shots/t_relay.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
