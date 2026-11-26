package com.example.mapoverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.mapoverlay.STOP"

        // Официальная интерактивная карта HoYoLAB (lang=ru-ru — русский язык).
        // Если захотите другую карту — поменяйте адрес здесь.
        const val MAP_URL = "https://act.hoyolab.com/ys/app/interactive-map/index.html?lang=ru-ru"

        private const val CHANNEL_ID = "overlay_button"
        private const val NOTIF_ID = 1
        const val PREFS = "settings"
        const val KEY_ALPHA = "map_alpha_pct"
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var button: ImageView? = null
    private var buttonParams: WindowManager.LayoutParams? = null

    private var webView: WebView? = null
    private var mapLayout: MapLayout? = null
    private var popupView: WebView? = null
    private var mapParams: WindowManager.LayoutParams? = null

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
        addButton()
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
        // Поворот экрана: берём положение кнопки, сохранённое для этой ориентации
        val b = button
        val lp = buttonParams
        if (b != null && lp != null) {
            applySavedPosition(lp, newConfig)
            runCatching { wm.updateViewLayout(b, lp) }
        }
    }

    private fun applySavedPosition(lp: WindowManager.LayoutParams, cfg: Configuration) {
        val density = resources.displayMetrics.density
        val maxX = (cfg.screenWidthDp * density).toInt() - dp(52)
        val maxY = (cfg.screenHeightDp * density).toInt() - dp(52)
        val suffix = if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) "_land" else "_port"
        lp.x = prefs.getInt("btn_x$suffix", dp(8)).coerceIn(0, maxX.coerceAtLeast(0))
        lp.y = prefs.getInt("btn_y$suffix", dp(120)).coerceIn(0, maxY.coerceAtLeast(0))
    }

    private fun saveButtonPosition(lp: WindowManager.LayoutParams) {
        val suffix = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "_land" else "_port"
        prefs.edit().putInt("btn_x$suffix", lp.x).putInt("btn_y$suffix", lp.y).apply()
    }

    private fun mapAlpha(): Float = (prefs.getInt(KEY_ALPHA, 100) / 100f).coerceIn(0.3f, 1f)

    /** Долгое нажатие на кнопку: 100% -> 70% -> 40% -> 100% */
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

    override fun onDestroy() {
        hideMap()
        webView?.destroy()
        webView = null
        button?.let { runCatching { wm.removeView(it) } }
        button = null
        super.onDestroy()
    }

    // ---------- Уведомление (нужно, чтобы система не убивала сервис) ----------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Плавающая кнопка", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("Кнопка карты активна")
            .setContentText("Нажмите на плавающую кнопку поверх игры")
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

    // ---------- Плавающая кнопка ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton() {
        val size = dp(52)
        val btn = ImageView(this).apply {
            setImageResource(R.drawable.ic_map)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(210, 30, 30, 45))
                setStroke(dp(2), Color.argb(200, 255, 255, 255))
            }
            alpha = 0.9f
        }
        val lp = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        applySavedPosition(lp, resources.configuration)

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val longPress = Runnable {
            longFired = true
            btn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            cycleOpacity()
        }

        btn.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
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
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(btn, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (moved) {
                        saveButtonPosition(lp)
                    } else if (!longFired) {
                        // post — чтобы не менять окна прямо посреди обработки касания
                        handler.post { toggleMap() }
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

        wm.addView(btn, lp)
        button = btn
        buttonParams = lp
    }

    /** Убираем и снова добавляем кнопку, чтобы она оказалась выше окна с картой. */
    private fun bringButtonToFront() {
        val b = button ?: return
        val lp = buttonParams ?: return
        runCatching { wm.removeView(b) }
        wm.addView(b, lp)
    }

    // ---------- Карта ----------

    private fun toggleMap() {
        if (mapLayout == null) showMap() else hideMap()
    }

    private fun showMap() {
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

        bringButtonToFront()
        button?.setImageResource(R.drawable.ic_close)
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
        button?.setImageResource(R.drawable.ic_map)
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

    /** Окно с картой; кнопка «Назад» сначала ходит по истории страницы, потом закрывает карту. */
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
