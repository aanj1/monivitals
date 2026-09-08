'use strict';
const path = require('node:path');
const { test } = require('node:test');
const assert = require('node:assert');

const build = process.env.MONIVITALS_TEST_BUILD;
const { analyzeCamera, samplePixels, cameraCrop, CAPTURE_SECONDS } = require(path.join(build, 'palm/scan/_lib/ppg.js'));
const { clampCrop, palmCropFromLandmarks, smoothCrop, cropToFramePercent } = require(path.join(build, 'palm/scan/_lib/hand.js'));
const { captureQuality } = require(path.join(build, 'palm/scan/_lib/quality.js'));

const FPS = 30;

/** A clean synthetic camera trace at a known pulse rate. */
function trace(bpm, seconds = CAPTURE_SECONDS, { motion = 0, clipped = 0 } = {}) {
  const samples = [];
  for (let i = 0; i < seconds * FPS; i++) {
    const t = i / FPS;
    const pulse = Math.sin(2 * Math.PI * (bpm / 60) * t);
    samples.push({
      time: t,
      r: 150 + pulse * 0.9,
      g: 110 + pulse * 1.6,
      b: 95 + pulse * 0.5,
      motion,
      clipped,
    });
  }
  return samples;
}

test('a clean synthetic trace recovers its pulse rate', () => {
  for (const bpm of [55, 72, 96]) {
    const result = analyzeCamera(trace(bpm));
    assert.equal(result.issue, 'ready', `expected a reading at ${bpm} bpm, got ${result.issue}`);
    assert.ok(Math.abs(result.bpm - bpm) <= 3, `estimated ${result.bpm}, expected about ${bpm}`);
  }
});

test('no number is reported when the capture cannot support one', () => {
  // Too short to analyze.
  assert.equal(analyzeCamera(trace(72, 3)).bpm, null);
  // Frames arriving too slowly.
  const sparse = trace(72).filter((_, i) => i % 3 === 0).map((s, i) => ({ ...s, time: i / 10 }));
  assert.equal(analyzeCamera(sparse).issue, 'frames');
  // Hand moving throughout.
  assert.equal(analyzeCamera(trace(72, CAPTURE_SECONDS, { motion: 0.2 })).issue, 'motion');
  // Blown-out exposure.
  assert.equal(analyzeCamera(trace(72, CAPTURE_SECONDS, { clipped: 0.5 })).issue, 'light');
  // Flat signal, no pulse present.
  const flat = trace(72).map((s) => ({ ...s, r: 150, g: 110, b: 95 }));
  assert.equal(analyzeCamera(flat).bpm, null);
});

test('a gap in the middle of a capture is rejected rather than interpolated', () => {
  const samples = trace(72).filter((s) => s.time < 10 || s.time > 11);
  assert.equal(analyzeCamera(samples).issue, 'frames');
});

test('samplePixels reports mean color, clipping and frame-to-frame motion', () => {
  const size = 4 * 4 * 4;
  const gray = new Uint8ClampedArray(size).fill(120);
  const white = new Uint8ClampedArray(size).fill(255);
  for (let i = 3; i < size; i += 4) { gray[i] = 255; white[i] = 255; }

  const first = samplePixels(gray, 0);
  assert.ok(Math.abs(first.g - 120) < 1);
  assert.equal(first.motion, 0);

  const second = samplePixels(white, 1 / 30, gray);
  assert.ok(second.clipped > 0.9, 'an all-white frame should read as clipped');
  assert.ok(second.motion > 0, 'a changed frame should register motion');
});

test('the default crop is a centered square inside the frame', () => {
  const crop = cameraCrop(640, 480);
  assert.equal(crop.size, Math.min(crop.size, 480));
  assert.ok(crop.x >= 0 && crop.y >= 0);
  assert.ok(crop.x + crop.size <= 640);
  assert.ok(crop.y + crop.size <= 480);
});

test('a crop is clamped inside the frame', () => {
  const crop = clampCrop({ x: -50, y: -50, size: 900 }, 640, 480);
  assert.equal(crop.size, 480);
  assert.ok(crop.x >= 0 && crop.y >= 0);
  assert.ok(crop.x + crop.size <= 640 && crop.y + crop.size <= 480);
});

test('landmarks too far from the camera yield no crop', () => {
  const tiny = Array.from({ length: 21 }, () => ({ x: 0.5, y: 0.5 }));
  assert.equal(palmCropFromLandmarks(tiny, 640, 480), null);
  assert.equal(palmCropFromLandmarks([{ x: 0.5, y: 0.5 }], 640, 480), null);
});

test('crop smoothing moves toward the target instead of snapping', () => {
  const from = { x: 100, y: 100, size: 200 };
  const to = { x: 200, y: 100, size: 200 };
  const smoothed = smoothCrop(from, to);
  assert.ok(smoothed.x > from.x && smoothed.x < to.x, 'should land between the two');
  assert.deepEqual(smoothCrop(null, to), to, 'the first detection is taken as-is');
});

test('a mirrored preview flips the crop horizontally', () => {
  const crop = { x: 0, y: 100, size: 100 };
  const plain = cropToFramePercent(crop, 400, 300, false);
  const mirrored = cropToFramePercent(crop, 400, 300, true);
  assert.ok(Math.abs(plain.left + plain.width + mirrored.left - 100) < 0.01);
  assert.equal(plain.top, mirrored.top);
});

test('quality scores 0 without an estimate and rewards a clean capture', () => {
  const samples = trace(72);
  const good = analyzeCamera(samples);
  assert.ok(captureQuality(good, samples) > 70, `a clean ${CAPTURE_SECONDS} s capture should score well`);

  assert.equal(captureQuality(null, samples), 0);
  assert.equal(captureQuality({ bpm: null, issue: 'weak', fps: 30, seconds: CAPTURE_SECONDS, trace: [] }, samples), 0);

  const shaky = trace(72, CAPTURE_SECONDS, { motion: 0.1 });
  assert.ok(
    captureQuality({ ...good, bpm: 72 }, shaky) < captureQuality(good, samples),
    'a shaky capture should score below a steady one'
  );
});
