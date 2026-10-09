//
//  MotionMonitor.kt
//  MoniVitals (Android)
//
//  Reads the phone's accelerometer and publishes a linear-acceleration magnitude (gravity
//  removed) as a movement signal. Used to flag when the hand is moving so a reading can be
//  taken while still.
//

package com.monivitals.source

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.monivitals.dsp.DSPConstants
import kotlin.math.sqrt

class MotionMonitor(context: Context, private val onSample: (MotionSample) -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    // Prefer TYPE_LINEAR_ACCELERATION (gravity already removed); fall back to raw accel.
    private val linear: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val raw: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val usingLinear = linear != null

    fun start() {
        val sensor = linear ?: raw ?: return
        // ~50 Hz.
        sensorManager?.registerListener(this, sensor, 20_000)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        var mag = sqrt((x * x + y * y + z * z).toDouble())
        // Raw accelerometer includes ~9.81 of gravity; subtract it so a still phone reads ~0.
        if (!usingLinear) mag = kotlin.math.abs(mag - SensorManager.GRAVITY_EARTH)
        val tUs = System.currentTimeMillis() * 1000.0
        onSample(MotionSample(tUs = tUs, magnitude = mag))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        /** Sanity constant kept beside the sensitivity default. */
        val defaultMotionThresh = DSPConstants.motionThresh
    }
}
