//
//  CameraSource.kt
//  MoniVitals (Android)
//
//  Live fingertip measurement from the rear camera. Turns the torch on, runs a lightweight
//  per-frame analysis, and averages the red (and green) channel over a centered region of the
//  frame — one pulse (photoplethysmography) value per frame at ~30 fps. Values are inverted so
//  a heartbeat reads as an upward deflection downstream.
//
//  If there is no usable camera (for example on an emulator), connect() fails gracefully into
//  the friendly UNSUPPORTED state instead of crashing, so the app still launches everywhere.
//

package com.monivitals.source

import android.content.Context
import android.graphics.ImageFormat
import android.os.SystemClock
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.monivitals.dsp.DSPConstants
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Camera-based pulse source. Needs a [LifecycleOwner] (the Activity) to bind the CameraX use
 * case to, and the CAMERA runtime permission granted before [connect].
 */
class CameraSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : BaseDataSource() {

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var cameraControl: CameraControl? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "monivitals-camera") }

    /**
     * Set once [disconnect] runs. The provider future's listener checks it so a disconnect
     * that lands before the future fires can't leave the listener binding an ownerless
     * camera with the torch on. Volatile: disconnect() and the analyzer run on different
     * threads.
     */
    @Volatile private var disposed = false

    // Volatile: start()/stop() run on the caller (main) thread while the analyzer checks this
    // on the camera thread — without it the analyzer may never observe a stop.
    @Volatile private var running = false
    private var seq = 0

    // Frame batching: we emit a PpgPacket every BATCH frames so downstream plumbing works in
    // small blocks (like the old stream packets) instead of one event per frame.
    private val batchGreen = ArrayList<Double>(BATCH)
    private val batchRed = ArrayList<Double>(BATCH)
    // Real per-frame capture timestamps (µs), one per batched sample. Carried on the PpgPacket so
    // the recorder exports true frame times instead of a synthetic 30 fps grid (matches iOS).
    private val batchTimes = ArrayList<Double>(BATCH)
    private var batchStartUs = 0.0
    private var lastFrameUs = 0.0
    private var measuredFps = 30.0

    override fun connect() {
        setState(ConnectionState.CONNECTING)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                if (disposed) return@addListener
                val cp = future.get()
                provider = cp
                if (!hasRearCamera(cp)) {
                    setState(ConnectionState.UNSUPPORTED, ConnectionDetail("No rear camera on this phone."))
                    return@addListener
                }
                bind(cp)
            } catch (e: Exception) {
                // No usable camera (emulator, camera in use, etc.) — degrade warmly.
                setState(ConnectionState.UNSUPPORTED, ConnectionDetail("This needs a real phone camera."))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun hasRearCamera(cp: ProcessCameraProvider): Boolean = try {
        cp.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
    } catch (e: Exception) {
        false
    }

    private fun bind(cp: ProcessCameraProvider) {
        if (disposed) return
        try {
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis.setAnalyzer(analysisExecutor) { proxy -> onFrame(proxy) }
            cp.unbindAll()
            val camera = cp.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageAnalysis,
            )
            analysis = imageAnalysis
            cameraControl = camera.cameraControl
            // Torch on so the fingertip is trans-illuminated.
            try {
                camera.cameraControl.enableTorch(true)
            } catch (e: Exception) {
                // Some cameras lack a torch; keep going (perfusion under ambient light is weaker).
            }
            setState(ConnectionState.CONNECTED)
        } catch (e: Exception) {
            setState(ConnectionState.UNSUPPORTED, ConnectionDetail("This needs a real phone camera."))
        }
    }

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun disconnect() {
        disposed = true
        running = false
        try {
            cameraControl?.enableTorch(false)
        } catch (e: Exception) {
            // ignore
        }
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            // ignore
        }
        analysis = null
        cameraControl = null
        analysisExecutor.shutdown()
        setState(ConnectionState.DISCONNECTED)
    }

    private fun onFrame(proxy: ImageProxy) {
        try {
            if (running && !disposed) {
                // Skip (don't emit) frames we can't read — a 0.0 sample would poison the DSP.
                averageRoi(proxy)?.let { (redMean, greenMean) -> emit(redMean, greenMean) }
            }
        } catch (e: Exception) {
            // A single bad frame must never crash the pipeline.
        } finally {
            proxy.close()
        }
    }

    /**
     * Average the red + green channels over a centered ROI (~40% of the frame). ImageAnalysis
     * delivers YUV_420_888; we sample a grid of pixels in the ROI and convert each to RGB.
     * Returns null when the frame can't be read (unexpected format / plane layout) so the
     * caller can skip it instead of feeding zeros into the DSP.
     */
    private fun averageRoi(proxy: ImageProxy): Pair<Double, Double>? {
        if (proxy.format != ImageFormat.YUV_420_888 || proxy.planes.size < 3) {
            return null
        }
        val w = proxy.width
        val h = proxy.height
        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        // The Y plane is almost always tightly packed (pixelStride 1), but the spec doesn't
        // guarantee it — index stride-aware so exotic layouts still read the right pixel.
        val yPixelStride = yPlane.pixelStride
        if (yPixelStride < 1) return null
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        // Centered ROI covering ~40% of each dimension.
        val roiFrac = 0.4
        val x0 = ((0.5 - roiFrac / 2) * w).toInt().coerceIn(0, w - 1)
        val x1 = ((0.5 + roiFrac / 2) * w).toInt().coerceIn(0, w - 1)
        val y0 = ((0.5 - roiFrac / 2) * h).toInt().coerceIn(0, h - 1)
        val y1 = ((0.5 + roiFrac / 2) * h).toInt().coerceIn(0, h - 1)

        // Sample on a grid (~24×24) so per-frame cost stays tiny.
        val steps = 24
        val stepX = maxOf(1, (x1 - x0) / steps)
        val stepY = maxOf(1, (y1 - y0) / steps)

        var rSum = 0.0
        var gSum = 0.0
        var count = 0
        var yy = y0
        while (yy <= y1) {
            var xx = x0
            while (xx <= x1) {
                val yIndex = yy * yRowStride + xx * yPixelStride
                if (yIndex >= yBuf.limit()) { xx += stepX; continue }
                val yVal = (yBuf.get(yIndex).toInt() and 0xFF)
                val uvRow = (yy / 2) * uvRowStride
                val uvCol = (xx / 2) * uvPixelStride
                if (uvRow + uvCol >= uBuf.limit() || uvRow + uvCol >= vBuf.limit()) { xx += stepX; continue }
                val uVal = (uBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128
                val vVal = (vBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128
                // BT.601 YUV → RGB.
                val r = yVal + 1.402 * vVal
                val g = yVal - 0.344136 * uVal - 0.714136 * vVal
                rSum += r.coerceIn(0.0, 255.0)
                gSum += g.coerceIn(0.0, 255.0)
                count += 1
                xx += stepX
            }
            yy += stepY
        }
        if (count == 0) return null
        return (rSum / count) to (gSum / count)
    }

    private fun emit(redMean: Double, greenMean: Double) {
        // Monotonic timebase (µs since boot). Wall-clock (System.currentTimeMillis) can jump on
        // an NTP step, which would corrupt beat-to-beat intervals (RR) and therefore HRV.
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1000.0
        if (lastFrameUs > 0) {
            val instFps = DSPConstants.usPerS / (nowUs - lastFrameUs).coerceAtLeast(1.0)
            // Smooth the fps estimate.
            measuredFps = 0.9 * measuredFps + 0.1 * instFps.coerceIn(5.0, 60.0)
        }
        lastFrameUs = nowUs

        if (batchRed.isEmpty()) batchStartUs = nowUs
        // Invert so a pulse (which dips brightness slightly) reads as an upward deflection.
        batchRed.add(255.0 - redMean)
        batchGreen.add(255.0 - greenMean)
        batchTimes.add(nowUs)

        if (batchRed.size >= BATCH) {
            val fps = measuredFps.roundToInt().coerceIn(10, 60)
            val packet = PpgPacket(
                seq = seq++,
                tUs = batchStartUs,
                sampleRateHz = fps,
                green = batchGreen.toDoubleArray(),
                red = batchRed.toDoubleArray(),
                timesUs = batchTimes.toList(),
            )
            events.ppg.tryEmit(packet)
            batchRed.clear()
            batchGreen.clear()
            batchTimes.clear()
        }
    }

    companion object {
        private const val BATCH = 8
    }
}
