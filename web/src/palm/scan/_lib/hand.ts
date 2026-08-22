/** On-device palm tracking with Google MediaPipe Hand Landmarker.
 * Wasm runtime and model are served from this site's /public/mediapipe folder,
 * so tracking adds no third-party requests and no frames leave the browser.
 * Landmark indices: 0 wrist, 1 thumb CMC, 5/9/13/17 index/middle/ring/pinky MCP.
 */
import type { HandLandmarker } from "@mediapipe/tasks-vision";

export type TrackingState = "loading" | "on" | "off";
export interface PalmPoint { x: number; y: number }
export interface PalmCrop { x: number; y: number; size: number }
export interface PalmDetection { crop: PalmCrop; landmarks: PalmPoint[] }

const WASM_PATH = "/mediapipe/wasm";
const MODEL_PATH = "/mediapipe/hand_landmarker.task";
/** Wrist plus the knuckle line — the palm surface the crop should cover. */
const PALM_ANCHORS = [0, 1, 5, 9, 13, 17] as const;

export function clampCrop(crop: PalmCrop, width: number, height: number): PalmCrop {
  const size = Math.min(crop.size, width, height);
  return {
    x: Math.min(Math.max(crop.x, 0), width - size),
    y: Math.min(Math.max(crop.y, 0), height - size),
    size,
  };
}

/** Square over the palm center from normalized landmarks; null when the hand is
 * too far away for the crop to hold a usable pixel sample. */
export function palmCropFromLandmarks(landmarks: readonly PalmPoint[], width: number, height: number): PalmCrop | null {
  if (landmarks.length < 18 || width <= 0 || height <= 0) return null;
  let cx = 0, cy = 0;
  for (const index of PALM_ANCHORS) { cx += landmarks[index].x; cy += landmarks[index].y; }
  cx = cx / PALM_ANCHORS.length * width;
  cy = cy / PALM_ANCHORS.length * height;
  const size = Math.hypot((landmarks[9].x - landmarks[0].x) * width, (landmarks[9].y - landmarks[0].y) * height) * 1.15;
  if (size < 32) return null;
  return clampCrop({ x: cx - size / 2, y: cy - size / 2, size }, width, height);
}

/** Exponential smoothing keeps the crop from jittering with per-frame landmark noise. */
export function smoothCrop(previous: PalmCrop | null, next: PalmCrop, alpha = 0.25): PalmCrop {
  if (!previous) return next;
  return {
    x: previous.x + (next.x - previous.x) * alpha,
    y: previous.y + (next.y - previous.y) * alpha,
    size: previous.size + (next.size - previous.size) * alpha,
  };
}

export interface FramePercentRect { left: number; top: number; width: number; height: number }

/** Maps a crop in video pixels into percent offsets of the 4:3 object-fit:cover
 * camera frame, mirroring horizontally to match the mirrored preview. */
export function cropToFramePercent(crop: PalmCrop, videoWidth: number, videoHeight: number, mirrored: boolean): FramePercentRect {
  const visibleWidth = Math.min(videoWidth, videoHeight * 4 / 3);
  const visibleHeight = Math.min(videoHeight, videoWidth * 3 / 4);
  if (visibleWidth <= 0 || visibleHeight <= 0) return { left: 0, top: 0, width: 0, height: 0 };
  const offsetX = (videoWidth - visibleWidth) / 2;
  const offsetY = (videoHeight - visibleHeight) / 2;
  const width = crop.size / visibleWidth * 100;
  const left = (crop.x - offsetX) / visibleWidth * 100;
  return {
    left: mirrored ? 100 - left - width : left,
    top: (crop.y - offsetY) / visibleHeight * 100,
    width,
    height: crop.size / visibleHeight * 100,
  };
}

/** Same mapping for a single normalized landmark. */
export function pointToFramePercent(point: PalmPoint, videoWidth: number, videoHeight: number, mirrored: boolean): PalmPoint {
  const visibleWidth = Math.min(videoWidth, videoHeight * 4 / 3);
  const visibleHeight = Math.min(videoHeight, videoWidth * 3 / 4);
  if (visibleWidth <= 0 || visibleHeight <= 0) return { x: 0, y: 0 };
  const x = (point.x * videoWidth - (videoWidth - visibleWidth) / 2) / visibleWidth * 100;
  return {
    x: mirrored ? 100 - x : x,
    y: (point.y * videoHeight - (videoHeight - visibleHeight) / 2) / visibleHeight * 100,
  };
}

/** Owns one HandLandmarker in VIDEO mode. Created lazily when the camera opens;
 * any failure (unsupported browser, blocked wasm) leaves the manual crop in charge. */
export class PalmTracker {
  private landmarker: HandLandmarker | null = null;

  static async create(): Promise<PalmTracker> {
    const { FilesetResolver, HandLandmarker } = await import("@mediapipe/tasks-vision");
    const fileset = await FilesetResolver.forVisionTasks(WASM_PATH);
    const options = (delegate: "GPU" | "CPU") => ({
      baseOptions: { modelAssetPath: MODEL_PATH, delegate },
      runningMode: "VIDEO" as const,
      numHands: 1,
    });
    const tracker = new PalmTracker();
    try {
      tracker.landmarker = await HandLandmarker.createFromOptions(fileset, options("GPU"));
    } catch {
      tracker.landmarker = await HandLandmarker.createFromOptions(fileset, options("CPU"));
    }
    return tracker;
  }

  /** timestampMs must increase monotonically (performance.now of the frame callback). */
  detect(video: HTMLVideoElement, timestampMs: number): PalmDetection | null {
    if (!this.landmarker) return null;
    const landmarks = this.landmarker.detectForVideo(video, timestampMs).landmarks?.[0];
    if (!landmarks?.length) return null;
    const points = landmarks.map(l => ({ x: l.x, y: l.y }));
    const crop = palmCropFromLandmarks(points, video.videoWidth, video.videoHeight);
    return crop ? { crop, landmarks: points } : null;
  }

  close() {
    this.landmarker?.close();
    this.landmarker = null;
  }
}
