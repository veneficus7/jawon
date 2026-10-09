package com.jawon.app.ui.common

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.safeDestroy

/**
 * A Filament engine for one room, **and a GL context that is actually freed when the room goes.**
 *
 * SceneView's `rememberEngine` (4.32) makes its context with a 1×1 pbuffer surface and makes it
 * current on the main thread — and on the way out only calls `eglDestroyContext`. It never makes
 * the context not current and never destroys the pbuffer. EGL defers deleting a context that is
 * still current, and the surface is simply lost, so every room that came and went left its
 * context's share group — every texture and buffer the room had uploaded — behind.
 *
 * Measured on the owner's S25 Ultra (2026-10-10, `dumpsys meminfo`): six switches between the CD
 * room and the pocket player took GL memory from 452 to 602MB, ~25MB a room, and each trip
 * through the showroom ~30MB more. Every visit to Settings did the same in 0.7.1, when the room
 * was still rebuilt behind every panel.
 *
 * This is SceneView's own construction with the teardown completed: the engine first (it waits
 * for its driver thread and frees what it owns), then the context released from this thread,
 * then the surface, then the context itself.
 */
@Composable
fun rememberRoomEngine(): Engine {
    val gl = remember { RoomGl.create() }
    val engine = remember(gl) { Engine.create(gl.context) }
    DisposableEffect(gl, engine) {
        onDispose {
            engine.safeDestroy()
            gl.release()
        }
    }
    return engine
}

/** The context a room's engine shares, and the pbuffer it is made current with. */
private class RoomGl(
    private val display: EGLDisplay,
    val context: EGLContext,
    private val surface: EGLSurface
) {
    private var released = false

    fun release() {
        if (released) return
        released = true
        // Not current any more, or EGL keeps it — and everything shared with it — alive.
        if (EGL14.eglGetCurrentContext() == context) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
        }
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
    }

    companion object {
        private const val EGL_OPENGL_ES3_BIT = 0x40

        /** SceneView's `OpenGL.createEglContext`, keeping hold of the pbuffer it makes. */
        fun create(): RoomGl {
            // Filament's native libraries, as SceneView's own createEglContext loads them first —
            // without this the first Engine.create is an UnsatisfiedLinkError on launch (trap 23's
            // family: found by the phone, not by a single one of the unit tests). Idempotent.
            Gltfio.init()
            Filament.init()
            Utils.init()
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(display, null, 0, null, 0)
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = intArrayOf(0)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_NONE), 0,
                configs, 0, 1, count, 0
            )
            val context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed" }
            val surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "Error making GL context current"
            }
            return RoomGl(display, context, surface)
        }
    }
}
