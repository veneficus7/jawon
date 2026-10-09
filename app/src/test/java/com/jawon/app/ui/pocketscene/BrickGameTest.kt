package com.jawon.app.ui.pocketscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrickGameTest {

    private fun BrickGame.run(seconds: Float, dt: Float = 1f / 60f, each: BrickGame.() -> Unit = {}) {
        var t = 0f
        while (t < seconds) {
            each()
            step(dt)
            t += dt
        }
    }

    @Test
    fun `the ball waits on the paddle until the centre is pressed, and follows it`() {
        val game = BrickGame()
        game.run(1f)
        assertEquals(BrickGame.Phase.READY, game.phase)
        game.movePaddle(-100f)
        assertEquals(game.paddleX, game.ballX, 1e-3f)
        assertTrue(game.ballY < game.paddleY)
    }

    @Test
    fun `served, it goes up and takes bricks for points`() {
        val game = BrickGame()
        game.press()
        assertEquals(BrickGame.Phase.PLAYING, game.phase)
        val y0 = game.ballY
        game.step(0.05f)
        assertTrue(game.ballY < y0)
        // Keep the paddle under the ball, and it must clear some of the wall.
        game.run(6f) { movePaddle(ballX - paddleX) }
        assertTrue(game.score > 0)
        assertTrue(game.bricks.count { !it } > 0)
    }

    @Test
    fun `the paddle never leaves the field`() {
        val game = BrickGame()
        game.movePaddle(-10_000f)
        assertEquals(BrickGame.PADDLE_W / 2f, game.paddleX, 1e-3f)
        game.movePaddle(10_000f)
        assertEquals(game.width - BrickGame.PADDLE_W / 2f, game.paddleX, 1e-3f)
    }

    @Test
    fun `a missed ball costs a life, and the last one ends the game`() {
        val game = BrickGame()
        repeat(BrickGame.START_LIVES) { n ->
            game.press()
            // Park the paddle as far from the ball as it goes, and wait.
            game.run(8f) { movePaddle(if (ballX < width / 2) 10_000f else -10_000f) }
            assertEquals(BrickGame.START_LIVES - n - 1, game.lives)
        }
        assertEquals(BrickGame.Phase.OVER, game.phase)
        // The centre starts again.
        game.press()
        assertEquals(BrickGame.Phase.READY, game.phase)
        assertEquals(BrickGame.START_LIVES, game.lives)
        assertEquals(0, game.score)
        assertTrue(game.bricks.all { it })
    }

    @Test
    fun `the ball stays inside the field, however long it runs`() {
        val game = BrickGame()
        game.press()
        game.run(30f) {
            movePaddle(ballX - paddleX)
            assertTrue(ballX >= BrickGame.BALL_R - 0.01f && ballX <= width - BrickGame.BALL_R + 0.01f)
            assertTrue(ballY >= BrickGame.BALL_R - 0.01f)
            if (phase == BrickGame.Phase.READY) press()
        }
    }

    @Test
    fun `a stalled frame does not carry the ball through a brick`() {
        val game = BrickGame()
        game.press()
        // One enormous frame: stepped as MAX_FRAME_S, in sub-steps.
        game.step(5f)
        assertTrue(game.ballY > 0f)
        assertEquals(BrickGame.Phase.PLAYING, game.phase)
    }

    @Test
    fun `the top rows are worth the most`() {
        val game = BrickGame()
        assertTrue(game.pointsFor(0) > game.pointsFor(BrickGame.ROWS - 1))
    }

    @Test
    fun `a lean steers only past its dead zone, and harder for a bigger lean`() {
        assertEquals(0f, BrickGame.leanSpeed(0.05f), 0f)
        assertTrue(BrickGame.leanSpeed(0.4f) > 0f)
        assertTrue(BrickGame.leanSpeed(-0.4f) < 0f)
        assertTrue(BrickGame.leanSpeed(0.9f) > BrickGame.leanSpeed(0.4f))
        assertEquals(BrickGame.PADDLE_LEAN_SPEED, BrickGame.leanSpeed(1f), 1e-3f)
    }

    @Test
    fun `clearing the wall is the next level, with a new wall and the ball back on the paddle`() {
        val game = BrickGame()
        // Everything down but one brick in the bottom row, and the ball rising into it.
        val row = BrickGame.ROWS - 1
        val col = 4
        for (i in game.bricks.indices) game.bricks[i] = i == row * BrickGame.COLS + col
        val x = BrickGame.SIDE_MARGIN + col * (game.brickW + BrickGame.GAP) + game.brickW / 2f
        val below = BrickGame.WALL_TOP + row * (BrickGame.BRICK_H + BrickGame.GAP) + BrickGame.BRICK_H + 20f
        game.placeBall(x, below, 0f, -300f)
        game.step(0.1f)
        assertEquals(2, game.level)
        assertTrue(game.bricks.all { it })
        assertEquals(BrickGame.Phase.READY, game.phase)
        assertEquals(game.pointsFor(row), game.score)
        assertFalse(game.lives < BrickGame.START_LIVES)
    }
}
