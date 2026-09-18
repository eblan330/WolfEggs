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
    // ─────────── ВОЛК: объёмный, с лапами, хвостом, ушами ───────────
    private fun drawWolf(canvas: Canvas, x: Float, y: Float) {
        val bob = sin(frameCount * 0.15).toFloat() * 3f
        val yy = y + bob

        // Тень под волком
        canvas.drawOval(
            RectF(x + 10f, yy + wolfH - 10f, x + wolfW - 10f, yy + wolfH + 20f),
            Paint().apply { color = Color.argb(90, 0, 0, 0); isAntiAlias = true }
        )

        // Хвост (сзади)
        val tailPaint = Paint().apply {
            color = Color.rgb(95, 95, 110); isAntiAlias = true
            style = Paint.Style.STROKE; strokeWidth = 22f; strokeCap = Paint.Cap.ROUND
        }
        val tail = Path().apply {
            moveTo(x + 5f, yy + wolfH * 0.6f)
            cubicTo(
                x - 50f, yy + wolfH * 0.4f,
                x - 70f, yy + wolfH * 0.9f,
                x - 30f, yy + wolfH * 1.05f
            )
        }
        canvas.drawPath(tail, tailPaint)
        // Кончик хвоста (светлый)
        canvas.drawCircle(x - 30f, yy + wolfH * 1.05f, 12f,
            Paint().apply { color = Color.rgb(220, 220, 230); isAntiAlias = true })

        // Задние лапы
        val legPaint = Paint().apply { color = Color.rgb(85, 85, 100); isAntiAlias = true }
        canvas.drawRoundRect(RectF(x + 30f, yy + wolfH - 10f, x + 60f, yy + wolfH + 25f),
            10f, 10f, legPaint)
        canvas.drawRoundRect(RectF(x + wolfW - 60f, yy + wolfH - 10f, x + wolfW - 30f, yy + wolfH + 25f),
            10f, 10f, legPaint)

        // Тело — с объёмом (градиент)
        val bodyGrad = android.graphics.RadialGradient(
            x + wolfW / 2f, yy + wolfH * 0.4f, wolfW * 0.7f,
            Color.rgb(150, 150, 165),
            Color.rgb(85, 85, 100),
            Shader.TileMode.CLAMP
        )
        val bodyPaint = Paint().apply {
            shader = bodyGrad; isAntiAlias = true
        }
        canvas.drawRoundRect(
            RectF(x, yy, x + wolfW, yy + wolfH),
            55f, 55f, bodyPaint
        )

        // Грудь — светлое пятно
        canvas.drawOval(
            RectF(x + wolfW * 0.3f, yy + wolfH * 0.5f, x + wolfW * 0.7f, yy + wolfH * 0.95f),
            Paint().apply { color = Color.rgb(220, 220, 230); isAntiAlias = true }
        )

        // Уши (торчком)
        val earPaint = Paint().apply { color = Color.rgb(105, 105, 120); isAntiAlias = true }
        val earInner = Paint().apply { color = Color.rgb(200, 150, 170); isAntiAlias = true }

        val earL = Path().apply {
            moveTo(x + 45f, yy + 10f)
            lineTo(x + 30f, yy - 45f)
            lineTo(x + 85f, yy + 5f)
            close()
        }
        val earR = Path().apply {
            moveTo(x + wolfW - 45f, yy + 10f)
            lineTo(x + wolfW - 30f, yy - 45f)
            lineTo(x + wolfW - 85f, yy + 5f)
            close()
        }
        canvas.drawPath(earL, earPaint)
        canvas.drawPath(earR, earPaint)
        // Внутренняя часть ушей
        val earLin = Path().apply {
            moveTo(x + 55f, yy + 8f)
            lineTo(x + 48f, yy - 30f)
            lineTo(x + 80f, yy + 5f)
            close()
        }
        val earRin = Path().apply {
            moveTo(x + wolfW - 55f, yy + 8f)
            lineTo(x + wolfW - 48f, yy - 30f)
            lineTo(x + wolfW - 80f, yy + 5f)
            close()
        }
        canvas.drawPath(earLin, earInner)
        canvas.drawPath(earRin, earInner)

        // Глаза с зрачками
        val eyeWhite = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        val eyeBlack = Paint().apply { color = Color.BLACK; isAntiAlias = true }
        val eyeGreen = Paint().apply { color = Color.rgb(120, 200, 90); isAntiAlias = true }

        canvas.drawCircle(x + 65f, yy + 60f, 18f, eyeWhite)
        canvas.drawCircle(x + wolfW - 65f, yy + 60f, 18f, eyeWhite)
        canvas.drawCircle(x + 68f, yy + 62f, 11f, eyeGreen)
        canvas.drawCircle(x + wolfW - 68f, yy + 62f, 11f, eyeGreen)
        canvas.drawCircle(x + 68f, yy + 62f, 5f, eyeBlack)
        canvas.drawCircle(x + wolfW - 68f, yy + 62f, 5f, eyeBlack)
        // Блики
        canvas.drawCircle(x + 71f, yy + 59f, 3f, eyeWhite)
        canvas.drawCircle(x + wolfW - 65f, yy + 59f, 3f, eyeWhite)

        // Нос
        val nose = Path().apply {
            moveTo(x + wolfW / 2f - 14f, yy + wolfH * 0.55f)
            lineTo(x + wolfW / 2f + 14f, yy + wolfH * 0.55f)
            lineTo(x + wolfW / 2f, yy + wolfH * 0.65f)
            close()
        }
        canvas.drawPath(nose, eyeBlack)

        // Открытая пасть-корзина сверху
        val mouthPaint = Paint().apply { color = Color.rgb(80, 40, 20); isAntiAlias = true }
        val mouthIn = Paint().apply { color = Color.rgb(180, 60, 60); isAntiAlias = true }
        canvas.drawRoundRect(
            RectF(x + 25f, yy - 42f, x + wolfW - 25f, yy),
            20f, 20f, mouthPaint
        )
        // Внутренность пасти
        canvas.drawRoundRect(
            RectF(x + 35f, yy - 32f, x + wolfW - 35f, yy - 5f),
            12f, 12f, mouthIn
        )
        // Зубы
        val toothPaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        val teeth = 7
        for (i in 0 until teeth) {
            val tx = x + 40f + i * ((wolfW - 80f) / (teeth - 1))
            val tooth = Path().apply {
                moveTo(tx - 8f, yy - 30f)
                lineTo(tx, yy - 15f)
                lineTo(tx + 8f, yy - 30f)
                close()
            }
            canvas.drawPath(tooth, toothPaint)
        }
    }

    // ─────────── ЯЙЦО: объёмное, с бликом ───────────
    private fun drawEgg(canvas: Canvas, e: Egg) {
        // Тень
        canvas.drawOval(
            RectF(e.x - e.r, e.y + e.r * 0.7f, e.x + e.r, e.y + e.r * 1.1f),
            Paint().apply { color = Color.argb(60, 0, 0, 0); isAntiAlias = true }
        )

        // Белок с градиентом
        val eggGrad = RadialGradient(
            e.x - e.r * 0.3f, e.y - e.r * 0.3f, e.r * 1.6f,
            Color.WHITE,
            Color.rgb(230, 225, 210),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(e.x, e.y, e.r, Paint().apply {
            shader = eggGrad; isAntiAlias = true
        })

        // Желток
        val yolkGrad = RadialGradient(
            e.x - e.r * 0.1f, e.y - e.r * 0.1f, e.r * 0.7f,
            Color.rgb(255, 235, 120),
            Color.rgb(255, 180, 40),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(e.x, e.y, e.r * 0.55f, Paint().apply {
            shader = yolkGrad; isAntiAlias = true
        })

        // Блик
        canvas.drawCircle(e.x - e.r * 0.35f, e.y - e.r * 0.4f, e.r * 0.18f,
            Paint().apply { color = Color.argb(200, 255, 255, 255); isAntiAlias = true })
    }

    // ─────────── БОМБА: с фитилём и искрой ───────────
    private fun drawBomb(canvas: Canvas, b: Bomb) {
        // Тень
        canvas.drawOval(
            RectF(b.x - b.r, b.y + b.r * 0.7f, b.x + b.r, b.y + b.r * 1.1f),
            Paint().apply { color = Color.argb(80, 0, 0, 0); isAntiAlias = true }
        )

        // Тело с градиентом
        val bombGrad = RadialGradient(
            b.x - b.r * 0.35f, b.y - b.r * 0.35f, b.r * 1.6f,
            Color.rgb(80, 80, 90),
            Color.rgb(10, 10, 15),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(b.x, b.y, b.r, Paint().apply {
            shader = bombGrad; isAntiAlias = true
        })

        // Блик
        canvas.drawCircle(b.x - b.r * 0.4f, b.y - b.r * 0.4f, b.r * 0.22f,
            Paint().apply { color = Color.argb(180, 255, 255, 255); isAntiAlias = true })

        // Крышка
        canvas.drawRoundRect(
            RectF(b.x - b.r * 0.35f, b.y - b.r * 1.15f, b.x + b.r * 0.35f, b.y - b.r * 0.85f),
            5f, 5f,
            Paint().apply { color = Color.rgb(140, 140, 150); isAntiAlias = true }
        )

        // Фитиль
        val fuse = Path().apply {
            moveTo(b.x, b.y - b.r * 1.1f)
            cubicTo(
                b.x + 15f, b.y - b.r * 1.5f,
                b.x - 10f, b.y - b.r * 1.8f,
                b.x + 5f, b.y - b.r * 2.2f
            )
        }
        canvas.drawPath(fuse, Paint().apply {
            color = Color.rgb(120, 80, 40); isAntiAlias = true
            style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
        })

        // Искра — пульсирует
        val pulse = 1f + sin(frameCount * 0.4).toFloat() * 0.3f
        val sparkX = b.x + 5f
        val sparkY = b.y - b.r * 2.2f
        val sparkGlow = Paint().apply {
            shader = RadialGradient(
                sparkX, sparkY, 22f * pulse,
                intArrayOf(
                    Color.argb(200, 255, 240, 100),
                    Color.argb(0, 255, 100, 0)
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(sparkX, sparkY, 22f * pulse, sparkGlow)
        canvas.drawCircle(sparkX, sparkY, 5f,
            Paint().apply { color = Color.rgb(255, 255, 200); isAntiAlias = true })
    }

    // ─────────── СИГАРЕТЫ: красно-белая пачка ───────────
    private fun drawCig(canvas: Canvas, c: CigPack) {
        val blink = c.life < 60f && ((c.life.toInt() / 6) % 2 == 0)
        if (blink) return

        // Свечение вокруг
        val pulse = 1f + sin(frameCount * 0.2).toFloat() * 0.15f
        val glow = Paint().apply {
            shader = RadialGradient(
                c.x + c.w / 2f, c.y + c.h / 2f, c.w * pulse,
                intArrayOf(
                    Color.argb(180, 255, 220, 100),
                    Color.argb(0, 255, 220, 100)
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(c.x + c.w / 2f, c.y + c.h / 2f, c.w * pulse, glow)

        // Тень пачки
        canvas.drawRoundRect(
            RectF(c.x + 5f, c.y + 8f, c.x + c.w + 5f, c.y + c.h + 8f),
            12f, 12f,
            Paint().apply { color = Color.argb(100, 0, 0, 0); isAntiAlias = true }
        )

        // Корпус
        canvas.drawRoundRect(
            RectF(c.x, c.y, c.x + c.w, c.y + c.h),
            12f, 12f,
            Paint().apply { color = Color.rgb(215, 25, 35); isAntiAlias = true }
        )

        // Белая полоса сверху
        canvas.drawRect(
            c.x, c.y + c.h * 0.12f, c.x + c.w, c.y + c.h * 0.32f,
            Paint().apply { color = Color.WHITE; isAntiAlias = true }
        )

        // Кнопка-крышка
        canvas.drawRoundRect(
            RectF(c.x + c.w * 0.1f, c.y + c.h * 0.02f, c.x + c.w * 0.9f, c.y + c.h * 0.1f),
            8f, 8f,
            Paint().apply { color = Color.rgb(180, 20, 25); isAntiAlias = true }
        )

        // Надпись "SMOKE"
        canvas.drawText(
            "SMOKE",
            c.x + c.w / 2f, c.y + c.h * 0.6f,
            Paint().apply {
                color = Color.WHITE; textSize = 24f; isAntiAlias = true
                textAlign = Paint.Align.CENTER; isFakeBoldText = true
            }
        )
        // +1 ♥
        canvas.drawText(
            "+1 \u2665",
            c.x + c.w / 2f, c.y + c.h * 0.82f,
            Paint().apply {
                color = Color.rgb(255, 220, 100); textSize = 26f; isAntiAlias = true
                textAlign = Paint.Align.CENTER; isFakeBoldText = true
            }
        )
        // Предупреждение снизу
        canvas.drawRect(
            c.x + 4f, c.y + c.h * 0.88f, c.x + c.w - 4f, c.y + c.h - 4f,
            Paint().apply { color = Color.rgb(80, 60, 40); isAntiAlias = true }
        )
        canvas.drawText(
            "!",
            c.x + c.w / 2f, c.y + c.h * 0.97f,
            Paint().apply {
                color = Color.rgb(255, 180, 80); textSize = 16f; isAntiAlias = true
                textAlign = Paint.Align.CENTER; isFakeBoldText = true
            }
        )
    }

    // ─────────── ЗАЯЦ-БОСС: зубастый и злой ───────────
    private fun drawRabbit(canvas: Canvas, x: Float, y: Float, screaming: Boolean) {
        val bob = sin(frameCount * 0.1).toFloat() * 4f
        val yy = y + bob

        // Тень
        canvas.drawOval(
            RectF(x, yy + bossH - 20f, x + bossW, yy + bossH + 30f),
            Paint().apply { color = Color.argb(100, 0, 0, 0); isAntiAlias = true }
        )

        // Уши — длинные, с розовой внутренностью
        val earPaint = Paint().apply { color = Color.rgb(235, 235, 245); isAntiAlias = true }
        val earIn = Paint().apply { color = Color.rgb(255, 170, 190); isAntiAlias = true }
        // Левое ухо
        canvas.drawRoundRect(
            RectF(x + 35f, yy - 130f, x + 85f, yy + 10f),
            30f, 30f, earPaint
        )
        canvas.drawRoundRect(
            RectF(x + 48f, yy - 115f, x + 72f, yy - 5f),
            15f, 15f, earIn
        )
        // Правое ухо
        canvas.drawRoundRect(
            RectF(x + bossW - 85f, yy - 130f, x + bossW - 35f, yy + 10f),
            30f, 30f, earPaint
        )
        canvas.drawRoundRect(
            RectF(x + bossW - 72f, yy - 115f, x + bossW - 48f, yy - 5f),
            15f, 15f, earIn
        )

        // Тело с градиентом
        val bodyGrad = RadialGradient(
            x + bossW / 2f, yy + bossH * 0.4f, bossW * 0.8f,
            Color.rgb(250, 250, 255),
            Color.rgb(200, 200, 215),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(
            RectF(x, yy, x + bossW, yy + bossH),
            60f, 60f,
            Paint().apply { shader = bodyGrad; isAntiAlias = true }
        )

        // Глаза — злые, с красным отблеском
        val eyeWhite = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        val eyeRed = Paint().apply { color = Color.rgb(200, 20, 20); isAntiAlias = true }
        val eyeBlack = Paint().apply { color = Color.BLACK; isAntiAlias = true }

        canvas.drawCircle(x + 75f, yy + 80f, 22f, eyeWhite)
        canvas.drawCircle(x + bossW - 75f, yy + 80f, 22f, eyeWhite)
        canvas.drawCircle(x + 75f, yy + 80f, 15f, eyeRed)
        canvas.drawCircle(x + bossW - 75f, yy + 80f, 15f, eyeRed)
        canvas.drawCircle(x + 75f, yy + 80f, 6f, eyeBlack)
        canvas.drawCircle(x + bossW - 75f, yy + 80f, 6f, eyeBlack)
        // Блики
        canvas.drawCircle(x + 80f, yy + 75f, 3f, eyeWhite)
        canvas.drawCircle(x + bossW - 70f, yy + 75f, 3f, eyeWhite)

        // Брови — злые (диагональные)
        val browPaint = Paint().apply { color = Color.BLACK; isAntiAlias = true
            style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(x + 50f, yy + 45f, x + 100f, yy + 60f, browPaint)
        canvas.drawLine(x + bossW - 50f, yy + 45f, x + bossW - 100f, yy + 60f, browPaint)

        // Нос
        canvas.drawCircle(x + bossW / 2f, yy + bossH * 0.5f, 8f,
            Paint().apply { color = Color.rgb(255, 150, 170); isAntiAlias = true })

        // Открытая пасть с зубами (меняется от screaming)
        bossMouth = if (screaming) 1f else 0f
        val mouthH = 30f + bossMouth * 40f
        val mouthY = yy + bossH * 0.72f

        // Тёмный рот
        canvas.drawRoundRect(
            RectF(x + 50f, mouthY, x + bossW - 50f, mouthY + mouthH),
            15f, 15f,
            Paint().apply { color = Color.rgb(80, 10, 20); isAntiAlias = true }
        )

        // Зубы сверху
        val toothPaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        for (i in 0 until 6) {
            val tx = x + 60f + i * ((bossW - 120f) / 5f)
            val tooth = Path().apply {
                moveTo(tx - 10f, mouthY)
                lineTo(tx, mouthY + mouthH * 0.5f)
                lineTo(tx + 10f, mouthY)
                close()
            }
            canvas.drawPath(tooth, toothPaint)
        }
        // Зубы снизу (если пасть открыта сильнее)
        if (screaming) {
            for (i in 0 until 6) {
                val tx = x + 60f + i * ((bossW - 120f) / 5f)
                val tooth = Path().apply {
                    moveTo(tx - 10f, mouthY + mouthH)
                    lineTo(tx, mouthY + mouthH * 0.55f)
                    lineTo(tx + 10f, mouthY + mouthH)
                    close()
                }
                canvas.drawPath(tooth, toothPaint)
            }
        }

        // Крик
        if (screaming) {
            val cryPaint = Paint().apply {
                color = Color.rgb(255, 30, 30); textSize = 78f; isAntiAlias = true
                isFakeBoldText = true; textAlign = Paint.Align.CENTER
                setShadowLayer(15f, 0f, 0f, Color.BLACK)
            }
            val scale = 1f + sin(frameCount * 0.5).toFloat() * 0.08f
            canvas.save()
            canvas.scale(scale, scale, x + bossW / 2f, yy - 150f)
            canvas.drawText("ZAyAC-VOLK!", x + bossW / 2f, yy - 140f, cryPaint)
            canvas.restore()
        }
    }

    // ─────────── HUD ───────────
    private fun drawHud(canvas: Canvas) {
        // Плашка сверху слева
        canvas.drawRoundRect(
            RectF(20f, 30f, 340f, 260f), 20f, 20f,
            Paint().apply { color = Color.argb(120, 0, 0, 0); isAntiAlias = true }
        )
        canvas.drawText("Score: $score", 45f, 95f, textPaint)
        canvas.drawText("Lives: $lives / $MAX_LIVES", 45f, 170f, textPaint)
        canvas.drawText("Best: $highScore", 45f, 235f, smallText)

        if (state == State.BOSS) {
            val p = Paint().apply {
                color = Color.rgb(255, 60, 60); textSize = 50f; isAntiAlias = true
                textAlign = Paint.Align.RIGHT; isFakeBoldText = true
                setShadowLayer(10f, 0f, 0f, Color.BLACK)
            }
            canvas.drawText("BOSS: RABBIT", width - 40f, 90f, p)
        }
    }

    private fun drawPlusLife(canvas: Canvas) {
        val alpha = (plusLifeTimer / 60f * 255).toInt().coerceIn(0, 255)
        val p = Paint().apply {
            color = Color.rgb(80, 255, 80); textSize = 80f; isAntiAlias = true
            isFakeBoldText = true; textAlign = Paint.Align.CENTER
            this.alpha = alpha; setShadowLayer(10f, 0f, 0f, Color.BLACK)
        }
        val yOffset = (60f - plusLifeTimer) * 3f
        canvas.drawText("+1 LIFE", plusLifeX, plusLifeY - yOffset, p)
    }

    private fun drawGameOver(canvas: Canvas) {
        // Затемнение
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(),
            Paint().apply { color = Color.argb(160, 0, 0, 0) })

        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("GAME OVER", width / 2f, height / 2f - 80f, textPaint)
        canvas.drawText("Score: $score", width / 2f, height / 2f + 20f, textPaint)
        if (score >= highScore && score > 0) {
            val p = Paint().apply {
                color = Color.rgb(255, 215, 0); textSize = 60f; isAntiAlias = true
                textAlign = Paint.Align.CENTER; isFakeBoldText = true
                setShadowLayer(10f, 0f, 0f, Color.BLACK)
            }
            canvas.drawText("NEW RECORD!", width / 2f, height / 2f + 110f, p)
        }
        textPaint.textSize = 45f
        canvas.drawText("Tap to restart", width / 2f, height / 2f + 220f, textPaint)
        textPaint.textSize = 60f
        textPaint.textAlign = Paint.Align.LEFT
    }

    // ─────────── СКРИМЕР ───────────
    private fun drawScreamer(canvas: Canvas) {
        val flash = sin(screamTimer.toDouble() * 0.6) > 0
        canvas.drawColor(if (flash) Color.RED else Color.BLACK)

        val sx = Random.nextFloat() * 60f - 30f
        val sy = Random.nextFloat() * 60f - 30f
        canvas.save()
        canvas.translate(sx, sy)

        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.42f

        // Голова с градиентом
        val faceGrad = RadialGradient(
            cx - r * 0.2f, cy - r * 0.2f, r * 1.4f,
            Color.rgb(255, 255, 255),
            Color.rgb(200, 200, 210),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, Paint().apply {
            shader = faceGrad; isAntiAlias = true
        })

        // Уши
        val earPaint = Paint().apply { color = Color.rgb(240, 240, 250); isAntiAlias = true }
        canvas.drawRoundRect(
            RectF(cx - r * 0.65f, cy - r * 2.3f, cx - r * 0.15f, cy - r * 0.4f),
            45f, 45f, earPaint
        )
        canvas.drawRoundRect(
            RectF(cx + r * 0.15f, cy - r * 2.3f, cx + r * 0.65f, cy - r * 0.4f),
            45f, 45f, earPaint
        )
        // Внутренняя часть ушей
        val earIn = Paint().apply { color = Color.rgb(255, 140, 160); isAntiAlias = true }
        canvas.drawRoundRect(
            RectF(cx - r * 0.55f, cy - r * 2.1f, cx - r * 0.25f, cy - r * 0.6f),
            25f, 25f, earIn
        )
        canvas.drawRoundRect(
            RectF(cx + r * 0.25f, cy - r * 2.1f, cx + r * 0.55f, cy - r * 0.6f),
            25f, 25f, earIn
        )

        // Красные глаза с чёрными зрачками
        val eyeRed = Paint().apply {
            shader = RadialGradient(
                cx - r * 0.35f, cy - r * 0.15f, r * 0.3f,
                Color.rgb(255, 60, 60),
                Color.rgb(150, 0, 0),
                Shader.TileMode.CLAMP
            )
            isAntiAlias = true
        }
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.15f, r * 0.2f, eyeRed)
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.15f, r * 0.2f, eyeRed)

        val eyeRed2 = Paint().apply {
            shader = RadialGradient(
                cx + r * 0.35f, cy - r * 0.15f, r * 0.3f,
                Color.rgb(255, 60, 60),
                Color.rgb(150, 0, 0),
                Shader.TileMode.CLAMP
            )
            isAntiAlias = true
        }
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.15f, r * 0.2f, eyeRed2)

        val pupil = Paint().apply { color = Color.BLACK; isAntiAlias = true }
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.15f, r * 0.09f, pupil)
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.15f, r * 0.09f, pupil)

        // Кровавая пасть
        canvas.drawRoundRect(
            RectF(cx - r * 0.55f, cy + r * 0.3f, cx + r * 0.55f, cy + r * 0.65f),
            20f, 20f,
            Paint().apply { color = Color.rgb(140, 0, 0); isAntiAlias = true }
        )

        // Зубы
        val tooth = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        for (i in 0 until 7) {
            val tx = cx - r * 0.55f + i * (r * 0.19f)
            val path = Path().apply {
                moveTo(tx, cy + r * 0.3f)
                lineTo(tx + r * 0.09f, cy + r * 0.55f)
                lineTo(tx + r * 0.18f, cy + r * 0.3f)
                close()
            }
            canvas.drawPath(path, tooth)
        }

        // Крик
        val scale = 1f + sin(screamTimer.toDouble() * 0.3).toFloat() * 0.1f
        canvas.save()
        canvas.scale(scale, scale, cx, cy + r * 1.5f)
        canvas.drawText("!!!", cx, cy + r * 1.5f, bigText)
        canvas.restore()

        canvas.restore()
    }

    // ─────────── ЛОГИКА ───────────
    private fun update() {
        shakeX = 0f; shakeY = 0f
        if (plusLifeTimer > 0f) plusLifeTimer--
        when (state) {
            State.PLAYING  -> { updatePlaying(); updateCig() }
            State.BOSS     -> { updateBoss();    updateCig() }
            State.SCREAMER -> updateScreamer()
            State.GAME_OVER -> {}
        }
    }

    private fun updateCig() {
        val c = cig
        if (c != null) {
            c.life--
            if (c.life <= 0f) cig = null
            return
        }
        cigTimer++
        if (cigTimer >= cigNextSpawn) {
            spawnCig(); cigTimer = 0f; cigNextSpawn = randCigInterval()
        }
    }

    private fun spawnCig() {
        val w = 90f; val h = 130f; val margin = 60f
        val x = Random.nextFloat() * (width - w - 2 * margin) + margin
        val y = Random.nextFloat() * (height * 0.5f) + height * 0.1f
        cig = CigPack(x, y, w, h, CIG_LIFETIME)
        vibrate(40)
    }

    private fun updatePlaying() {
        spawnTimer++
        if (spawnTimer >= 40f) {
            spawnTimer = 0f
            val r = 45f
            eggs.add(Egg(
                Random.nextFloat() * (width - 2 * r) + r, -r,
                Random.nextFloat() * 4.5f + 10.5f, r,
                Random.nextFloat() * 360f
            ))
        }
        moveEggs()
        if (score >= BOSS_TRIGGER_SCORE) startBoss()
    }

    private fun startBoss() {
        state = State.BOSS
        eggs.clear(); bombs.clear()
        bossX = width / 2f - bossW / 2f
        bossSpeed = 6f; bossBombTimer = 0f; bossCryTimer = 0f; bossBombsThrown = 0
        vibrate(200)
    }

    private fun updateBoss() {
        bossX += bossSpeed * bossDir
        if (bossX <= 0) { bossX = 0f; bossDir = 1f }
        if (bossX + bossW >= width) { bossX = width - bossW; bossDir = -1f }
        if (bossCryTimer > 0) bossCryTimer--
        bossBombTimer++
        val interval = (60f - bossBombsThrown * 0.5f).coerceAtLeast(25f)
        if (bossBombTimer >= interval) {
            bossBombTimer = 0f; bossBombsThrown++; bossCryTimer = 30f
            vibrate(80)
            bombs.add(Bomb(bossX + bossW / 2f, bossY + bossH, 16f, 38f))
            bossSpeed = (bossSpeed + 0.15f).coerceAtMost(16f)
        }
        val wolfY = height - wolfH - 100f
        val it = bombs.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.y += b.speed
            val caught = b.y + b.r >= wolfY - 35f && b.y - b.r <= wolfY + wolfH &&
                    b.x in wolfX..(wolfX + wolfW)
            if (caught) { triggerScreamer(); return }
            if (b.y - b.r > height) it.remove()
        }
    }

    private fun updateScreamer() {
        screamTimer++
        shakeX = Random.nextFloat() * 70f - 35f
        shakeY = Random.nextFloat() * 70f - 35f
        if (screamTimer >= screamDuration) resetToGameOver()
    }

    private fun triggerScreamer() {
        state = State.SCREAMER; screamTimer = 0f; vibrate(1500)
    }

    private fun resetToGameOver() {
        saveHighScore()
        state = State.GAME_OVER
        score = 0; lives = 3
        eggs.clear(); bombs.clear(); cig = null
        cigTimer = 0f; spawnTimer = 0f
        bossBombsThrown = 0; bossSpeed = 6f; screamTimer = 0f
    }

    private fun moveEggs() {
        val wolfY = height - wolfH - 100f
        val it = eggs.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.y += e.speed
            val caught = e.y + e.r >= wolfY - 35f && e.y - e.r <= wolfY + wolfH &&
                    e.x in wolfX..(wolfX + wolfW)
            if (caught) { score++; it.remove(); continue }
            if (e.y - e.r > height) {
                lives--
                it.remove()
                if (lives <= 0) { saveHighScore(); state = State.GAME_OVER }
            }
        }
    }

    private fun saveHighScore() {
        if (score > highScore) {
            highScore = score
            prefs.edit().putInt("high_score", highScore).apply()
        }
    }

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (_: SecurityException) { }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                when (state) {
                    State.GAME_OVER -> resetGame()
                    State.SCREAMER  -> {}
                    else -> {
                        val c = cig
                        if (c != null && event.x in c.x..(c.x + c.w) &&
                                     event.y in c.y..(c.y + c.h)) {
                            collectCig(c)
                        } else {
                            wolfX = (event.x - wolfW / 2f).coerceIn(0f, width - wolfW)
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (state != State.GAME_OVER && state != State.SCREAMER) {
                    wolfX = (event.x - wolfW / 2f).coerceIn(0f, width - wolfW)
                }
            }
        }
        return true
    }

    private fun collectCig(c: CigPack) {
        if (lives < MAX_LIVES) {
            lives++
            plusLifeTimer = 60f
            plusLifeX = c.x + c.w / 2f
            plusLifeY = c.y
            vibrate(120)
        } else {
            score += 5
            vibrate(60)
        }
        cig = null
    }

    private fun resetGame() {
        state = State.PLAYING
        score = 0; lives = 3
        eggs.clear(); bombs.clear(); cig = null
        cigTimer = 0f; spawnTimer = 0f
        bossBombsThrown = 0; bossSpeed = 6f; bossCryTimer = 0f
    }
}
