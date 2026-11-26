package com.example.mapoverlay

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
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

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Плавающая кнопка карты.\n\n" +
                "1) Разрешите показ поверх других окон.\n" +
                "2) Запустите кнопку — откроется игра, а поверх неё появится круглая кнопка.\n" +
                "3) Нажали — открылась карта. Нажали ещё раз — вернулись в игру.\n" +
                "Кнопку можно перетаскивать пальцем."
            textSize = 16f
        })

        root.addView(button("1. Разрешить поверх других окон") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })

        root.addView(button("2. Запустить кнопку и игру") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала выдайте разрешение (шаг 1)", Toast.LENGTH_LONG).show()
            } else {
                startForegroundService(Intent(this, OverlayService::class.java))
                if (!launchGame()) {
                    Toast.makeText(
                        this,
                        "Кнопка включена, но игра не найдена — запустите её сами",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        root.addView(button("Выключить кнопку") {
            stopService(Intent(this, OverlayService::class.java))
        })

        setContentView(root)
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

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
