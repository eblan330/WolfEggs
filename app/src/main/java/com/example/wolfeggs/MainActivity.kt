package com.example.wolfeggs

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        setContentView(GameView(this))
    }
}

private enum class State { PLAYING, BOSS, SCREAMER, GAME_OVER }

class GameView(context: Context) : View(context) {

    private data class Egg(val x: Float, var y: Float, val speed: Float, val r: Float, val rot: Float)
    private data class Bomb(var x: Float, var y: Float, val speed: Float, val r: Float)
    private data class CigPack(val x: Float, val y: Float, val w: Float, val h: Float, var life: Float)
    private data class Star(val x: Float, val y: Float, val r: Float, val twinkle: Float)

    private val MAX_LIVES = 5
    private val CIG_LIFETIME = 240f
    private val CIG_MIN_INTERVAL = 300f
    private val CIG_MAX_INTERVAL = 600f
    private val BOSS_TRIGGER_SCORE = 100

    private val wolfW = 220f
    private val wolfH = 150f

    private val stars = mutableListOf<Star>()

    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 60f; isAntiAlias = true
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
        isFakeBoldText = true
    }
    private val smallText = Paint().apply {
        color = Color.rgb(180, 180, 200); textSize = 42f; isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    private val bigText = Paint().apply {
        color = Color.RED; textSize = 170f; isAntiAlias = true
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(25f, 0f, 0f, Color.BLACK)
    }

    private var wolfX = 0f
    private var wolfBob = 0f

    private var state = State.PLAYING
    private val eggs = mutableListOf<Egg>()
    private val bombs = mutableListOf<Bomb>()
    private var cig: CigPack? = null
    private var cigTimer = 0f
    private var cigNextSpawn = 0f

    private var score = 0
    private var lives = 3
    private var spawnTimer = 0f
    private var shakeX = 0f
    private var shakeY = 0f
    private var frameCount = 0L

    private val bossW = 280f
    private val bossH = 220f
    private var bossX = 0f
    private var bossY = 130f
    private var bossDir = 1f
    private var bossSpeed = 6f
    private var bossBombTimer = 0f
    private var bossCryTimer = 0f
    private var bossBombsThrown = 0
    private var bossMouth = 0f

    private var screamTimer = 0f
    private val screamDuration = 180f

    private var plusLifeTimer = 0f
    private var plusLifeX = 0f
    private var plusLifeY = 0f

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wolf_eggs", Context.MODE_PRIVATE)
    private var highScore = prefs.getInt("high_score", 0)

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    init { cigNextSpawn = randCigInterval() }

    private fun randCigInterval() =
        Random.nextFloat() * (CIG_MAX_INTERVAL - CIG_MIN_INTERVAL) + CIG_MIN_INTERVAL

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        wolfX = w / 2f - wolfW / 2f
        bossX = w / 2f - bossW / 2f
        stars.clear()
        repeat(60) {
            stars.add(Star(
                Random.nextFloat() * w,
                Random.nextFloat() * h * 0.7f,
                Random.nextFloat() * 2.5f + 0.8f,
                Random.nextFloat() * 6.28f
            ))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frameCount++

        if (state == State.SCREAMER) { drawScreamer(canvas); update(); return }

        canvas.save()
        canvas.translate(shakeX, shakeY)

        drawBackground(canvas)

        val wolfY = height - wolfH - 100f
        if (state == State.BOSS) drawRabbit(canvas, bossX, bossY, bossCryTimer > 0)
        drawWolf(canvas, wolfX, wolfY)
        eggs.forEach  { drawEgg(canvas, it) }
        bombs.forEach { drawBomb(canvas, it) }
        cig?.let { drawCig(canvas, it) }
        drawHud(canvas)
        if (plusLifeTimer > 0f) drawPlusLife(canvas)
        if (state == State.GAME_OVER) drawGameOver(canvas)

        canvas.restore()
        update()
        invalidate()
    }

    // ─────────── ФОН: небо, луна, звёзды, силуэты деревьев ───────────
    private fun drawBackground(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val horizon = h * 0.75f

        // Градиент неба
        val sky = Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, horizon,
                Color.rgb(15, 8, 35),
                Color.rgb(60, 30, 80),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, horizon, sky)

        // Луна
        val moonX = w * 0.82f
        val moonY = h * 0.15f
        val moonR = w * 0.11f
        val moonGlow = Paint().apply {
            shader = RadialGradient(
                moonX, moonY, moonR * 3f,
                intArrayOf(
                    Color.argb(120, 255, 240, 200),
                    Color.argb(0, 255, 240, 200)
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(moonX, moonY, moonR * 3f, moonGlow)
        canvas.drawCircle(moonX, moonY, moonR, Paint().apply {
            color = Color.rgb(245, 240, 210); isAntiAlias = true
        })
        // Кратеры на луне
        canvas.drawCircle(moonX - moonR * 0.3f, moonY - moonR * 0.2f, moonR * 0.18f,
            Paint().apply { color = Color.rgb(210, 205, 180); isAntiAlias = true })
        canvas.drawCircle(moonX + moonR * 0.35f, moonY + moonR * 0.3f, moonR * 0.14f,
            Paint().apply { color = Color.rgb(210, 205, 180); isAntiAlias = true })

        // Звёзды (мерцают)
        for (s in stars) {
            val alpha = (150 + 105 * sin((frameCount * 0.05 + s.twinkle).toDouble())).toInt()
                .coerceIn(0, 255)
            canvas.drawCircle(s.x, s.y, s.r, Paint().apply {
                color = Color.WHITE; this.alpha = alpha; isAntiAlias = true
            })
        }

        // Земля с градиентом
        val ground = Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, horizon, 0f, h,
                Color.rgb(35, 20, 55),
                Color.rgb(15, 8, 25),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, horizon, w, h, ground)

        // Линия горизонта — светящаяся
        canvas.drawRect(0f, horizon - 2f, w, horizon + 2f,
            Paint().apply { color = Color.rgb(120, 60, 160); isAntiAlias = true })

        // Силуэты ёлок вдалеке
        drawTree(canvas, w * 0.1f, horizon, h * 0.12f)
        drawTree(canvas, w * 0.28f, horizon, h * 0.16f)
        drawTree(canvas, w * 0.52f, horizon, h * 0.10f)
        drawTree(canvas, w * 0.72f, horizon, h * 0.14f)
        drawTree(canvas, w * 0.92f, horizon, h * 0.09f)
    }

    private fun drawTree(canvas: Canvas, x: Float, baseY: Float, size: Float) {
        val paint = Paint().apply { color = Color.rgb(20, 12, 30); isAntiAlias = true }
        val path = Path().apply {
            moveTo(x, baseY - size)
            lineTo(x - size * 0.4f, baseY - size * 0.5f)
            lineTo(x - size * 0.25f, baseY - size * 0.5f)
            lineTo(x - size * 0.55f, baseY)
            lineTo(x + size * 0.55f, baseY)
            lineTo(x + size * 0.25f, baseY - size * 0.5f)
            lineTo(x + size * 0.4f, baseY - size * 0.5f)
            close()
        }
        canvas.drawPath(path, paint)
    }
