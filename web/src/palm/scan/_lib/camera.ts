import { analyzeCamera, cameraCrop, CAPTURE_SECONDS, samplePixels, signalMessages, type Analysis, type CameraSample } from "./ppg";
import { PalmTracker, smoothCrop, type PalmCrop, type PalmDetection, type TrackingState } from "./hand";

export type ScanPhase = "idle" | "requesting" | "preview" | "capturing" | "complete" | "error";
export interface ScanView {
  phase: ScanPhase;
  seconds: number;
  analysis: Analysis | null;
  message: string;
  lighting: "Waiting" | "Good" | "Adjust";
  movement: "Waiting" | "Steady" | "Hold still";
  fps: number;
  cameras: { id: string; label: string }[];
  mirrored: boolean;
  tracking: TrackingState;
  palm: PalmDetection | null;
  crop: PalmCrop | null;
  videoSize: { width: number; height: number } | null;
}
export const initialView: ScanView = {
  phase: "idle", seconds: 0, analysis: null,
  message: "Start with your palm facing the camera in bright, even light.",
  lighting: "Waiting", movement: "Waiting", fps: 0, cameras: [], mirrored: true,
  tracking: "loading", palm: null, crop: null, videoSize: null,
};

export function cameraError(error: unknown) {
  const name = error instanceof Error ? error.name : "";
  if (name === "NotAllowedError" || name === "SecurityError") return "Camera access was blocked. Allow camera access in your browser settings, then try again.";
  if (name === "NotFoundError" || name === "OverconstrainedError") return "That camera is unavailable. Connect a camera or choose a different one.";
  if (name === "NotReadableError" || name === "AbortError") return "The camera could not start. Close other apps using it, then try again.";
  return "The camera could not deliver video. Try a different camera or reload this page.";
}

/** Owns exactly one stream and its callbacks. Raw video is never stored or sent. */
export class CameraScanner {
  private view = { ...initialView };
  private stream: MediaStream | null = null;
  private canvas: HTMLCanvasElement;
  private context: CanvasRenderingContext2D | null;
  private previous?: Uint8ClampedArray;
  private previewSamples: CameraSample[] = [];
  private samples: CameraSample[] = [];
  private generation = 0;
  private disposed = false;
  private frameId = 0;
  private animationId = 0;
  private watchdog: ReturnType<typeof setInterval> | null = null;
  private lastMediaTime = -1;
  private lastPresentedFrame = -1;
  private lastFrameAt = 0;
  private lastUpdateAt = 0;
  private captureStart: number | null = null;
  private listeners = new AbortController();
  private streamListeners: AbortController | null = null;
  private resultAt: string | null = null;
  private tracker: PalmTracker | null = null;
  private trackerStarted = false;
  private trackingState: TrackingState = "loading";
  private smoothedCrop: PalmCrop | null = null;
  private frozenCrop: PalmCrop | null = null;
  private palmSeenAt = -1;
  private lastDetection: PalmDetection | null = null;
  private lastDetectAt = 0;
  private lastOverlayAt = 0;
  private captureCropSource: "hand_tracking" | "manual" = "manual";
  private rafFrameCount = 0;

  constructor(private video: HTMLVideoElement, private onChange: (view: ScanView) => void) {
    this.canvas = document.createElement("canvas");
    this.canvas.width = this.canvas.height = 40;
    this.context = this.canvas.getContext("2d", { willReadFrequently: true });
    document.addEventListener("visibilitychange", () => {
      if (document.hidden && ["preview", "capturing", "requesting"].includes(this.view.phase)) this.fail("Scan stopped when this tab became hidden. Return to this page and start again.");
    }, { signal: this.listeners.signal });
    window.addEventListener("pagehide", () => this.stop(), { signal: this.listeners.signal });
  }

  private update(patch: Partial<ScanView>) {
    this.view = { ...this.view, ...patch };
    if (!this.disposed) this.onChange(this.view);
  }

  private release() {
    this.generation++;
    this.streamListeners?.abort();
    this.streamListeners = null;
    if (this.frameId) this.video.cancelVideoFrameCallback?.(this.frameId);
    if (this.animationId) cancelAnimationFrame(this.animationId);
    if (this.watchdog) clearInterval(this.watchdog);
    this.frameId = this.animationId = 0;
    this.watchdog = null;
    this.stream?.getTracks().forEach(track => track.stop());
    this.stream = null;
    this.video.pause();
    this.video.srcObject = null;
    this.previous = undefined;
    this.previewSamples = [];
    this.captureStart = null;
    this.lastMediaTime = this.lastPresentedFrame = -1;
    this.smoothedCrop = null;
    this.frozenCrop = null;
    this.palmSeenAt = -1;
    this.lastDetection = null;
  }

  /** Loads the MediaPipe hand landmarker once, on first camera use. Failure is
   * not an error: the scan falls back to the manually positioned crop. */
  private ensureTracker() {
    if (this.trackerStarted) return;
    this.trackerStarted = true;
    // A stalled wasm/model fetch resolves neither then nor catch; fall the UI
    // back to "off" after 10s so the tracking chip can't hang on "loading".
    let settled = false;
    const timer = setTimeout(() => {
      if (settled || this.disposed) return;
      this.trackingState = "off";
      this.update({ tracking: "off" });
    }, 10000);
    PalmTracker.create().then(tracker => {
      settled = true; clearTimeout(timer);
      if (this.disposed) { tracker.close(); return; }
      this.tracker = tracker;
      this.trackingState = "on";
      this.update({ tracking: "on" });
    }).catch(() => {
      settled = true; clearTimeout(timer);
      this.trackingState = "off";
      if (!this.disposed) this.update({ tracking: "off" });
    });
  }

  async open(camera = "user") {
    if (this.disposed) return;
    this.ensureTracker();
    this.release();
    this.samples = [];
    this.resultAt = null;
    const token = this.generation;
    this.update({ ...initialView, cameras: this.view.cameras, tracking: this.trackingState, phase: "requesting", message: "Allow camera access to see your palm. Audio is never requested." });
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      this.fail("Camera access needs HTTPS or localhost. Open this page securely in a current browser.");
      return;
    }
    if (!this.context || (!this.video.requestVideoFrameCallback && !this.video.getVideoPlaybackQuality)) {
      this.fail("This browser cannot read individual video frames. Use a current version of Safari, Chrome, Edge or Firefox.");
      return;
    }
    try {
      const choice: MediaTrackConstraints = camera === "user" || camera === "environment" ? { facingMode: { ideal: camera } } : { deviceId: { exact: camera } };
      const stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: { ...choice, width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 60, min: 20 } } });
      if (this.disposed || token !== this.generation) { stream.getTracks().forEach(track => track.stop()); return; }
      this.stream = stream;
      this.streamListeners = new AbortController();
      const track = stream.getVideoTracks()[0];
      if (!track) { this.fail("The camera did not provide a video track. Choose another camera."); return; }
      const signal = this.streamListeners.signal;
      track.addEventListener("ended", () => this.fail("The camera disconnected. Reconnect it and start again."), { signal });
      track.addEventListener("mute", () => this.fail("The camera was interrupted. Close other camera apps and start again."), { signal });
      this.video.srcObject = stream;
      this.lastFrameAt = performance.now();
      this.watchdog = setInterval(() => {
        if (performance.now() - this.lastFrameAt > 6000) this.fail("Video frames stopped arriving. Check your camera and start again.");
      }, 1000);
      await this.video.play();
      if (this.disposed || token !== this.generation) return;
      const settings = track.getSettings();
      this.update({ phase: "preview", mirrored: settings.facingMode ? settings.facingMode === "user" : camera !== "environment", message: "Fill the square with your bare palm. Rest your wrist and keep the camera still." });
      this.queueFrame(token);
      navigator.mediaDevices.enumerateDevices().then(devices => {
        if (!this.disposed && token === this.generation) this.update({ cameras: devices.filter(d => d.kind === "videoinput").map((d, i) => ({ id: d.deviceId, label: d.label || `Camera ${i + 1}` })) });
      }).catch(() => { /* Selection by facing mode remains available. */ });
    } catch (error) {
      if (!this.disposed && token === this.generation) this.fail(cameraError(error));
    }
  }

  private queueFrame(token: number) {
    if (this.disposed || token !== this.generation) return;
    if (this.video.requestVideoFrameCallback) {
      this.frameId = this.video.requestVideoFrameCallback((now, metadata) => {
        this.frame(now, metadata.mediaTime, metadata.presentedFrames);
        this.queueFrame(token);
      });
    } else {
      this.animationId = requestAnimationFrame(now => {
        const quality = this.video.getVideoPlaybackQuality?.();
        this.frame(now, this.video.currentTime, quality ? quality.totalVideoFrames : ++this.rafFrameCount);
        this.queueFrame(token);
      });
    }
  }

  private frame(now: number, mediaTime: number, presentedFrames: number) {
    if (!this.context || this.video.readyState < 2 || !this.video.videoWidth || this.lastPresentedFrame === presentedFrames) return;
    if (mediaTime <= this.lastMediaTime) return;
    if (this.view.phase === "capturing" && (mediaTime - this.lastMediaTime > .3 || now - this.lastFrameAt > 600)) {
      this.fail("The video was interrupted during capture. Keep this tab open and start a new scan.");
      return;
    }
    this.lastMediaTime = mediaTime;
    this.lastPresentedFrame = presentedFrames;
    this.lastFrameAt = now;
    const width = this.video.videoWidth, height = this.video.videoHeight;
    // Detection only feeds the live-preview crop. During capture the crop is
    // frozen (see below), so skip inference entirely: it saves a synchronous
    // detect per frame, keeps the landmark overlay pinned to the frozen square
    // instead of drifting, and avoids a slow CPU-delegate detect (>600ms)
    // tripping the capture-interrupted watchdog and aborting a valid scan.
    if (this.view.phase !== "capturing") {
      if (this.tracker && now - this.lastDetectAt >= 66) {
        this.lastDetectAt = now;
        try {
          const detection = this.tracker.detect(this.video, now);
          if (detection) {
            this.smoothedCrop = smoothCrop(this.smoothedCrop, detection.crop);
            this.palmSeenAt = now;
            this.lastDetection = detection;
          }
        } catch { /* Tracking is optional; the manual crop keeps the scan usable. */ }
      }
      if (this.lastDetection && now - this.palmSeenAt > 600) { this.lastDetection = null; this.smoothedCrop = null; }
    }
    // During capture the crop is frozen so crop movement never masquerades as signal.
    const tracked = this.view.phase === "capturing" ? this.frozenCrop : this.smoothedCrop;
    const crop = tracked ?? cameraCrop(width, height);
    let sample: CameraSample;
    try {
      // Preserve pixel variation before averaging. Smoothing into a tiny canvas
      // can quantize a sub-percent signal away before the RGB mean is taken.
      // Size is quantized to 16px steps so a tracked crop drifting by a pixel
      // does not resize the canvas and reset the motion baseline every frame.
      const size = Math.min(256, Math.max(16, Math.round(crop.size / 16) * 16));
      if (this.canvas.width !== size) {
        this.canvas.width = this.canvas.height = size;
        this.context.imageSmoothingEnabled = false;
        this.previous = undefined;
      }
      this.context.drawImage(this.video, crop.x, crop.y, crop.size, crop.size, 0, 0, size, size);
      const pixels = this.context.getImageData(0, 0, size, size).data;
      sample = samplePixels(pixels, mediaTime, this.previous);
      this.previous = pixels;
    } catch {
      this.fail("The browser could not read this camera frame. Try another camera or browser.");
      return;
    }
    this.previewSamples.push(sample);
    this.previewSamples = this.previewSamples.filter(s => mediaTime - s.time <= 2);
    if (now - this.lastOverlayAt >= 120) {
      this.lastOverlayAt = now;
      this.update({ palm: this.lastDetection, crop, videoSize: { width, height } });
    }
    if (this.view.phase === "capturing") {
      if (this.captureStart === null) this.captureStart = mediaTime;
      this.samples.push({ ...sample, time: mediaTime - this.captureStart });
      if (mediaTime - this.captureStart >= CAPTURE_SECONDS) { this.finish(); return; }
    }
    if (now - this.lastUpdateAt < 500) return;
    this.lastUpdateAt = now;
    const recent = this.previewSamples;
    const fps = recent.length > 1 ? (recent.length - 1) / (recent[recent.length - 1].time - recent[0].time) : 0;
    const averageMotion = recent.reduce((sum, s) => sum + s.motion, 0) / recent.length;
    const analysis = this.view.phase === "capturing" ? analyzeCamera(this.samples.slice(-30 * (CAPTURE_SECONDS + 5))) : null;
    this.update({
      seconds: this.captureStart === null ? 0 : mediaTime - this.captureStart,
      analysis, fps,
      lighting: sample.g >= 25 && sample.g <= 235 && sample.clipped <= .08 ? "Good" : "Adjust",
      movement: averageMotion < .035 ? "Steady" : "Hold still",
      ...(analysis ? { message: signalMessages[analysis.issue] } : {}),
    });
  }

  begin() {
    if (this.view.phase !== "preview" || !this.stream || !this.previewSamples.length) return;
    this.samples = [];
    this.captureStart = null;
    const tracked = this.smoothedCrop && this.lastFrameAt - this.palmSeenAt <= 600 ? { ...this.smoothedCrop } : null;
    this.frozenCrop = tracked;
    this.captureCropSource = tracked ? "hand_tracking" : "manual";
    this.update({ phase: "capturing", seconds: 0, analysis: null, message: "Collecting color changes. Keep your palm still for 30 seconds." });
  }

  private finish() {
    const analysis = analyzeCamera(this.samples);
    this.resultAt = new Date().toISOString();
    this.release();
    this.update({ phase: "complete", seconds: CAPTURE_SECONDS, analysis, fps: analysis.fps, message: analysis.bpm === null ? signalMessages[analysis.issue] : "Capture complete. This is an experimental pulse-rate estimate from your video." });
  }

  private fail(message: string) {
    this.release();
    this.samples = [];
    this.resultAt = null;
    this.update({ phase: "error", analysis: null, seconds: 0, fps: 0, lighting: "Waiting", movement: "Waiting", palm: null, crop: null, message });
  }

  stop() {
    this.release();
    this.samples = [];
    this.resultAt = null;
    this.update({ ...initialView, cameras: this.view.cameras, tracking: this.trackingState, message: "Camera off. Start a new scan whenever you are ready." });
  }

  exportCapture() {
    if (this.view.phase !== "complete") return null;
    return {
      version: "palm-camera-ppg-1", source: "camera", capturedAt: this.resultAt,
      status: this.view.analysis?.bpm === null ? "insufficient_signal" : "experimental_estimate",
      pulseRateEstimateBpm: this.view.analysis?.bpm ?? null,
      durationSeconds: this.view.analysis?.seconds, observedFramesPerSecond: this.view.analysis?.fps,
      note: `Experimental, not clinically validated. Do not use for medical decisions. Video is not included. Color samples are from a ${this.captureCropSource === "hand_tracking" ? "palm crop positioned by on-device hand tracking (Google MediaPipe Hand Landmarker)" : "manually positioned palm crop"}.`,
      cropSource: this.captureCropSource,
      samples: this.samples,
    };
  }

  dispose() {
    this.disposed = true;
    this.release();
    this.listeners.abort();
    this.samples = [];
    this.tracker?.close();
    this.tracker = null;
  }
}
