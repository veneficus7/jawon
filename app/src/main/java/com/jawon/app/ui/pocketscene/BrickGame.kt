package com.jawon.app.ui.pocketscene

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * **Brick**, the classic's game (0.7.3): bat a ball into a wall of bricks with a paddle along the
 * bottom of the screen. The wheel moves the paddle, as it did on the classic, and so does leaning
 * the phone ([leanOf]) — the one thing the original could not do.
 *
 * Pure, and in the screen's own units: the field is the LCD under its title bar, [FIELD_W] by
 * [FIELD_H] pixels. The pocket room steps it from its frame clock and hands the screen a
 * [BrickFrame] to draw; nothing here knows about either.
 *
 * The ball is moved in sub-steps no longer than a few pixels, so a fast ball late in a level cannot
 * pass through a brick or the paddle between two frames.
 */
class BrickGame(
    val width: Float = FIELD_W,
    val height: Float = FIELD_H
) {
    enum class Phase {
        /** The ball sits on the paddle, waiting for the centre button. */
        READY,
        PLAYING,
        /** No balls left. The centre starts again. */
        OVER
    }

    var phase = Phase.READY
        private set
    var score = 0
        private set
    var lives = START_LIVES
        private set
    var level = 1
        private set

    /** The paddle's centre. */
    var paddleX = width / 2f
        private set
    var ballX = 0f
        private set
    var ballY = 0f
        private set
    private var vx = 0f
    private var vy = 0f

    /** Row-major, [ROWS] × [COLS]: true where a brick still stands. */
    val bricks = BooleanArray(ROWS * COLS)

    val brickW: Float get() = (width - 2 * SIDE_MARGIN - (COLS - 1) * GAP) / COLS
    val paddleY: Float get() = height - PADDLE_FROM_BOTTOM

    init {
        newWall()
        restBall()
    }

    // ---- the player's hands ----------------------------------------------------------------

    /** The wheel's steps, or a lean's worth of travel: moves the paddle, kept on the field. */
    fun movePaddle(dx: Float) {
        paddleX = (paddleX + dx).coerceIn(PADDLE_W / 2f, width - PADDLE_W / 2f)
        if (phase != Phase.PLAYING) restBall()
    }

    /** The centre button: serve the ball, or after the last one, a new game. */
    fun press() {
        when (phase) {
            Phase.READY -> serve()
            Phase.PLAYING -> Unit
            Phase.OVER -> {
                score = 0
                lives = START_LIVES
                level = 1
                newWall()
                phase = Phase.READY
                restBall()
            }
        }
    }

    // ---- time --------------------------------------------------------------------------------

    fun step(dtS: Float) {
        if (phase != Phase.PLAYING) return
        val dt = dtS.coerceIn(0f, MAX_FRAME_S)
        val speed = hypot(vx, vy)
        val distance = speed * dt
        val steps = (distance / MAX_SUBSTEP_PX).toInt() + 1
        val h = dt / steps
        repeat(steps) {
            if (phase == Phase.PLAYING) advance(h)
        }
    }

    private fun advance(h: Float) {
        ballX += vx * h
        ballY += vy * h

        // Walls: left, right, top.
        if (ballX < BALL_R) { ballX = BALL_R; vx = abs(vx) }
        if (ballX > width - BALL_R) { ballX = width - BALL_R; vx = -abs(vx) }
        if (ballY < BALL_R) { ballY = BALL_R; vy = abs(vy) }

        // The paddle: only on the way down, and only over its top face.
        val top = paddleY - PADDLE_H / 2f
        if (vy > 0f && ballY + BALL_R >= top && ballY - BALL_R <= top + PADDLE_H &&
            ballX >= paddleX - PADDLE_W / 2f - BALL_R && ballX <= paddleX + PADDLE_W / 2f + BALL_R
        ) {
            // Where it lands decides where it goes: the middle sends it straight up, the ends out
            // at up to MAX_BOUNCE_DEG — the classic's way of letting you aim.
            val offset = ((ballX - paddleX) / (PADDLE_W / 2f)).coerceIn(-1f, 1f)
            val angle = Math.toRadians((offset * MAX_BOUNCE_DEG).toDouble())
            val s = hypot(vx, vy)
            vx = (s * sin(angle)).toFloat()
            vy = -(s * cos(angle)).toFloat()
            ballY = top - BALL_R
        }

        hitBrick()

        // Missed.
        if (ballY - BALL_R > height) {
            lives -= 1
            if (lives <= 0) {
                lives = 0
                phase = Phase.OVER
            } else {
                phase = Phase.READY
            }
            restBall()
        }
    }

    private fun hitBrick() {
        val bw = brickW
        val col0 = ((ballX - BALL_R - SIDE_MARGIN) / (bw + GAP)).toInt().coerceIn(0, COLS - 1)
        val col1 = ((ballX + BALL_R - SIDE_MARGIN) / (bw + GAP)).toInt().coerceIn(0, COLS - 1)
        val row0 = ((ballY - BALL_R - WALL_TOP) / (BRICK_H + GAP)).toInt()
        val row1 = ((ballY + BALL_R - WALL_TOP) / (BRICK_H + GAP)).toInt()
        if (row1 < 0 || row0 >= ROWS) return
        for (row in row0.coerceAtLeast(0)..row1.coerceAtMost(ROWS - 1)) {
            for (col in col0..col1) {
                val i = row * COLS + col
                if (!bricks[i]) continue
                val left = SIDE_MARGIN + col * (bw + GAP)
                val top = WALL_TOP + row * (BRICK_H + GAP)
                // Overlap on each axis; the ball came in through the side it overlaps least.
                val overlapX = min(ballX + BALL_R - left, left + bw - (ballX - BALL_R))
                val overlapY = min(ballY + BALL_R - top, top + BRICK_H - (ballY - BALL_R))
                if (overlapX <= 0f || overlapY <= 0f) continue
                bricks[i] = false
                score += pointsFor(row)
                if (overlapX < overlapY) {
                    vx = if (ballX < left + bw / 2f) -abs(vx) else abs(vx)
                } else {
                    vy = if (ballY < top + BRICK_H / 2f) -abs(vy) else abs(vy)
                }
                // A little quicker with every brick, as the classic got.
                val s = hypot(vx, vy)
                val faster = (s * BRICK_SPEED_UP).coerceAtMost(maxSpeed())
                vx *= faster / s
                vy *= faster / s
                if (bricks.none { it }) {
                    level += 1
                    newWall()
                    phase = Phase.READY
                    restBall()
                }
                return
            }
        }
    }

    // ---- set-up --------------------------------------------------------------------------------

    private fun serve() {
        phase = Phase.PLAYING
        val s = startSpeed()
        // Up and a little to one side, never straight up: straight up from the middle of an empty
        // column is a ball that bounces in one place for ever.
        val angle = Math.toRadians(SERVE_DEG.toDouble())
        val side = if (paddleX < width / 2f) 1f else -1f
        vx = (s * sin(angle)).toFloat() * side
        vy = -(s * cos(angle)).toFloat()
    }

    private fun restBall() {
        ballX = paddleX
        ballY = paddleY - PADDLE_H / 2f - BALL_R - 1f
        vx = 0f
        vy = 0f
    }

    private fun newWall() {
        bricks.fill(true)
    }

    /** Puts the ball somewhere, moving — for tests that need one exact shot. */
    internal fun placeBall(x: Float, y: Float, velocityX: Float, velocityY: Float) {
        phase = Phase.PLAYING
        ballX = x
        ballY = y
        vx = velocityX
        vy = velocityY
    }

    private fun startSpeed(): Float = START_SPEED * (1f + (level - 1) * LEVEL_SPEED_UP)
    private fun maxSpeed(): Float = startSpeed() * 1.6f

    /** The top rows are worth the most, as the classic's were. */
    fun pointsFor(row: Int): Int = (ROWS - row) * 10

    /** What the screen draws, as it is now. */
    fun frame(): BrickFrame = BrickFrame(
        paddleX = paddleX,
        ballX = ballX,
        ballY = ballY,
        bricks = bricks.copyOf(),
        score = score,
        lives = lives,
        level = level,
        phase = phase
    )

    companion object {
        /** The LCD under its title bar: 640 wide, 475 − 54 tall. */
        const val FIELD_W = 640f
        const val FIELD_H = 421f

        const val ROWS = 6
        const val COLS = 10
        const val SIDE_MARGIN = 14f
        const val GAP = 5f
        const val BRICK_H = 18f
        /** Room above the wall for the score. */
        const val WALL_TOP = 46f

        const val PADDLE_W = 104f
        const val PADDLE_H = 12f
        const val PADDLE_FROM_BOTTOM = 26f
        const val BALL_R = 7f

        const val START_LIVES = 3
        /** Pixels a second: across the field in about two seconds. */
        const val START_SPEED = 330f
        const val LEVEL_SPEED_UP = 0.12f
        const val BRICK_SPEED_UP = 1.006f
        const val MAX_BOUNCE_DEG = 62f
        const val SERVE_DEG = 24f

        /** Longest frame stepped at once — a stalled frame is not a reason to teleport. */
        const val MAX_FRAME_S = 0.05f
        const val MAX_SUBSTEP_PX = 3f

        /** One click of the wheel moves the paddle this far. */
        const val PADDLE_PER_STEP = 24f
        /** A full lean moves it this fast, pixels a second. */
        const val PADDLE_LEAN_SPEED = 900f
        /** Leans smaller than this are a hand, not a steer. */
        const val LEAN_DEAD = 0.12f

        fun leanSpeed(lean: Float): Float {
            val a = abs(lean)
            if (a < LEAN_DEAD) return 0f
            val k = ((a - LEAN_DEAD) / (1f - LEAN_DEAD)).coerceIn(0f, 1f)
            // Squared, so a small lean is fine control and a big one gets you across.
            return kotlin.math.sign(lean) * PADDLE_LEAN_SPEED * k * k
        }
    }
}

/** One frame of Brick, for the screen. */
class BrickFrame(
    val paddleX: Float,
    val ballX: Float,
    val ballY: Float,
    val bricks: BooleanArray,
    val score: Int,
    val lives: Int,
    val level: Int,
    val phase: BrickGame.Phase,
    /** The best score this phone has seen, for the line along the top. */
    val best: Int = 0,
    /** Left mid-game: the ball waits for the centre. */
    val paused: Boolean = false
)
