package com.example.gametranslator

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
        if (rawText.isNullOrEmpty()) {
            finish()
            return
        }

        val isRussian = Regex("[а-яА-ЯёЁ]").containsMatchIn(rawText)
        val srcLang = if (isRussian) TranslateLanguage.RUSSIAN else TranslateLanguage.ENGLISH
        val dstLang = if (isRussian) TranslateLanguage.ENGLISH else TranslateLanguage.RUSSIAN

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (isRussian) "Перевод (RU → EN)" else "Перевод (EN → RU)")
            .setMessage("Перевожу…")
            .setNegativeButton("Закрыть") { d, _ -> d.dismiss() }
            .setOnDismissListener { finish() }
            .create()

        dialog.show()

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(srcLang)
                .setTargetLanguage(dstLang)
                .build()
        )

        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(rawText)
                    .addOnSuccessListener { translated ->
                        if (!isFinishing) {
                            dialog.setMessage(translated)
                            dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Копировать") { _, _ ->
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Перевод", translated))
                                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
                            }
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                                visibility = android.view.View.VISIBLE
                                setOnClickListener {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Перевод", translated))
                                    Toast.makeText(this@ProcessTextActivity, "Скопировано", Toast.LENGTH_SHORT).show()
                                    dialog.dismiss()
                                }
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        if (!isFinishing) dialog.setMessage("Ошибка перевода: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                if (!isFinishing) {
                    dialog.setMessage("Не удалось скачать словарь (при первом запуске нужен интернет): ${e.message}")
                }
            }
    }
}
