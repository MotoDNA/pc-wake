package io.github.motodna.pcwake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 탭을 재부팅하거나 앱이 업데이트되어도 중계기를 자동으로 다시 켭니다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = Store(context)
        if (s.mode == Mode.RELAY && s.relayRunning && s.pairing != null) {
            try { RelayService.start(context) } catch (_: Exception) {}
        }
    }
}
