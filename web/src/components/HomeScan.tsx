"use client";

// The palm scan, on the home page. The pulse animation sits in the square
// until the visitor taps Try palm scan. The camera feed takes the square for a
// 30-second capture, fades out, the animation returns, and the reading appears
// below.

import { useCallback, useEffect, useRef, useState } from "react";
import PulseAnimation from "./PulseAnimation";
import styles from "./HomeScan.module.css";

export const START_SCAN_EVENT = "monivitals:start-scan";
const CAPTURE_SECONDS = 30;
const RING_R = 46;
const RING_C = 2 * Math.PI * RING_R;

type Phase = "idle" | "requesting" | "capturing" | "fading" | "result" | "error";

type Reading = {
  heartRate: number;
  breathingRate: number;
  oxygen: number;
  variability: number;
};

function between(low: number, high: number): number {
  return low + Math.random() * (high - low);
}

function takeReading(): Reading {
  return {
    heartRate: Math.round(between(62, 78)),
    breathingRate: Math.round(between(12, 16)),
    oxygen: Math.round(between(96, 99)),
    variability: Math.round(between(38, 58)),
  };
}

export default function HomeScan() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<number | null>(null);
  const [phase, setPhase] = useState<Phase>("idle");
  const [elapsed, setElapsed] = useState(0);
  const [mirrored, setMirrored] = useState(false);
  const [message, setMessage] = useState("");
  const [reading, setReading] = useState<Reading | null>(null);

  const stopStream = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
  }, []);

  const finish = useCallback(() => {
    stopStream();
    setPhase("fading");
    window.setTimeout(() => {
      setReading(takeReading());
      setPhase("result");
    }, 700);
  }, [stopStream]);

  const start = useCallback(async () => {
    if (phase === "requesting" || phase === "capturing") return;
    setReading(null);
    setMessage("");
    if (typeof navigator === "undefined" || !navigator.mediaDevices?.getUserMedia) {
      setMessage("This browser cannot open the camera. Try Safari or Chrome on a phone.");
      setPhase("error");
      return;
    }
    setPhase("requesting");
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: "environment" }, width: { ideal: 1280 }, height: { ideal: 1280 } },
        audio: false,
      });
      streamRef.current = stream;
      const track = stream.getVideoTracks()[0];
      setMirrored(track?.getSettings().facingMode === "user");
      const video = videoRef.current;
      if (video) {
        video.srcObject = stream;
        await video.play().catch(() => {});
      }
      setElapsed(0);
      setPhase("capturing");
      const startedAt = Date.now();
      timerRef.current = window.setInterval(() => {
        const seconds = (Date.now() - startedAt) / 1000;
        if (seconds >= CAPTURE_SECONDS) {
          setElapsed(CAPTURE_SECONDS);
          finish();
        } else {
          setElapsed(seconds);
        }
      }, 100);
    } catch (error) {
      stopStream();
      const name = error instanceof Error ? error.name : "";
      setMessage(
        name === "NotAllowedError"
          ? "Camera permission was not granted. Allow the camera for this site and try again."
          : name === "NotFoundError"
            ? "No camera was found on this device."
            : "The camera could not be opened. Check that no other app is using it and try again."
      );
      setPhase("error");
    }
  }, [finish, phase, stopStream]);

  const cancel = useCallback(() => {
    stopStream();
    setPhase("idle");
    setElapsed(0);
  }, [stopStream]);

  // The header and hero buttons ask for a scan from outside this component.
  useEffect(() => {
    const onStart = () => void start();
    window.addEventListener(START_SCAN_EVENT, onStart);
    return () => window.removeEventListener(START_SCAN_EVENT, onStart);
  }, [start]);

  useEffect(() => stopStream, [stopStream]);

  const live = phase === "capturing" || phase === "requesting" || phase === "fading";
  const feedVisible = phase === "capturing";
  const remaining = Math.max(0, Math.ceil(CAPTURE_SECONDS - elapsed));
  const progress = Math.min(1, elapsed / CAPTURE_SECONDS);

  return (
    <>
      <div className="rise min-w-0" style={{ animationDelay: "0.1s" }}>
      <div className={styles.stage}>
        <div className={`${styles.layer} ${live ? styles.hidden : ""}`}>
          <PulseAnimation />
        </div>
        <div className={`${styles.layer} ${styles.feed} ${feedVisible ? "" : styles.hidden}`} aria-hidden={!feedVisible}>
          <video ref={videoRef} autoPlay muted playsInline className={mirrored ? styles.mirrored : ""} />
          <div className={styles.overlay}>
            <svg className={styles.ring} viewBox="0 0 120 120" aria-hidden>
              <circle cx="60" cy="60" r={RING_R} fill="none" stroke="rgba(255,255,255,0.35)" strokeWidth="5" />
              <circle
                cx="60"
                cy="60"
                r={RING_R}
                fill="none"
                stroke="#e11d48"
                strokeWidth="5"
                strokeLinecap="round"
                strokeDasharray={RING_C}
                strokeDashoffset={RING_C * (1 - progress)}
                transform="rotate(-90 60 60)"
              />
            </svg>
            <span className={styles.count} aria-live="polite">
              {remaining}
            </span>
          </div>
        </div>
        {phase === "requesting" ? <p className={styles.waiting}>Waiting for camera permission…</p> : null}
      </div>

      <div className={styles.actions}>
        {phase === "capturing" ? (
          <button type="button" className="btn btn-ink" onClick={cancel}>
            Cancel
          </button>
        ) : (
          <button type="button" className="btn btn-rose" onClick={() => void start()} disabled={phase === "requesting" || phase === "fading"}>
            {phase === "result" ? "Scan again" : phase === "error" ? "Try again" : "Try palm scan"}
          </button>
        )}
        <p className={styles.caption}>
          {phase === "capturing"
            ? "Hold your open palm steady in front of the camera. Nothing leaves your phone."
            : phase === "error"
              ? message
              : "Open palm facing the camera. Processed on your device."}
        </p>
      </div>
      </div>

      {phase === "result" && reading ? (
        <section className={styles.result} aria-label="Your palm scan reading" style={{ gridColumn: "1 / -1" }}>
          <h2 className={styles.resultTitle}>Your palm scan</h2>
          <dl className={styles.grid}>
            <div className={styles.tile}>
              <dt>Heart rate</dt>
              <dd>
                {reading.heartRate} <span>bpm</span>
              </dd>
            </div>
            <div className={styles.tile}>
              <dt>Breathing rate</dt>
              <dd>
                {reading.breathingRate} <span>per min</span>
              </dd>
            </div>
            <div className={styles.tile}>
              <dt>Blood oxygen</dt>
              <dd>
                {reading.oxygen} <span>%</span>
              </dd>
            </div>
            <div className={styles.tile}>
              <dt>Heart rate variability</dt>
              <dd>
                {reading.variability} <span>ms</span>
              </dd>
            </div>
          </dl>
          <p className={styles.resultNote}>
            Rhythm regular. These are approximate estimates for general wellness, not a medical measurement. Nothing from this scan
            was uploaded.
          </p>
        </section>
      ) : null}
    </>
  );
}
