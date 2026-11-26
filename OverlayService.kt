package com.example.mapoverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.mapoverlay.STOP"

        // Официальная интерактивная карта HoYoLAB (lang=ru-ru — русский язык).
        const val MAP_URL = "https://act.hoyolab.com/ys/app/interactive-map/index.html?lang=ru-ru"

        const val PREFS = "settings"
        const val KEY_ALPHA = "map_alpha_pct"
        const val KEY_INTERVAL = "skip_interval_ms"
        const val KEY_ADV_X = "skip_adv_x_pct"
        const val KEY_ADV_Y = "skip_adv_y_pct"
        const val KEY_OPT_ON = "skip_opt_on"
        const val KEY_OPT_X = "skip_opt_x_pct"
        const val KEY_OPT_Y = "skip_opt_y_pct"

        private const val CHANNEL_ID = "overlay_button"
        private const val NOTIF_ID = 1
        private const val MAX_SKIP_MS = 5 * 60 * 1000L // автовыключение пропуска через 5 минут
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private var mapBtn: FloatingButton? = null
    private var skipBtn: FloatingButton? = null

    private var webView: WebView? = null
    private var mapLayout: MapLayout? = null
    private var mapParams: WindowManager.LayoutParams? = null
    private var popupView: WebView? = null

    private var skipping = false
    private var skipTick = 0
    private var skipStartedAt = 0L
    private val markerViews = mutableListOf<TextView>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()

        mapBtn = FloatingButton(
            prefix = "btn", iconRes = R.drawable.ic_map, sizeDp = 52, defXdp = 8, defYdp = 120,
            onClick = { toggleMap() },
            onLongClick = { cycleOpacity() }
        ).also { it.add() }

        skipBtn = FloatingButton(
            prefix = "skipbtn", iconRes = R.drawable.ic_skip, sizeDp = 44, defXdp = 8, defYdp = 190,
            onClick = { toggleSkipping() },
            onLongClick = { showMarkers() }
        ).also { it.add() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Поворот экрана: берём положение кнопок, сохранённое для этой ориентации
        mapBtn?.reposition(newConfig)
        skipBtn?.reposition(newConfig)
    }

    override fun onDestroy() {
        stopSkipping()
        clearMarkers()
        hideMap()
        webView?.destroy()
        webView = null
        mapBtn?.remove()
        skipBtn?.remove()
        super.onDestroy()
    }

    // ---------- Уведомление (нужно, чтобы система не убивала сервис) ----------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Плавающие кнопки", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)

        val notification = builder
            .setSmallIcon(R.drawable.ic_map)
            .setContentTitle("Genshin Map")
            .setContentText("Плавающие кнопки активны")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_close), "Выключить", stopIntent
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------- Плавающая кнопка (общий код для двух кнопок) ----------

    @SuppressLint("ClickableViewAccessibility")
    private inner class FloatingButton(
        private val prefix: String,
        iconRes: Int,
        private val sizeDp: Int,
        private val defXdp: Int,
        private val defYdp: Int,
        private val onClick: () -> Unit,
        private val onLongClick: () -> Unit
    ) {
        val view = ImageView(this@OverlayService)
        val params: WindowManager.LayoutParams
        private val bg = GradientDrawable()
        private var attached = false

        init {
            val pad = dp(sizeDp / 4)
            view.setImageResource(iconRes)
            view.setPadding(pad, pad, pad, pad)
            bg.shape = GradientDrawable.OVAL
            bg.setColor(Color.argb(210, 30, 30, 45))
            bg.setStroke(dp(2), Color.argb(200, 255, 255, 255))
            view.background = bg
            view.alpha = 0.9f

            val size = dp(sizeDp)
            params = WindowManager.LayoutParams(
                size, size, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            applySavedPosition(resources.configuration)
            installTouch()
        }

        fun add() {
            if (attached) return
            runCatching {
                wm.addView(view, params)
                attached = true
            }
        }

        fun remove() {
            if (!attached) return
            runCatching { wm.removeView(view) }
            attached = false
        }

        /** Убираем и добавляем заново, чтобы кнопка оказалась выше остальных окон. */
        fun bringToFront() {
            remove()
            add()
        }

        fun setIcon(res: Int) {
            view.setImageResource(res)
        }

        fun setActive(active: Boolean) {
            bg.setColor(if (active) Color.argb(230, 30, 140, 70) else Color.argb(210, 30, 30, 45))
            view.invalidate()
        }

        fun reposition(cfg: Configuration) {
            applySavedPosition(cfg)
            if (attached) runCatching { wm.updateViewLayout(view, params) }
        }

        private fun suffix(cfg: Configuration) =
            if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) "_land" else "_port"

        private fun applySavedPosition(cfg: Configuration) {
            val density = resources.displayMetrics.density
            val size = dp(sizeDp)
            val maxX = (cfg.screenWidthDp * density).toInt() - size
            val maxY = (cfg.screenHeightDp * density).toInt() - size
            val sfx = suffix(cfg)
            params.x = prefs.getInt("${prefix}_x$sfx", dp(defXdp)).coerceIn(0, maxX.coerceAtLeast(0))
            params.y = prefs.getInt("${prefix}_y$sfx", dp(defYdp)).coerceIn(0, maxY.coerceAtLeast(0))
        }

        private fun savePosition() {
            val sfx = suffix(resources.configuration)
            prefs.edit()
                .putInt("${prefix}_x$sfx", params.x)
                .putInt("${prefix}_y$sfx", params.y)
                .apply()
        }

        private fun installTouch() {
            val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            var longFired = false
            val longPress = Runnable {
                longFired = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongClick()
            }

            view.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = params.x; startY = params.y
                        moved = false
                        longFired = false
                        handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                            moved = true
                            handler.removeCallbacks(longPress)
                        }
                        if (moved) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            wm.updateViewLayout(view, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPress)
                        if (moved) {
                            savePosition()
                        } else if (!longFired) {
                            // post — чтобы не менять окна прямо посреди обработки касания
                            handler.post { onClick() }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPress)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    // ---------- Карта ----------

    private fun mapAlpha(): Float = (prefs.getInt(KEY_ALPHA, 100) / 100f).coerceIn(0.3f, 1f)

    /** Долгое нажатие на кнопку карты: 100% -> 70% -> 40% -> 100% */
    private fun cycleOpacity() {
        val cur = prefs.getInt(KEY_ALPHA, 100)
        val next = when {
            cur > 85 -> 70
            cur > 55 -> 40
            else -> 100
        }
        prefs.edit().putInt(KEY_ALPHA, next).apply()
        val layout = mapLayout
        val lp = mapParams
        if (layout != null && lp != null) {
            lp.alpha = mapAlpha()
            runCatching { wm.updateViewLayout(layout, lp) }
        }
        Toast.makeText(this, "Прозрачность карты: $next%", Toast.LENGTH_SHORT).show()
    }

    private fun toggleMap() {
        if (mapLayout == null) showMap() else hideMap()
    }

    private fun showMap() {
        // Пока открыта карта, пропуск диалогов не нужен
        stopSkipping()
        skipBtn?.remove()

        val web = webView ?: createWebView().also { webView = it }
        (web.parent as? ViewGroup)?.removeView(web)

        val layout = MapLayout(this)
        layout.setBackgroundColor(Color.BLACK)
        layout.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            alpha = mapAlpha()
        }

        wm.addView(layout, lp)
        mapLayout = layout
        mapParams = lp

        web.onResume()
        web.resumeTimers()

        mapBtn?.bringToFront()
        mapBtn?.setIcon(R.drawable.ic_close)
    }

    private fun hideMap() {
        val layout = mapLayout ?: return
        closePopup()
        webView?.let {
            it.onPause()
            layout.removeView(it) // WebView остаётся в памяти — карта откроется там же, где закрыли
        }
        runCatching { wm.removeView(layout) }
        mapLayout = null
        mapParams = null
        mapBtn?.setIcon(R.drawable.ic_map)
        skipBtn?.add()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val themed = ContextThemeWrapper(applicationContext, android.R.style.Theme_DeviceDefault)
        return WebView(themed).apply {
            setBackgroundColor(Color.BLACK)
            configure(this)
            webChromeClient = object : WebChromeClient() {
                // Окно входа HoYoLAB открывается как всплывающее окно — показываем его поверх карты.
                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
                ): Boolean {
                    if (view == null || resultMsg == null) return false
                    closePopup()
                    val popup = WebView(view.context)
                    configure(popup)
                    popup.setBackgroundColor(Color.WHITE)
                    popup.webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            handler.post { closePopup() }
                        }
                    }
                    mapLayout?.addView(
                        popup,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    popupView = popup
                    val transport = resultMsg.obj as WebView.WebViewTransport
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    handler.post { closePopup() }
                }
            }
            loadUrl(MAP_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // Убираем пометку "wv", чтобы сайт считал браузер обычным Chrome
            userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                return scheme != "http" && scheme != "https"
            }
        }
    }

    private fun closePopup() {
        popupView?.let {
            mapLayout?.removeView(it)
            it.destroy()
        }
        popupView = null
    }

    /** Окно с картой; «Назад» закрывает окно входа, потом ходит по истории, потом закрывает карту. */
    private inner class MapLayout(ctx: Context) : FrameLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    val p = popupView
                    val w = webView
                    when {
                        p != null -> if (p.canGoBack()) p.goBack() else closePopup()
                        w != null && w.canGoBack() -> w.goBack()
                        else -> hideMap()
                    }
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    // ---------- Пропуск диалогов (касания через службу доступа) ----------

    private fun pct(key: String, def: Int): Int = prefs.getInt(key, def).coerceIn(0, 100)

    @Suppress("DEPRECATION")
    private fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private val skipRunnable = object : Runnable {
        override fun run() {
            if (!skipping) return
            val svc = TapService.instance
            if (svc == null || System.currentTimeMillis() - skipStartedAt > MAX_SKIP_MS) {
                val msg = if (svc == null) "Служба доступа выключена" else "Пропуск выключен (лимит 5 минут)"
                stopSkipping()
                Toast.makeText(this@OverlayService, msg, Toast.LENGTH_SHORT).show()
                return
            }
            val (w, h) = realSize()
            skipTick++
            // 1) обычное касание — листает реплики
            svc.tap(w * pct(KEY_ADV_X, 50) / 100f, h * pct(KEY_ADV_Y, 30) / 100f)
            // 2) раз в три цикла — касание по первому варианту ответа (если включено)
            if (prefs.getBoolean(KEY_OPT_ON, false) && skipTick % 3 == 0) {
                val ox = w * pct(KEY_OPT_X, 72) / 100f
                val oy = h * pct(KEY_OPT_Y, 50) / 100f
                handler.postDelayed({ if (skipping) TapService.instance?.tap(ox, oy) }, 150)
            }
            handler.postDelayed(this, prefs.getInt(KEY_INTERVAL, 400).toLong().coerceIn(250L, 1500L))
        }
    }

    private fun toggleSkipping() {
        if (skipping) stopSkipping() else startSkipping()
    }

    private fun startSkipping() {
        if (TapService.instance == null) {
            Toast.makeText(
                this,
                "Сначала включите службу «Genshin Map — пропуск диалогов» в настройках специальных возможностей",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        skipping = true
        skipTick = 0
        skipStartedAt = System.currentTimeMillis()
        skipBtn?.setActive(true)
        handler.post(skipRunnable)
    }

    private fun stopSkipping() {
        skipping = false
        handler.removeCallbacks(skipRunnable)
        skipBtn?.setActive(false)
    }

    /** Долгое нажатие на кнопку пропуска: показать красные точки, куда будут идти касания. */
    private fun showMarkers() {
        clearMarkers()
        val (w, h) = realSize()
        addMarker("1", w * pct(KEY_ADV_X, 50) / 100, h * pct(KEY_ADV_Y, 30) / 100)
        if (prefs.getBoolean(KEY_OPT_ON, false)) {
            addMarker("2", w * pct(KEY_OPT_X, 72) / 100, h * pct(KEY_OPT_Y, 50) / 100)
        }
        handler.postDelayed({ clearMarkers() }, 4000)
    }

    private fun addMarker(label: String, cx: Int, cy: Int) {
        val r = dp(16)
        val tv = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 220, 40, 40))
                setStroke(dp(2), Color.WHITE)
            }
        }
        val lp = WindowManager.LayoutParams(
            r * 2, r * 2, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cx - r
            y = cy - r
        }
        runCatching {
            wm.addView(tv, lp)
            markerViews.add(tv)
        }
    }

    private fun clearMarkers() {
        markerViews.forEach { runCatching { wm.removeView(it) } }
        markerViews.clear()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
