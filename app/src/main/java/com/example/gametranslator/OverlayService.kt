package com.example.gametranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var held: Image? = null
    private var capW = 0
    private var capH = 0

    private var button: TextView? = null
    private var buttonLp: WindowManager.LayoutParams? = null
    private var overlay: FrameLayout? = null
    private var busy = false
    private var stopping = false

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build()
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val ch = NotificationChannel("t", "Переводчик", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val n = Notification.Builder(this, "t")
            .setContentTitle("Переводчик работает")
            .setContentText("Плавающая кнопка активна")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
        if (projection != null) return START_NOT_STICKY

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopping) stopSelf()
            }
        }, handler)
        projection = p
        setupCapture()
        addButton()
        return START_NOT_STICKY
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun setupCapture() {
        val (w, h) = screenSize()
        val dpi = resources.displayMetrics.densityDpi
        synchronized(lock) {
            held?.close()
            held = null
        }
        val old = reader
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                synchronized(lock) {
                    held?.close()
                    held = img
                }
            }
        }, handler)
        reader = r
        capW = w
        capH = h
        if (vd == null) {
            vd = projection!!.createVirtualDisplay(
                "cap", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, null
            )
        } else {
            vd!!.resize(w, h, dpi)
            vd!!.surface = r.surface
        }
        try {
            old?.close()
        } catch (e: Exception) {
        }
    }

    private fun addButton() {
        val d = resources.displayMetrics.density
        val size = (46 * d).toInt()
        val tv = TextView(this).apply {
            text = "Aa"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99202020.toInt())
                setStroke((2 * d).toInt(), 0xCC4FC3F7.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = (200 * d).toInt()

        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        var t0 = 0L
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y
                    moved = false
                    t0 = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = ox + dx
                        lp.y = oy + dy
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (System.currentTimeMillis() - t0 > 1000) stopSelf() else onTap()
                    }
                }
            }
            true
        }
        wm.addView(tv, lp)
        button = tv
        buttonLp = lp
    }

    private fun bringButtonToFront() {
        val b = button ?: return
        try {
            wm.removeView(b)
            wm.addView(b, buttonLp)
        } catch (e: Exception) {
        }
    }

    private fun onTap() {
        if (busy) return
        busy = true
        overlay?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        overlay = null
        val (w, h) = screenSize()
        if (w != capW || h != capH) setupCapture()
        button?.visibility = View.INVISIBLE
        handler.postDelayed({ capture() }, 450)
    }

    private fun capture() {
        val bmp = synchronized(lock) {
            held?.let {
                try {
                    toBitmap(it)
                } catch (e: Exception) {
                    null
                }
            }
        }
        button?.visibility = View.VISIBLE
        if (bmp == null) {
            toast("Нет кадра, нажми ещё раз")
            busy = false
            return
        }
        toast("Перевожу…")
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { showTranslations(it) }
            .addOnFailureListener {
                toast("Ошибка распознавания")
                busy = false
            }
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        p.buffer.rewind()
        val rowPadding = p.rowStride - p.pixelStride * img.width
        val full = Bitmap.createBitmap(
            img.width + rowPadding / p.pixelStride, img.height, Bitmap.Config.ARGB_8888
        )
        full.copyPixelsFromBuffer(p.buffer)
        return Bitmap.createBitmap(full, 0, 0, img.width, img.height)
    }

    private fun showTranslations(res: Text) {
        val blocks = res.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }
        if (blocks.isEmpty()) {
            toast("Текст не найден")
            busy = false
            return
        }
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                val ov = FrameLayout(this)
                ov.setOnClickListener {
                    try {
                        wm.removeView(ov)
                    } catch (e: Exception) {
                    }
                    if (overlay === ov) overlay = null
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= 28) {
                    lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                wm.addView(ov, lp)
                overlay = ov
                bringButtonToFront()
                for (b in blocks) {
                    translator.translate(b.text.replace("\n", " "))
                        .addOnSuccessListener { addLabel(ov, b, it) }
                }
                busy = false
            }
            .addOnFailureListener {
                toast("Не скачалась языковая модель. При первом запуске нужен интернет")
                busy = false
            }
    }

    private fun addLabel(ov: FrameLayout, b: Text.TextBlock, tr: String) {
        val r = b.boundingBox ?: return
        val lines = b.lines.size.coerceAtLeast(1)
        val tv = TextView(this).apply {
            text = tr
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6101010.toInt())
            setPadding(8, 4, 8, 4)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (r.height() / lines * 0.62f).coerceIn(26f, 46f))
        }
        val width = r.width().coerceAtLeast(320).coerceAtMost((capW - r.left).coerceAtLeast(200))
        val p = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)
        p.leftMargin = r.left
        p.topMargin = r.top
        ov.addView(tv, p)
    }

    private fun toast(s: String) {
        handler.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        stopping = true
        try {
            overlay?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        try {
            button?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        synchronized(lock) {
            held?.close()
            held = null
        }
        try {
            vd?.release()
            reader?.close()
            projection?.stop()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
