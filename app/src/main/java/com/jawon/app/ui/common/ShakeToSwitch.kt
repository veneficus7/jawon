package com.jawon.app.ui.common

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Calls [onShake] when the phone is shaken ([ShakeDetector]) — **while [enabled], the app is in
 * front, and the phone is not in a pocket.**
 *
 * The pocket is the proximity sensor: with something against the screen a shake is a walk or a
 * run, never a request, so it is ignored. Listening stops with the activity, so nothing is drawn
 * from the battery while Jawon is in the background.
 *
 * Linear acceleration where the phone has it (gravity already taken out by the platform's own
 * sensor fusion); a plain accelerometer with gravity filtered out where it does not.
 */
@Composable
fun ShakeToSwitch(enabled: Boolean, onShake: () -> Unit) {
    val context = LocalContext.current
    val currentOnShake by rememberUpdatedState(onShake)

    LifecycleResumeEffect(enabled, context) {
        val sensors = context.getSystemService(SensorManager::class.java)
        if (!enabled || sensors == null) return@LifecycleResumeEffect onPauseOrDispose { }

        val linear = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val motion = linear ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val proximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val detector = ShakeDetector()
        val gravity = FloatArray(3)
        var near = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_PROXIMITY -> {
                        near = event.values[0] < event.sensor.maximumRange
                        if (near) detector.reset()
                    }
                    else -> {
                        var x = event.values[0]
                        var y = event.values[1]
                        var z = event.values[2]
                        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                            // A slow low-pass is gravity; what is left is the hand.
                            gravity[0] += (x - gravity[0]) * GRAVITY_ALPHA
                            gravity[1] += (y - gravity[1]) * GRAVITY_ALPHA
                            gravity[2] += (z - gravity[2]) * GRAVITY_ALPHA
                            x -= gravity[0]; y -= gravity[1]; z -= gravity[2]
                        }
                        if (!near && detector.onSample(x, y, z, event.timestamp / 1_000_000L)) {
                            currentOnShake()
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        motion?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        proximity?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        onPauseOrDispose { sensors.unregisterListener(listener) }
    }
}

private const val GRAVITY_ALPHA = 0.1f
