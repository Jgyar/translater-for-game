package com.example.gametranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : Activity() {

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val info = TextView(this).apply {
            text = "Экранный переводчик EN → RU.\nПосле запуска появится кружок «Aa». " +
                "Тап — перевести экран, тап по переводу — скрыть, " +
                "перетаскивание — переместить, долгое нажатие (1 сек) — выключить."
        }

        val b1 = Button(this).apply {
            text = "1. Разрешить показ поверх других приложений"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        val b2 = Button(this).apply {
            text = "2. Запустить плавающую кнопку"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Сначала шаг 1", Toast.LENGTH_LONG).show()
                } else {
                    val mpm = getSystemService(MediaProjectionManager::class.java)
                    startActivityForResult(mpm.createScreenCaptureIntent(), 100)
                }
            }
        }

        val b3 = Button(this).apply {
            text = "Остановить"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        }

        val input = EditText(this).apply { hint = "Текст по-русски → английский" }
        out = TextView(this).apply { textSize = 18f }
        val b4 = Button(this).apply {
            text = "RU → EN"
            setOnClickListener { translateRuEn(input.text.toString()) }
        }

        root.addView(info)
        root.addView(b1)
        root.addView(b2)
        root.addView(b3)
        root.addView(input)
        root.addView(b4)
        root.addView(out)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }

    private fun translateRuEn(text: String) {
        if (text.isBlank()) return
        out.text = "Перевожу…"
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.RUSSIAN)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
        t.downloadModelIfNeeded()
            .addOnSuccessListener {
                t.translate(text)
                    .addOnSuccessListener { out.text = it }
                    .addOnFailureListener { out.text = "Ошибка: ${it.message}" }
            }
            .addOnFailureListener { out.text = "Не скачалась модель (нужен интернет): ${it.message}" }
    }
}
