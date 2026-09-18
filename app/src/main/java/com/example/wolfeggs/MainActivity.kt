package com.example.wolfeggs

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    private data class Egg(val x: Float, var y: Float, val speed: Float, val r: Float)
    private data class Bomb(var x: Float, var y: Float, val speed: Float, val r: Float)
    private data class CigPack(val x: Float, val y: Float, val w: Float, val h: Float, var life: Float)

    private val MAX_LIVES = 5
    private val CIG_LIFETIME = 240f
    private val CIG_MIN_INTERVAL = 300f
    private val CIG_MAX_INTERVAL = 600f
    private val BOSS_TRIGGER_SCORE = 100

    private val skyPaint    = Paint().apply { color = Color.rgb(20, 10, 30) }
    private val grassPaint  = Paint().apply { color = Color.rgb(40, 25, 60) }
    private val wolfPaint   = Paint().apply { color = Color.rgb(120, 120, 135) }
    private val eyePaint    = Paint().apply { color = Color.BLACK }
    private val mouthPaint  = Paint().apply { color = Color.rgb(160, 82, 45) }
    private val eggPaint    = Paint().apply { color = Color.rgb(255, 245, 220) }
    private val yolkPaint   = Paint().apply { color = Color.rgb(255, 200, 60) }
    private val bombPaint   = Paint().apply { color = Color.rgb(30, 30, 30) }
    private val fusePaint   = Paint().apply { color = Color.rgb(255, 120, 0) }
    private val rabbitPaint = Paint().apply { color = Color.rgb(230, 230, 240) }
    private val rabbitEar   = Paint().apply { color = Color.rgb(255, 180, 200) }
    private val bloodPaint  = Paint().apply { color = Color.rgb(180, 0, 0) }

    private val cigBody   = Paint().apply { color = Color.rgb(220, 30, 40); isAntiAlias = true }
    private val cigStripe = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val cigLabel  = Paint().apply {
        color = Color.rgb(255, 220, 100); textSize = 22f
        isAntiAlias = true; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val cigGlow = Paint().apply {
        color = Color.rgb(255, 200, 80); isAntiAlias = true
        style = Paint.Style.STROKE; strokeWidth = 4f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 60f; isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    private val smallText = Paint().apply {
        color = Color.WHITE; textSize = 45f; isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    private val bigText = Paint().apply {
        color = Color.RED; textSize = 160f; isAntiAlias = true
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(20f, 0f, 0f, Color.BLACK)
    }

    private val wolfW = 200f
    private val wolfH = 130f
    private var wolfX = 0f

    private var state = State.PLAYING
    private val eggs  = mutableListOf<Egg>()
    private val bombs = mutableListOf<Bomb>()
    private var cig: CigPack? = null
    private var cigTimer = 0f
    private var cigNextSpawn = 0f

    private var score = 0
    private var lives = 3
    private var spawnTimer = 0f
    private var shakeX = 0f
    private var shakeY = 0f

    private val bossW = 260f
    private val bossH = 200f
    private var bossX = 0f
    private var bossY = 120f
    private var bossDir = 1f
    private var bossSpeed = 6f
    private var bossBombTimer = 0f
    private var bossCryTimer = 0f
    private var bossBombsThrown = 0

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
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (state == State.SCREAMER) { drawScreamer(canvas); update(); return }

        canvas.save()
        canvas.translate(shakeX, shakeY)
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.75f, skyPaint)
        canvas.drawRect(0f, height * 0.75f, width.toFloat(), height.toFloat(), grassPaint)

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

    private fun drawWolf(canvas: Canvas, x: Float, y: Float) {
        canvas.drawRoundRect(x, y, x + wolfW, y + wolfH, 45f, 45f, wolfPaint)
        canvas.drawCircle(x + 40f, y + 10f, 25f, wolfPaint)
        canvas.drawCircle(x + wolfW - 40f, y + 10f, 25f, wolfPaint)
        canvas.drawCircle(x + 55f, y + 50f, 12f, eyePaint)
        canvas.drawCircle(x + wolfW - 55f, y + 50f, 12f, eyePaint)
        canvas.drawRect(x + 30f, y - 35f, x + wolfW - 30f, y, mouthPaint)
    }

    private fun drawEgg(canvas: Canvas, e: Egg) {
        canvas.drawCircle(e.x, e.y, e.r, eggPaint)
        canvas.drawCircle(e.x, e.y, e.r * 0.5f, yolkPaint)
    }

    private fun drawBomb(canvas: Canvas, b: Bomb) {
        canvas.drawCircle(b.x, b.y, b.r, bombPaint)
        canvas.drawCircle(b.x, b.y - b.r, 6f, fusePaint)
    }

    private fun drawCig(canvas: Canvas, c: CigPack) {
        val blink = c.life < 60f && ((c.life.toInt() / 6) % 2 == 0)
        if (blink) return
        canvas.drawRoundRect(c.x - 6f, c.y - 6f, c.x + c.w + 6f, c.y + c.h + 6f, 14f, 14f, cigGlow)
        canvas.drawRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, 10f, 10f, cigBody)
        canvas.drawRect(c.x, c.y + c.h * 0.15f, c.x + c.w, c.y + c.h * 0.35f, cigStripe)
        canvas.drawText("SMOKE", c.x + c.w / 2f, c.y + c.h * 0.65f, cigLabel)
        canvas.drawText("+1", c.x + c.w / 2f, c.y + c.h * 0.9f, cigLabel)
    }

    private fun drawPlusLife(canvas: Canvas) {
        val alpha = (plusLifeTimer / 60f * 255).toInt().coerceIn(0, 255)
        val p = Paint().apply {
            color = Color.rgb(80, 255, 80); textSize = 70f; isAntiAlias = true
            isFakeBoldText = true; textAlign = Paint.Align.CENTER
            this.alpha = alpha; setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }
        val yOffset = (60f - plusLifeTimer) * 3f
        canvas.drawText("+1 LIFE", plusLifeX, plusLifeY - yOffset, p)
    }

    private fun drawRabbit(canvas: Canvas, x: Float, y: Float, screaming: Boolean) {
        canvas.drawRoundRect(x, y, x + bossW, y + bossH, 50f, 50f, rabbitPaint)
        canvas.drawRoundRect(x + 40f, y - 90f, x + 80f, y, 30f, 30f, rabbitPaint)
        canvas.drawRoundRect(x + bossW - 80f, y - 90f, x + bossW - 40f, y, 30f, 30f, rabbitPaint)
        canvas.drawRoundRect(x + 50f, y - 80f, x + 70f, y - 10f, 15f, 15f, rabbitEar)
        canvas.drawRoundRect(x + bossW - 70f, y - 80f, x + bossW - 50f, y - 10f, 15f, 15f, rabbitEar)
        canvas.drawCircle(x + 70f, y + 70f, 16f, eyePaint)
        canvas.drawCircle(x + bossW - 70f, y + 70f, 16f, eyePaint)
        if (screaming) {
            val cry = Paint().apply {
                color = Color.rgb(255, 50, 50); textSize = 70f; isAntiAlias = true
                isFakeBoldText = true; textAlign = Paint.Align.CENTER
                setShadowLayer(10f, 0f, 0f, Color.BLACK)
            }
            canvas.drawText("ZAyAC-VOLK!", x + bossW / 2f, y - 120f, cry)
        }
    }

    private fun drawHud(canvas: Canvas) {
        canvas.drawText("Score: $score", 40f, 90f, textPaint)
        canvas.drawText("Lives: $lives / $MAX_LIVES", 40f, 170f, textPaint)
        canvas.drawText("Best: $highScore", 40f, 240f, smallText)
        if (state == State.BOSS) {
            val p = Paint().apply {
                color = Color.rgb(255, 80, 80); textSize = 50f
                isAntiAlias = true; textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("BOSS: RABBIT", width - 40f, 90f, p)
        }
    }

    private fun drawGameOver(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("GAME OVER", width / 2f, height / 2f - 80f, textPaint)
        canvas.drawText("Score: $score", width / 2f, height / 2f + 20f, textPaint)
        if (score >= highScore && score > 0) {
            val p = Paint().apply {
                color = Color.rgb(255, 215, 0); textSize = 55f; isAntiAlias = true
                textAlign = Paint.Align.CENTER; isFakeBoldText = true
            }
            canvas.drawText("NEW RECORD!", width / 2f, height / 2f + 100f, p)
        }
        textPaint.textSize = 45f
        canvas.drawText("Tap to restart", width / 2f, height / 2f + 200f, textPaint)
        textPaint.textSize = 60f
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawScreamer(canvas: Canvas) {
        val flash = sin(screamTimer.toDouble() * 0.6) > 0
        canvas.drawColor(if (flash) Color.RED else Color.BLACK)
        val sx = Random.nextFloat() * 40f - 20f
        val sy = Random.nextFloat() * 40f - 20f
        canvas.save()
        canvas.translate(sx, sy)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.42f
        val facePaint = Paint().apply { color = Color.rgb(240, 240, 250); isAntiAlias = true }
        canvas.drawCircle(cx, cy, r, facePaint)
        canvas.drawRoundRect(cx - r * 0.6f, cy - r * 2.2f, cx - r * 0.2f, cy - r * 0.5f, 40f, 40f, facePaint)
        canvas.drawRoundRect(cx + r * 0.2f, cy - r * 2.2f, cx + r * 0.6f, cy - r * 0.5f, 40f, 40f, facePaint)
        val eyeRed = Paint().apply { color = Color.RED; isAntiAlias = true }
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.15f, r * 0.18f, eyeRed)
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.15f, r * 0.18f, eyeRed)
        val pupil = Paint().apply { color = Color.BLACK; isAntiAlias = true }
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.15f, r * 0.08f, pupil)
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.15f, r * 0.08f, pupil)
        canvas.drawRect(cx - r * 0.5f, cy + r * 0.3f, cx + r * 0.5f, cy + r * 0.55f, bloodPaint)
        val tooth = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        for (i in 0..5) {
            val tx = cx - r * 0.5f + i * (r * 0.2f)
            val path = android.graphics.Path().apply {
                moveTo(tx, cy + r * 0.3f)
                lineTo(tx + r * 0.1f, cy + r * 0.55f)
                lineTo(tx + r * 0.2f, cy + r * 0.3f)
                close()
            }
            canvas.drawPath(path, tooth)
        }
        canvas.drawText("!!!", cx, cy + r * 1.4f, bigText)
        canvas.restore()
    }

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
            eggs.add(Egg(Random.nextFloat() * (width - 2 * r) + r, -r,
                Random.nextFloat() * 6f + 14f, r))
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
        shakeX = Random.nextFloat() * 60f - 30f
        shakeY = Random.nextFloat() * 60f - 30f
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
