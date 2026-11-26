package com.example.mapoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val gamePackages = listOf(
        "com.miHoYo.GenshinImpact", // международная версия
        "com.miHoYo.Yuanshen"       // китайская версия
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val prefs = getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(label(
            "Genshin Map\n\n" +
                "1) Разрешите показ поверх других окон.\n" +
                "2) Запустите кнопки — откроется игра, а поверх неё появятся две круглые кнопки.\n" +
                "Первая — карта: нажали — карта открылась, нажали ещё раз — вернулись в игру.\n" +
                "Вторая (⏩) — пропуск диалогов. Кнопки можно перетаскивать.",
            16f, 0
        ))

        root.addView(button("1. Разрешить поверх других окон") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })

        root.addView(button("2. Запустить кнопки и игру") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала выдайте разрешение (шаг 1)", Toast.LENGTH_LONG).show()
            } else {
                startForegroundService(Intent(this, OverlayService::class.java))
                if (!launchGame()) {
                    Toast.makeText(
                        this,
                        "Кнопки включены, но игра не найдена — запустите её сами",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        root.addView(button("Выключить кнопки") {
            stopService(Intent(this, OverlayService::class.java))
        })

        // ----- Карта -----
        root.addView(label("Карта", 18f, 24))
        root.addView(slider(
            prefs, "Прозрачность карты", OverlayService.KEY_ALPHA, 30, 100, 100, "%"
        ))
        root.addView(label("В игре: долгое нажатие на кнопку карты переключает 100 / 70 / 40%.", 13f, 4))

        // ----- Пропуск диалогов -----
        root.addView(label("Пропуск диалогов", 18f, 24))
        root.addView(label(
            "Нужна служба доступа: она только нажимает на экран и ничего на нём не читает. " +
                "В игре нажмите ⏩ — пойдут касания, нажмите ещё раз — остановятся " +
                "(сам выключится через 5 минут). Долгое нажатие на ⏩ показывает красные точки: " +
                "где будут касания. Двигайте ползунки ниже, пока точки не встанут куда нужно.",
            13f, 4
        ))
        root.addView(button("Включить службу доступа") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(slider(prefs, "Пауза между касаниями", OverlayService.KEY_INTERVAL, 250, 1500, 400, " мс"))
        root.addView(slider(prefs, "Точка 1 (листать): слева направо", OverlayService.KEY_ADV_X, 0, 100, 50, "%"))
        root.addView(slider(prefs, "Точка 1 (листать): сверху вниз", OverlayService.KEY_ADV_Y, 0, 100, 30, "%"))

        root.addView(CheckBox(this).apply {
            text = "Выбирать первый вариант ответа (точка 2)"
            isChecked = prefs.getBoolean(OverlayService.KEY_OPT_ON, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(OverlayService.KEY_OPT_ON, checked).apply()
            }
        })
        root.addView(slider(prefs, "Точка 2 (ответ): слева направо", OverlayService.KEY_OPT_X, 0, 100, 72, "%"))
        root.addView(slider(prefs, "Точка 2 (ответ): сверху вниз", OverlayService.KEY_OPT_Y, 0, 100, 50, "%"))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun launchGame(): Boolean {
        for (pkg in gamePackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                startActivity(intent)
                return true
            }
        }
        return false
    }

    private fun label(text: String, size: Float, topDp: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dp(topDp), 0, 0)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    /** Ползунок со значением от min до max, сохраняется в настройки сразу. */
    private fun slider(
        prefs: SharedPreferences, title: String, key: String,
        min: Int, max: Int, def: Int, unit: String
    ): LinearLayout {
        val cur = prefs.getInt(key, def).coerceIn(min, max)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val text = TextView(this).apply {
            textSize = 14f
            this.text = "$title: $cur$unit"
        }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = cur - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    val v = value + min
                    text.text = "$title: $v$unit"
                    prefs.edit().putInt(key, v).apply()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        box.addView(text)
        box.addView(bar)
        return box
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
