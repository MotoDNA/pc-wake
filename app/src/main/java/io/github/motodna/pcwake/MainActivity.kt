package io.github.motodna.pcwake

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {

    /** 화면 색상 (밝은 모드 / 어두운 모드) */
    private class Palette(dark: Boolean) {
        val bg = c(dark, "#F4F5F2", "#0F1211")
        val surface = c(dark, "#FFFFFF", "#181C1A")
        val line = c(dark, "#E3E6E2", "#2A302D")
        val ink = c(dark, "#151917", "#ECEFED")
        val muted = c(dark, "#6B736F", "#9AA39E")
        val faint = c(dark, "#9BA29E", "#6E7772")
        val accent = c(dark, "#1E6B52", "#5FCB9B")
        val accentSoft = c(dark, "#E2EFE8", "#1C3128")
        val onAccent = c(dark, "#FFFFFF", "#0A1D15")
        val warn = c(dark, "#9A6200", "#E3B04B")
        val warnSoft = c(dark, "#FBF0DC", "#33291A")
        val danger = c(dark, "#B3261E", "#F2857C")
        val dangerSoft = c(dark, "#FBE6E4", "#3A1F1D")
        val press = c(dark, "#14000000", "#22FFFFFF")

        companion object {
            fun c(dark: Boolean, l: String, d: String) = Color.parseColor(if (dark) d else l)
        }
    }

    private enum class Tone { IDLE, BUSY, OK, WARN, ERR }

    private class PcViews(val status: TextView, val button: FrameLayout, val icon: ImageView)

    private lateinit var store: Store
    private lateinit var p: Palette
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private val pcViews = mutableMapOf<Int, PcViews>()
    private val anims = mutableMapOf<Any, ValueAnimator>()
    private var pillDot: View? = null
    private var pillText: TextView? = null
    private var heroRing: View? = null
    private var heroCircle: FrameLayout? = null
    private var heroIcon: ImageView? = null
    private var heroTitle: TextView? = null
    private var heroSub: TextView? = null
    private var checklistBox: LinearLayout? = null
    private var logView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        p = Palette(dark)
        scroll = ScrollView(this).apply { setBackgroundColor(p.bg); isFillViewport = true; isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(40))
        }
        scroll.addView(content)
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 30) {
            val light = if (dark) 0 else
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                light,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            if (!dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!busy) render()
        ui.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresher)
    }

    override fun onDestroy() {
        anims.values.forEach { it.cancel() }
        super.onDestroy()
    }

    private val refresher = object : Runnable {
        override fun run() {
            if (store.mode == Mode.RELAY) {
                updateHero()
                updateChecklist()
                logView?.text = store.readLog().ifBlank { "아직 기록이 없습니다." }
            }
            ui.postDelayed(this, 3000)
        }
    }

    // ───────────────────────── 화면 그리기 ─────────────────────────

    private fun render() {
        anims.values.forEach { it.cancel() }
        anims.clear()
        pcViews.clear()
        pillDot = null; pillText = null
        heroRing = null; heroCircle = null; heroIcon = null; heroTitle = null; heroSub = null
        checklistBox = null; logView = null
        content.removeAllViews()
        when (store.mode) {
            Mode.REMOTE -> renderRemote()
            Mode.RELAY -> renderRelay()
            else -> renderChooseMode()
        }
    }

    private fun topBar(title: String, showMenu: Boolean = true) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        bar.addView(text("PC 원격켜기", 13f, p.muted, weight = W.MEDIUM).apply {
            letterSpacing = 0.02f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (showMenu) {
            val more = iconButton(R.drawable.ic_more, p.muted)
            more.setOnClickListener { showMenu(more) }
            bar.addView(more)
        } else {
            bar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) })
        }
        content.addView(bar)
        content.addView(text(title, 30f, p.ink, weight = W.BOLD).apply {
            letterSpacing = -0.01f
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        if (store.mode == Mode.REMOTE) {
            menu.menu.add(0, 1, 0, "중계기 연결 관리")
        }
        menu.menu.add(0, 2, 1, "이 기기의 역할 바꾸기")
        menu.menu.add(0, 3, 2, "앱 정보")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> askPairing()
                2 -> changeMode()
                3 -> showAbout()
            }
            true
        }
        menu.show()
    }

    // ───────── 첫 화면: 역할 고르기 ─────────

    private fun renderChooseMode() {
        topBar("시작하기", showMenu = false)
        content.addView(text("이 기기를 어떻게 쓸지 골라주세요.", 16f, p.muted).apply { setPadding(0, 0, 0, dp(20)) })
        content.addView(modeTile(
            R.drawable.ic_phone, "리모컨",
            "늘 들고 다니는 휴대폰에 설정하세요.\n어디서든 버튼 하나로 PC를 켭니다."
        ) { store.mode = Mode.REMOTE; render() })
        content.addView(spacer(12))
        content.addView(modeTile(
            R.drawable.ic_relay, "중계기",
            "PC 옆에 둘 남는 탭·폰에 설정하세요.\n충전기에 꽂아 두면 신호를 PC에 전해줘요."
        ) {
            store.mode = Mode.RELAY
            if (store.pairing == null) store.pairing = Pairing.newRandom()
            render()
        })
        content.addView(text("같은 와이파이 안에서만 쓸 거라면 리모컨만 있어도 됩니다.", 13f, p.faint).apply {
            setPadding(dp(4), dp(20), dp(4), 0)
        })
    }

    private fun modeTile(icon: Int, title: String, desc: String, onClick: () -> Unit): View {
        val tile = card(pad = 20)
        tile.orientation = LinearLayout.HORIZONTAL
        tile.gravity = Gravity.CENTER_VERTICAL
        tile.addView(circleIcon(icon, p.accent, p.accentSoft, 52))
        tile.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(title, 19f, p.ink, weight = W.BOLD))
            addView(text(desc, 14f, p.muted).apply { setPadding(0, dp(4), 0, 0) })
        })
        makeClickable(tile, dp(22).toFloat(), onClick)
        return tile
    }

    // ───────── 리모컨 화면 ─────────

    private fun renderRemote() {
        topBar("내 컴퓨터")
        val pair = store.pairing

        if (pair != null) {
            // 중계기 상태 줄
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(14), dp(12))
                background = rounded(p.surface, 999f, p.line)
            }
            pillDot = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.accent) }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
            }
            pill.addView(pillDot)
            pillText = text("중계기 연결됨 · 밖에서도 켤 수 있어요", 14f, p.ink, weight = W.MEDIUM).apply {
                setPadding(dp(10), 0, dp(8), 0)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            pill.addView(pillText)
            pill.addView(text("확인", 14f, p.accent, weight = W.MEDIUM))
            makeClickable(pill, 999f) { pingRelay() }
            content.addView(pill)
            content.addView(spacer(16))
        } else {
            val call = card(pad = 18)
            call.background = rounded(p.accentSoft, dp(22).toFloat())
            call.elevation = 0f
            call.addView(text("밖에서도 켜려면", 16f, p.ink, weight = W.BOLD))
            call.addView(text("지금은 같은 와이파이 안에서만 켤 수 있어요.\n중계기 화면의 QR을 찍으면 어디서든 켤 수 있습니다.", 14f, p.muted).apply {
                setPadding(0, dp(4), 0, dp(14))
            })
            call.addView(row(
                primaryButton("QR 스캔", R.drawable.ic_qr) { scanQr() },
                secondaryButton("코드 붙여넣기", onSurface = true) { pasteCode() }
            ))
            content.addView(call)
            content.addView(spacer(16))
        }

        val pcs = store.pcs
        if (pcs.isEmpty()) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), dp(28), dp(16), dp(20))
                addView(circleIcon(R.drawable.ic_power, p.faint, p.surface, 64).apply { (background as GradientDrawable).setStroke(dp(1), p.line) })
                addView(text("아직 등록된 컴퓨터가 없어요", 17f, p.ink, weight = W.BOLD).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(6)) })
                addView(text("켤 컴퓨터에서 '준비 도우미'를 실행한 뒤\n화면의 QR을 찍어 추가하세요.", 14f, p.muted).apply { gravity = Gravity.CENTER })
            })
        }
        pcs.forEachIndexed { idx, pc -> content.addView(pcCard(idx, pc)); content.addView(spacer(12)) }

        // 추가 버튼 (점선 테두리)
        val add = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(60)
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1).coerceAtLeast(2), p.faint, dp(6).toFloat(), dp(5).toFloat())
            }
            addView(icon(R.drawable.ic_plus, p.muted, 20))
            addView(text("컴퓨터 추가", 15f, p.muted, weight = W.MEDIUM).apply { setPadding(dp(8), 0, 0, 0) })
        }
        makeClickable(add, dp(22).toFloat()) { addPcChooser() }
        content.addView(add)

        if (pcs.isNotEmpty()) {
            content.addView(text("카드를 누르면 이름·주소를 고칠 수 있어요.", 12f, p.faint).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0)
            })
        }
    }

    private fun pcCard(index: Int, pc: Pc): View {
        val c = card(pad = 20)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(pc.name, 19f, p.ink, weight = W.BOLD).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(text(if (pc.ip.isNotBlank()) pc.ip else pc.mac, 13f, p.faint).apply {
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                letterSpacing = 0.03f
                setPadding(0, dp(3), 0, 0)
            })
        }
        top.addView(info)

        val btn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.accentSoft) }
            elevation = 0f
        }
        val ic = icon(R.drawable.ic_power, p.accent, 28).apply {
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        }
        btn.addView(ic)
        btn.foreground = RippleDrawable(ColorStateList.valueOf(p.press), null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        btn.isClickable = true
        btn.contentDescription = "${pc.name} 전원 켜기"
        btn.setOnClickListener {
            it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }.start()
            wake(index, pc)
        }
        top.addView(btn)
        c.addView(top)

        val status = text("", 14f, p.muted).apply {
            visibility = View.GONE
            setPadding(0, dp(14), 0, 0)
            setLineSpacing(0f, 1.25f)
        }
        c.addView(status)
        pcViews[index] = PcViews(status, btn, ic)

        info.setOnClickListener { pcActions(index) }
        info.background = null
        return c
    }

    private fun setPcState(index: Int, tone: Tone, msg: String) = ui.post {
        val v = pcViews[index] ?: return@post
        v.status.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
        v.status.text = msg
        val bg = v.button.background as GradientDrawable
        anims.remove(v)?.cancel()
        v.button.alpha = 1f
        when (tone) {
            Tone.IDLE -> { bg.setColor(p.accentSoft); v.icon.imageTintList = tint(p.accent); v.status.setTextColor(p.muted) }
            Tone.BUSY -> {
                bg.setColor(p.accentSoft); v.icon.imageTintList = tint(p.accent); v.status.setTextColor(p.muted)
                anims[v] = ValueAnimator.ofFloat(1f, 0.45f).apply {
                    duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { a -> v.button.alpha = a.animatedValue as Float }
                    start()
                }
            }
            Tone.OK -> { bg.setColor(p.accent); v.icon.imageTintList = tint(p.onAccent); v.status.setTextColor(p.accent) }
            Tone.WARN -> { bg.setColor(p.warnSoft); v.icon.imageTintList = tint(p.warn); v.status.setTextColor(p.warn) }
            Tone.ERR -> { bg.setColor(p.dangerSoft); v.icon.imageTintList = tint(p.danger); v.status.setTextColor(p.danger) }
        }
    }

    private fun setPill(tone: Tone, msg: String) = ui.post {
        val t = pillText ?: return@post
        val dot = pillDot ?: return@post
        t.text = msg
        val color = when (tone) {
            Tone.OK, Tone.IDLE -> p.accent
            Tone.BUSY -> p.faint
            Tone.WARN -> p.warn
            Tone.ERR -> p.danger
        }
        (dot.background as GradientDrawable).setColor(color)
        anims.remove(dot)?.cancel(); dot.alpha = 1f
        if (tone == Tone.BUSY) {
            anims[dot] = ValueAnimator.ofFloat(1f, 0.2f).apply {
                duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { a -> dot.alpha = a.animatedValue as Float }
                start()
            }
        }
    }

    // ───────── 중계기 화면 ─────────

    private fun renderRelay() {
        topBar("중계기")
        if (store.pairing == null) store.pairing = Pairing.newRandom()
        val pair = store.pairing!!

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // 상태 (큰 원)
        val hero = card(pad = 24).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val stack = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dp(112), dp(112)) }
        heroRing = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.accentSoft) }
            layoutParams = FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER)
        }
        heroCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.accent) }
            layoutParams = FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER)
        }
        heroIcon = icon(R.drawable.ic_relay, p.onAccent, 34).apply {
            layoutParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
        }
        heroCircle!!.addView(heroIcon)
        stack.addView(heroRing); stack.addView(heroCircle)
        hero.addView(stack)
        heroTitle = text("", 21f, p.ink, weight = W.BOLD).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(4)) }
        heroSub = text("", 14f, p.muted).apply { gravity = Gravity.CENTER }
        hero.addView(heroTitle); hero.addView(heroSub)
        hero.addView(spacer(18))
        hero.addView(
            if (store.relayRunning) secondaryButton("중계기 멈추기") { RelayService.stop(this); render() }
            else primaryButton("중계기 시작", R.drawable.ic_power) { RelayService.start(this); ui.postDelayed({ render() }, 400) }
        )
        content.addView(hero)
        updateHero()
        content.addView(spacer(12))

        // 체크리스트
        val check = card(pad = 20)
        check.addView(sectionTitle("꺼지지 않게 준비하기"))
        checklistBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        check.addView(checklistBox)
        content.addView(check)
        updateChecklist()
        content.addView(spacer(12))

        // 리모컨 연결 QR
        val pairCard = card(pad = 20).apply { gravity = Gravity.CENTER_HORIZONTAL }
        pairCard.addView(sectionTitle("리모컨 연결하기").apply { gravity = Gravity.START; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
        pairCard.addView(text("리모컨 폰 앱에서 'QR 스캔'을 누르고 비추세요.\n가족 폰 여러 대도 같은 QR로 연결할 수 있어요.", 14f, p.muted).apply {
            setPadding(0, 0, 0, dp(18))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        qrBitmap(pair.toCode())?.let { bmp ->
            pairCard.addView(FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { gravity = Gravity.CENTER_HORIZONTAL }
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(Color.WHITE, dp(18).toFloat(), p.line)
                addView(ImageView(context).apply {
                    setImageBitmap(bmp)
                    layoutParams = FrameLayout.LayoutParams(dp(208), dp(208))
                })
            })
        }
        pairCard.addView(text("이 QR은 비밀번호와 같아요. 아는 사람만 PC를 켤 수 있습니다.", 12f, p.faint).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(16))
        })
        pairCard.addView(row(
            secondaryButton("코드 공유") { shareCode(pair) },
            secondaryButton("코드 복사") { copy(pair.toCode()); toast("복사했어요") }
        ))
        pairCard.addView(linkButton("새 코드 만들기") { regenerateCode() })
        content.addView(pairCard)
        content.addView(spacer(12))

        // 활동 기록 (접기/펼치기)
        val logCard = card(pad = 20)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(sectionTitle("활동 기록").apply {
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val arrow = text("펼치기", 13f, p.accent, weight = W.MEDIUM)
        head.addView(arrow)
        logCard.addView(head)
        logView = text(store.readLog().ifBlank { "아직 기록이 없습니다." }, 12f, p.muted).apply {
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.35f)
            setPadding(0, dp(12), 0, 0)
            visibility = View.GONE
        }
        logCard.addView(logView)
        makeClickable(logCard, dp(22).toFloat()) {
            val show = logView?.visibility != View.VISIBLE
            logView?.visibility = if (show) View.VISIBLE else View.GONE
            arrow.text = if (show) "접기" else "펼치기"
        }
        content.addView(logCard)
    }

    private fun updateHero() {
        val title = heroTitle ?: return
        val sub = heroSub ?: return
        val circle = heroCircle ?: return
        val ring = heroRing ?: return
        val icon = heroIcon ?: return
        val running = store.relayRunning && RelayService.running
        val fresh = System.currentTimeMillis() - RelayService.lastContact < 150000
        val (main, soft, onMain) = when {
            !store.relayRunning -> Triple(p.faint, p.line, p.surface)
            running && fresh -> Triple(p.accent, p.accentSoft, p.onAccent)
            else -> Triple(p.warn, p.warnSoft, p.surface)
        }
        when {
            !store.relayRunning -> { title.text = "멈춰 있어요"; sub.text = "아래 버튼을 눌러 중계기를 시작하세요." }
            running && fresh -> { title.text = "신호 대기 중"; sub.text = "인터넷 연결됨 · 이 기기를 켜 두세요" }
            running -> { title.text = "연결하는 중…"; sub.text = "인터넷에 연결하고 있어요" }
            else -> { title.text = "다시 시작하는 중…"; sub.text = "잠시만 기다려 주세요" }
        }
        (circle.background as GradientDrawable).setColor(main)
        (ring.background as GradientDrawable).setColor(soft)
        icon.imageTintList = tint(onMain)
        val pulse = store.relayRunning && running && fresh
        if (pulse && anims[ring] == null) {
            anims[ring] = ValueAnimator.ofFloat(0.82f, 1f).apply {
                duration = 1400; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { a ->
                    val s = a.animatedValue as Float
                    ring.scaleX = s; ring.scaleY = s; ring.alpha = 1.6f - s
                }
                start()
            }
        } else if (!pulse) {
            anims.remove(ring)?.cancel()
            ring.scaleX = 1f; ring.scaleY = 1f; ring.alpha = 1f
        }
    }

    private fun updateChecklist() {
        val box = checklistBox ?: return
        box.removeAllViews()
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cm = getSystemService(ConnectivityManager::class.java)
        val wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val battOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val notiOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        box.addView(checkRow(charging, "충전기 연결", if (charging) "연결됨" else "충전기에 꽂아 두세요", null))
        box.addView(checkRow(wifi, "와이파이 연결", if (wifi) "PC와 같은 공유기인지 확인하세요" else "PC와 같은 공유기 와이파이에 연결하세요", null))
        box.addView(checkRow(battOk, "배터리 절전 예외", if (battOk) "설정됨" else "앱이 잠들지 않게 해요", if (battOk) null else ("설정" to { askBatteryExemption() })))
        box.addView(checkRow(notiOk, "알림 허용", if (notiOk) "허용됨" else "실행 중 표시를 위해 필요해요",
            if (notiOk) null else ("허용" to {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })))
        box.addView(text("삼성 기기: 설정 › 배터리 › 백그라운드 사용 제한 › '절전 예외 앱'에도 추가해 주세요.", 12f, p.faint).apply {
            setPadding(0, dp(10), 0, 0)
        })
    }

    private fun checkRow(ok: Boolean, title: String, desc: String, action: Pair<String, () -> Unit>?): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }
        val badge = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (ok) setColor(p.accent) else { setColor(Color.TRANSPARENT); setStroke(dp(2), p.line) }
            }
            if (ok) addView(icon(R.drawable.ic_check, p.onAccent, 16).apply {
                layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
            })
        }
        r.addView(badge)
        r.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(title, 15f, p.ink, weight = W.MEDIUM))
            addView(text(desc, 13f, p.muted).apply { setPadding(0, dp(1), 0, 0) })
        })
        if (action != null) {
            r.addView(text(action.first, 14f, p.onAccent, weight = W.BOLD).apply {
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = rounded(p.accent, 999f)
                setOnClickListener { action.second() }
            })
        }
        return r
    }

    // ───────────────────────── 동작 ─────────────────────────

    private fun wake(index: Int, pc: Pc) {
        if (busy) { toast("이전 요청을 처리 중이에요"); return }
        busy = true
        setPcState(index, Tone.BUSY, "켜는 중…")
        Thread {
            try {
                // 1) 같은 와이파이 안이라면 직접 보내기 (밖이면 아무 일도 안 일어남)
                try { Wol.send(pc.mac, pc.ip) } catch (_: Exception) {}

                val p0 = store.pairing
                if (p0 == null) {
                    setPcState(index, Tone.OK, "같은 와이파이로 켜기 신호를 보냈어요.")
                    return@Thread
                }
                // 2) 중계기에게 부탁하기
                val n = Proto.nonce()
                val since = System.currentTimeMillis() / 1000 - 5
                try {
                    Ntfy.publish(p0.server, p0.topic, Proto.make(p0.key, "wake", n, mapOf("mac" to pc.mac, "ip" to pc.ip)))
                } catch (e: Exception) {
                    setPcState(index, Tone.ERR, "신호를 보내지 못했어요. 휴대폰 인터넷 연결을 확인하세요.")
                    return@Thread
                }
                setPcState(index, Tone.BUSY, "중계기에 신호를 보냈어요…")

                var acked = false
                var finished = false
                val onReply: (String) -> Boolean = handler@{ raw ->
                    val o = Proto.open(p0.key, raw) ?: return@handler false
                    if (o.optString("n") != n) return@handler false
                    when (o.optString("st")) {
                        "on" -> { setPcState(index, Tone.OK, "켜짐 확인 · 방금 켜졌어요"); finished = true }
                        "timeout" -> {
                            setPcState(index, Tone.WARN, "신호는 보냈지만 2분 동안 PC가 대답하지 않았어요.\n랜선 연결 · BIOS 설정 · IP 주소를 확인해 주세요.")
                            finished = true
                        }
                        "error" -> { setPcState(index, Tone.ERR, "중계기 오류: ${o.optString("info")}"); finished = true }
                        "sent" -> { setPcState(index, Tone.OK, "켜기 신호를 전달했어요.\nIP를 넣어 두면 켜졌는지도 확인해 드려요."); finished = true }
                        "sent_checking" -> {
                            if (!acked) setPcState(index, Tone.BUSY, "PC가 켜지는지 확인 중… (최대 2분)")
                            acked = true
                        }
                    }
                    finished || acked
                }
                // 1차: 30초 안에 중계기가 대답하는지
                Ntfy.listen(p0.server, p0.replyTopic, since, 30000, onReply)
                if (finished) return@Thread
                if (!acked) {
                    setPcState(index, Tone.WARN, "중계기가 대답하지 않아요.\n탭이 켜져 있고 '신호 대기 중'인지 확인해 주세요.")
                    return@Thread
                }
                // 2차: PC가 켜졌는지 결과 기다리기
                Ntfy.listen(p0.server, p0.replyTopic, since, 140000) { raw -> onReply(raw); finished }
                if (!finished) setPcState(index, Tone.WARN, "결과 확인 시간이 지났어요. 잠시 후 다시 확인해 보세요.")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun pingRelay() {
        val pair = store.pairing ?: return
        if (busy) { toast("이전 요청을 처리 중이에요"); return }
        busy = true
        setPill(Tone.BUSY, "중계기 확인 중…")
        Thread {
            try {
                val n = Proto.nonce()
                val since = System.currentTimeMillis() / 1000 - 5
                try {
                    Ntfy.publish(pair.server, pair.topic, Proto.make(pair.key, "ping", n))
                } catch (e: Exception) {
                    setPill(Tone.ERR, "보내지 못했어요 · 인터넷 연결 확인"); return@Thread
                }
                var model = ""
                val ok = Ntfy.listen(pair.server, pair.replyTopic, since, 25000) { raw ->
                    val r = Proto.open(pair.key, raw)
                    if (r != null && r.optString("n") == n && r.optString("st") == "pong") {
                        model = r.optString("info"); true
                    } else false
                }
                if (ok) setPill(Tone.OK, "중계기 정상 작동 중" + if (model.isNotBlank()) " · $model" else "")
                else setPill(Tone.WARN, "중계기가 대답하지 않아요 · 탭을 확인하세요")
            } finally {
                busy = false
            }
        }.start()
    }

    private fun scanQr() {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { code ->
                val raw = code.rawValue ?: ""
                if (raw.startsWith("PCWAKE-PC1|")) handlePcQr(raw) else applyCode(raw)
            }
            .addOnFailureListener { toast("스캐너를 열 수 없어요. '코드 붙여넣기'를 이용하세요.") }
    }

    private fun pasteCode() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val clipText = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        val input = field("PCWAKE1-로 시작하는 코드").apply {
            if (clipText.contains(Pairing.PREFIX)) setText(clipText)
            isSingleLine = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        AlertDialog.Builder(this)
            .setTitle("연결 코드 붙여넣기")
            .setMessage("중계기 화면에서 '코드 공유'로 받은 코드를 붙여넣으세요.")
            .setView(dialogBox(input))
            .setPositiveButton("연결") { _, _ -> applyCode(input.text.toString()) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun applyCode(raw: String) {
        val pair = Pairing.fromCode(raw)
        if (pair == null) { toast("올바른 연결 코드가 아니에요"); return }
        store.pairing = pair
        render()
        pingRelay()
    }

    private fun askPairing() {
        val items = if (store.pairing != null)
            arrayOf("연결 상태 확인", "QR 다시 스캔", "코드 붙여넣기", "연결 해제")
        else arrayOf("QR 스캔", "코드 붙여넣기")
        AlertDialog.Builder(this)
            .setTitle("중계기 연결")
            .setItems(items) { _, which ->
                when (items[which]) {
                    "연결 상태 확인" -> pingRelay()
                    "QR 다시 스캔", "QR 스캔" -> scanQr()
                    "코드 붙여넣기" -> pasteCode()
                    "연결 해제" -> { store.pairing = null; render() }
                }
            }.show()
    }

    private fun addPcChooser() {
        AlertDialog.Builder(this)
            .setTitle("컴퓨터 추가")
            .setItems(arrayOf("QR로 가져오기 (추천)", "직접 입력하기")) { _, which ->
                if (which == 0) scanPcQr() else editPc(-1)
            }.show()
    }

    /** PC 준비 도우미가 띄운 QR(PCWAKE-PC1|이름|MAC|IP)을 찍어서 입력칸을 채웁니다 */
    private fun scanPcQr() {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { code ->
                val raw = code.rawValue ?: ""
                when {
                    raw.startsWith("PCWAKE-PC1|") -> handlePcQr(raw)
                    raw.contains(Pairing.PREFIX) -> applyCode(raw) // 중계기 QR을 찍은 경우
                    else -> toast("PC 준비 도우미의 QR이 아니에요")
                }
            }
            .addOnFailureListener { toast("스캐너를 열 수 없어요. '직접 입력하기'를 이용하세요.") }
    }

    private fun handlePcQr(raw: String) {
        val parts = raw.trim().split("|")
        val mac = parts.getOrNull(2)?.let { Wol.normalizeMac(it) }
        if (mac == null) { toast("QR 안의 MAC 주소가 올바르지 않아요"); return }
        editPc(-1, Pc(parts.getOrNull(1)?.ifBlank { null } ?: "내 컴퓨터", mac, parts.getOrNull(3)?.trim() ?: ""))
    }

    private fun pcActions(index: Int) {
        val pc = store.pcs.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setTitle(pc.name)
            .setMessage("MAC  ${pc.mac}\nIP     ${pc.ip.ifBlank { "없음" }}")
            .setPositiveButton("수정") { _, _ -> editPc(index) }
            .setNegativeButton("닫기", null)
            .setNeutralButton("삭제") { _, _ -> deletePc(index) }
            .show()
    }

    private fun editPc(index: Int, preset: Pc? = null) {
        val old = store.pcs.getOrNull(index) ?: preset
        val name = field("이름 (예: 사무실 PC)").apply { setText(old?.name ?: "") }
        val mac = field("MAC 주소 (예: 3C-7C-3F-12-AB-CD)").apply {
            setText(old?.mac ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val ip = field("IP 주소 (선택, 예: 192.168.0.10)").apply { setText(old?.ip ?: "") }
        val box = dialogBox(
            label("이름"), name, spacer(10),
            label("MAC 주소"), mac, spacer(10),
            label("IP 주소 (선택)"), ip,
            text("IP를 넣으면 실제로 켜졌는지까지 확인해 드려요.", 12f, p.faint).apply { setPadding(0, dp(8), 0, 0) }
        )
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (index < 0) "컴퓨터 추가" else "컴퓨터 수정")
            .setView(box)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val m = Wol.normalizeMac(mac.text.toString())
                val ipText = ip.text.toString().trim()
                when {
                    m == null -> toast("MAC 주소를 확인해 주세요 (숫자·영문 12자리)")
                    ipText.isNotEmpty() && !Wol.isValidIp(ipText) -> toast("IP 주소 형식을 확인해 주세요")
                    else -> {
                        val pc = Pc(name.text.toString().trim().ifBlank { "내 컴퓨터" }, m, ipText)
                        val list = store.pcs.toMutableList()
                        if (index in list.indices) list[index] = pc else list.add(pc)
                        store.pcs = list
                        dlg.dismiss(); render()
                    }
                }
            }
        }
        dlg.show()
    }

    private fun deletePc(index: Int) {
        val pc = store.pcs.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setMessage("'${pc.name}'을(를) 목록에서 지울까요?")
            .setPositiveButton("삭제") { _, _ ->
                store.pcs = store.pcs.toMutableList().also { it.removeAt(index) }; render()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun changeMode() {
        AlertDialog.Builder(this)
            .setTitle("역할 바꾸기")
            .setMessage("이 기기를 리모컨/중계기 중 다른 역할로 바꿀 수 있어요.\n등록한 컴퓨터와 연결 정보는 그대로 남아요.")
            .setPositiveButton("바꾸기") { _, _ ->
                if (store.mode == Mode.RELAY) RelayService.stop(this)
                store.mode = Mode.NONE; render()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun showAbout() {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        AlertDialog.Builder(this)
            .setTitle("PC 원격켜기")
            .setMessage("버전 $ver\n\n꺼진 컴퓨터를 휴대폰으로 켜는 무료 앱입니다.\n신호 전달: ntfy.sh (서명으로 보호)\n\ngithub.com/MotoDNA/pc-wake")
            .setPositiveButton("확인", null)
            .show()
    }

    private fun regenerateCode() {
        AlertDialog.Builder(this)
            .setTitle("새 코드 만들기")
            .setMessage("새 코드를 만들면 지금 연결된 리모컨들은 모두 QR을 다시 찍어야 해요.\n코드가 남에게 알려졌을 때 쓰세요.")
            .setPositiveButton("새로 만들기") { _, _ ->
                store.pairing = Pairing.newRandom(store.pairing?.server ?: Pairing.DEFAULT_SERVER)
                if (store.relayRunning) { RelayService.stop(this); RelayService.start(this) }
                render()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun askBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun shareCode(pair: Pairing) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, pair.toCode())
        }
        startActivity(Intent.createChooser(send, "연결 코드 보내기"))
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PC Wake", s))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun qrBitmap(textValue: String): Bitmap? = try {
        val size = 600
        val m = QRCodeWriter().encode(textValue, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.parseColor("#151917") else Color.WHITE }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
    }

    // ───────────────────────── 화면 부품 ─────────────────────────

    private enum class W { NORMAL, MEDIUM, BOLD }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tint(color: Int) = ColorStateList.valueOf(color)

    private fun text(s: String, size: Float, color: Int, weight: W = W.NORMAL) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = when (weight) {
            W.NORMAL -> Typeface.create("sans-serif", Typeface.NORMAL)
            W.MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            W.BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
        }
        setLineSpacing(0f, 1.18f)
        includeFontPadding = true
    }

    private fun sectionTitle(s: String) = text(s, 16f, p.ink, weight = W.BOLD).apply { setPadding(0, 0, 0, dp(6)) }

    private fun label(s: String) = text(s, 12f, p.muted, weight = W.MEDIUM).apply { setPadding(dp(2), 0, 0, dp(6)) }

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun card(pad: Int = 20) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        background = rounded(p.surface, dp(22).toFloat(), p.line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun makeClickable(v: View, radius: Float, onClick: () -> Unit) {
        v.foreground = RippleDrawable(ColorStateList.valueOf(p.press), null, rounded(Color.WHITE, radius))
        v.isClickable = true
        v.setOnClickListener { onClick() }
    }

    private fun icon(res: Int, color: Int, size: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = tint(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun circleIcon(res: Int, fg: Int, bgColor: Int, size: Int) = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bgColor) }
        addView(icon(res, fg, size / 2).apply {
            layoutParams = FrameLayout.LayoutParams(dp(size / 2), dp(size / 2), Gravity.CENTER)
        })
    }

    private fun iconButton(res: Int, color: Int) = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        addView(icon(res, color, 22).apply { layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER) })
        foreground = RippleDrawable(ColorStateList.valueOf(p.press), null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        isClickable = true
        contentDescription = "메뉴"
    }

    private fun primaryButton(label: String, iconRes: Int? = null, onClick: () -> Unit): View =
        pillButton(label, iconRes, p.accent, p.onAccent, null, onClick)

    private fun secondaryButton(label: String, onSurface: Boolean = false, onClick: () -> Unit): View =
        pillButton(label, null, if (onSurface) p.surface else p.accentSoft, p.accent, null, onClick)

    private fun pillButton(label: String, iconRes: Int?, bgColor: Int, fg: Int, stroke: Int?, onClick: () -> Unit): View {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(52)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(bgColor, 999f, stroke)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (iconRes != null) b.addView(icon(iconRes, fg, 18).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8)
        })
        b.addView(text(label, 15f, fg, weight = W.BOLD))
        makeClickable(b, 999f, onClick)
        return b
    }

    private fun linkButton(label: String, onClick: () -> Unit) = text(label, 13f, p.muted, weight = W.MEDIUM).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(14), dp(12), dp(2))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        views.forEachIndexed { i, v ->
            v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(10)
            }
            addView(v)
        }
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 15f
        setTextColor(p.ink)
        setHintTextColor(p.faint)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(p.bg, dp(12).toFloat(), p.line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dialogBox(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(4))
        views.forEach { addView(it) }
    }
}
