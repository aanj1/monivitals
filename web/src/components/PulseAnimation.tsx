import styles from "./PulseAnimation.module.css";

// A hand, palm up. A phone settles onto the palm, rear camera and flash down.
// The screen shows the 30-second capture ring filling, then the estimate.
// The hand outline is drawn as the outer stroke of a union of rounded shapes:
// every shape is stroked first, then the same shapes are filled on top, so
// only the silhouette edge survives and no seams show between fingers and palm.

function HandShapes() {
  return (
    <>
      <rect x="150" y="340" width="124" height="90" rx="30" />
      <rect x="122" y="192" width="180" height="180" rx="44" />
      <rect x="128" y="84" width="40" height="150" rx="20" />
      <rect x="174" y="58" width="40" height="176" rx="20" />
      <rect x="220" y="72" width="40" height="164" rx="20" />
      <rect x="266" y="116" width="36" height="124" rx="18" />
      <rect x="72" y="232" width="42" height="124" rx="21" transform="rotate(-40 93 294)" />
    </>
  );
}

// Repeating PPG beat, 40 units per beat, scrolled under a -40 translateX loop.
function trace(x0: number, base: number, beats: number): string {
  let d = `M ${x0} ${base}`;
  for (let i = 0; i < beats; i++) {
    const x = x0 + i * 40;
    d +=
      ` L ${x + 8} ${base}` +
      ` L ${x + 11} ${base - 2}` +
      ` L ${x + 14} ${base - 11}` +
      ` L ${x + 17} ${base + 4}` +
      ` L ${x + 20} ${base - 1}` +
      ` L ${x + 25} ${base - 4}` +
      ` L ${x + 28} ${base - 1}` +
      ` L ${x + 40} ${base}`;
  }
  return d;
}

const RING_R = 17;
const RING_C = 2 * Math.PI * RING_R;

export default function PulseAnimation() {
  return (
    <div
      className={styles.wrap}
      role="img"
      aria-label="Animation of a phone resting on an open palm. A ring on the phone screen fills over a thirty-second capture, then an approximate heart-rate estimate appears."
      style={{ ["--ring" as string]: RING_C }}
    >
      <svg className={styles.svg} viewBox="0 0 400 400" xmlns="http://www.w3.org/2000/svg" aria-hidden>
        <defs>
          <clipPath id="mvScreenClip">
            <rect x="188" y="302" width="48" height="26" rx="4" />
          </clipPath>
        </defs>

        {/* hand */}
        <g className={styles.handStroke}>
          <HandShapes />
        </g>
        <g className={styles.handFill}>
          <HandShapes />
        </g>

        {/* flash light bleeding around the phone edge */}
        <g className={styles.glowWrap}>
          <ellipse className={styles.glow} cx="212" cy="282" rx="54" ry="86" />
        </g>

        {/* phone, screen up, camera against the palm */}
        <g className={styles.phone}>
          <rect x="180" y="218" width="64" height="128" rx="13" fill="#121417" />
          <rect x="184.5" y="222.5" width="55" height="119" rx="9.5" fill="#1a1c21" />

          {/* capture ring */}
          <circle cx="212" cy="266" r={RING_R} fill="none" stroke="#2c3038" strokeWidth="3" />
          <circle
            className={styles.ring}
            cx="212"
            cy="266"
            r={RING_R}
            fill="none"
            stroke="#e11d48"
            strokeWidth="3"
            strokeLinecap="round"
            transform="rotate(-90 212 266)"
          />
          <text className={styles.hold} x="212" y="269" textAnchor="middle">
            hold
          </text>
          <g className={styles.result}>
            <text className={styles.bpm} x="212" y="270" textAnchor="middle">
              72
            </text>
            <text className={styles.unit} x="212" y="279" textAnchor="middle">
              bpm
            </text>
          </g>

          {/* live trace */}
          <g clipPath="url(#mvScreenClip)">
            <g className={styles.flow}>
              <path className={styles.trace} d={trace(186, 316, 4)} />
            </g>
          </g>
        </g>
      </svg>
    </div>
  );
}
