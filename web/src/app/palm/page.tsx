import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = {
  title: "How the palm scan works, MoniVitals",
  description:
    "What happens during a MoniVitals palm scan: 30 seconds of ordinary video, read on your phone into an approximate heart-rate estimate. For general wellness.",
};

const STEPS = [
  {
    title: "Open your palm to the camera",
    body: "Hold your open palm facing the camera, about a hand's width away. Ordinary video, no flash and no contact.",
  },
  {
    title: "Hold still for 30 seconds",
    body: "The capture runs for 30 seconds. With every heartbeat, a little more blood reaches the skin and its colour shifts very slightly. The camera sees that shift even though you cannot.",
  },
  {
    title: "Read your estimate",
    body: "The colour signal is turned into a pulse trace and an approximate heart rate, on your phone. Nothing is uploaded and no frames are kept.",
  },
];

const DETAILS = [
  ["What it reads", "Heart rate, breathing rate, blood oxygen and heart rate variability, as approximate wellness estimates from a 30-second palm scan."],
  ["What it needs", "A phone or laptop with a camera, a browser that can open it, and steady light. No app install, no account, no wearable."],
  ["What it keeps", "Nothing, unless you choose to save a reading. Camera frames are processed in memory and discarded."],
  ["What it is not", "A medical device. The values are estimates for curiosity and general wellness, not for diagnosis or treatment decisions."],
];

export default function PalmPage() {
  return (
    <main>
      <header className="sticky top-0 z-40 bg-white/90 backdrop-blur">
        <div className="mx-auto flex h-[72px] max-w-[1240px] items-center justify-between px-5 sm:px-8">
          <Link href="/" className="flex items-center gap-2.5">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/logo-192.png" alt="" className="h-8 w-8 object-contain" />
            <span className="text-[19px] font-bold tracking-tight">MoniVitals</span>
          </Link>
          <Link href="/#scan" className="btn btn-rose btn-sm">
            Try palm scan <span className="arrow" aria-hidden>↗</span>
          </Link>
        </div>
      </header>

      <div className="mx-auto max-w-[1240px] px-5 pb-20 pt-10 sm:px-8 lg:pt-16">
        <h1 className="home-h1 max-w-[16ch]">How the palm scan works</h1>
        <p className="hero-p mt-5 text-[color:var(--mid)]">
          The scan uses photoplethysmography: light reflected from your skin changes a tiny amount with each heartbeat, and a
          phone camera can pick that change up from an open palm.
        </p>

        <div className="mt-12 grid gap-10 border-t border-[color:var(--line)] pt-10 sm:grid-cols-3 sm:gap-8">
          {STEPS.map((step) => (
            <div key={step.title}>
              <h2 className="text-[21px] font-bold tracking-tight">{step.title}</h2>
              <p className="mt-2 text-[16px] leading-relaxed text-[color:var(--mid)]">{step.body}</p>
            </div>
          ))}
        </div>

        <div className="mt-12 grid gap-x-8 gap-y-10 border-t border-[color:var(--line)] pt-10 sm:grid-cols-2">
          {DETAILS.map(([title, body]) => (
            <div key={title}>
              <h2 className="text-[19px] font-bold tracking-tight">{title}</h2>
              <p className="mt-2 text-[16px] leading-relaxed text-[color:var(--mid)]">{body}</p>
            </div>
          ))}
        </div>

        <div className="mt-12 flex flex-wrap items-center gap-3">
          <Link href="/#scan" className="btn btn-rose">
            Try palm scan <span className="arrow" aria-hidden>↗</span>
          </Link>
          <Link href="/support" className="btn btn-ink">
            Support and FAQ
          </Link>
        </div>
        <p className="mt-6 max-w-[70ch] text-[15px] leading-relaxed text-[color:var(--mid)]">
          MoniVitals is for everyday general wellness and curiosity. It is not a medical device and is not intended to diagnose,
          treat, cure, prevent, or monitor any disease or condition. If you have any health concern, please talk to a qualified
          professional.
        </p>
      </div>
    </main>
  );
}
