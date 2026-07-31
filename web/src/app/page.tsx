import Image from "next/image";
import Link from "next/link";
import MapExplorer from "@/components/atlas/MapExplorer";
import PulseAnimation from "@/components/PulseAnimation";

const STEPS = [
  {
    title: "Open your palm to the camera",
    body: "Hold your open palm facing the camera, about a hand's width away. Ordinary video, no flash and no contact.",
  },
  {
    title: "Hold still for 30 seconds",
    body: "Once your palm is detected, the 30-second capture starts. The camera reads the small color change in your skin with every heartbeat.",
  },
  {
    title: "Read your estimate",
    body: "An approximate heart-rate estimate appears on screen. Save it to your list or let it go. Nothing leaves the phone.",
  },
];

// Community screening photos, brought over from the farmers-market gallery.
// Three hand-balanced columns so the grid ends level instead of ragged.
type Photo = { src: string; w: number; h: number; alt: string };
const PHOTO_COLUMNS: Photo[][] = [
  [
    { src: "/community/20260829-090625.jpg", w: 1600, h: 1200, alt: "Showing a visitor the palm scan on a phone at the MoniVitals table" },
    { src: "/community/pic-25.jpg", w: 1200, h: 1600, alt: "Two people beside the MoniVitals free community health screening board" },
    { src: "/community/20260829-094947.jpg", w: 1600, h: 1200, alt: "Visitors gathered at the MoniVitals tent at the farmers market" },
  ],
  [
    { src: "/community/pic-20.jpg", w: 1200, h: 1600, alt: "A visitor holding the MoniVitals screening board and smiling" },
    { src: "/community/pic-35.jpg", w: 1600, h: 721, alt: "Showing the MoniVitals app on a laptop at the farmers market" },
    { src: "/community/pic-55.jpg", w: 1200, h: 1600, alt: "A visitor reading the MoniVitals board about why consistent checks matter" },
  ],
  [
    { src: "/community/20260829-093159.jpg", w: 1600, h: 1200, alt: "Market shoppers stopping at the MoniVitals table" },
    { src: "/community/20260829-084022.jpg", w: 1200, h: 1600, alt: "The MoniVitals screening table set up under a blue tent" },
    { src: "/community/20260829-091801.jpg", w: 1600, h: 1200, alt: "A visitor laughing at the MoniVitals table" },
  ],
];

const FAQS = [
  {
    q: "Palm or fingertip, which should I use?",
    a: "Either works. Rest the rear camera and flash against the center of your palm, or against the pad of a fingertip. Pick whichever gives steady, comfortable contact.",
  },
  {
    q: "Do I need any extra gear or an account?",
    a: "No. No wearable, no attachment, and no sign-up. The app works fully without an account; an optional free account only backs up your saved list.",
  },
  {
    q: "Is any face or personal data used?",
    a: "No. There is no face scan and no personal information required. The camera only reads light changes at your skin while you measure.",
  },
  {
    q: "How does it work?",
    a: "Photoplethysmography. The flash lights your skin and the camera picks up the small color changes caused by each heartbeat, which are processed on your device into an approximate heart-rate estimate.",
  },
  {
    q: "Why did the timer restart?",
    a: "The timer starts after contact settles and resets if contact is lost or frames pause. Keep the camera and flash covered gently, without pressing hard.",
  },
  {
    q: "Is MoniVitals a medical device?",
    a: "No. It is for everyday general wellness and curiosity. The value is an approximate estimate and is not intended to diagnose, treat, cure, prevent, or monitor any condition.",
  },
];

const PRIVACY = [
  ["No account needed", "No sign-in or registration. The app is fully usable without one."],
  ["Computed on your phone", "Heart-rate estimates come from the camera signal, processed locally."],
  ["Frames are never kept", "Raw camera frames are not saved, uploaded, or shared."],
  ["No tracking", "No face data, no advertising or tracking SDKs, and we never sell personal information."],
];

const NAV = [
  ["#how", "How it works"],
  ["#testimonials", "Testimonials"],
  ["#atlas", "Atlas"],
  ["#faq", "FAQ"],
];

export default function Page() {
  return (
    <>
      <header className="sticky top-0 z-40 bg-white/90 backdrop-blur">
        <div className="mx-auto flex h-[72px] max-w-[1240px] items-center justify-between px-5 sm:px-8">
          <a href="#top" className="flex items-center gap-2.5">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/logo-192.png" alt="" className="h-8 w-8 object-contain" />
            <span className="text-[19px] font-bold tracking-tight">MoniVitals</span>
          </a>
          <nav className="hidden items-center gap-7 text-[16px] font-semibold text-[color:var(--mid)] md:flex">
            {NAV.map(([href, label]) => (
              <a key={href} href={href} className="transition hover:text-[color:var(--ink)]">
                {label}
              </a>
            ))}
          </nav>
          <Link href="/palm" className="btn btn-rose btn-sm">
            Start palm scan <span className="arrow" aria-hidden>↗</span>
          </Link>
        </div>
      </header>

      <main id="top">
        {/* ---------- Hero ---------- */}
        <section id="scan" className="scroll-mt-20">
          <div className="mx-auto max-w-[1240px] px-5 pb-6 pt-8 sm:px-8 lg:pb-10 lg:pt-16">
            <div className="hero-grid">
              <div className="rise min-w-0">
                <h1 className="home-h1">Your pulse, anywhere.</h1>
                <p className="hero-p mt-4 text-[color:var(--mid)] lg:mt-6">
                  A 30-second heart-rate check on the phone you already carry. No wearable, no
                  account, no face scan. The reading stays on your phone.
                </p>
                <div className="mt-6 flex flex-wrap items-center gap-3 lg:mt-8">
                  <Link href="/palm" className="btn btn-rose">
                    Start palm scan <span className="arrow" aria-hidden>↗</span>
                  </Link>
                  <a href="#how" className="btn btn-ink">
                    How it works
                  </a>
                </div>
                <p className="mt-4 text-[15px] font-medium text-[color:var(--mid)]">For general wellness only. Not a medical device.</p>
              </div>
              <div className="rise min-w-0" style={{ animationDelay: "0.1s" }}>
                <PulseAnimation />
                <p className="hero-cap mt-3 text-center text-[color:var(--mid)]">Open palm facing the camera. Processed on your device.</p>
              </div>
            </div>
          </div>
        </section>

        {/* ---------- Atlas ---------- */}
        <section id="atlas" className="scroll-mt-20">
          <div className="mx-auto max-w-[1240px] px-5 py-12 sm:px-8 lg:py-16">
            <div className="flex flex-wrap items-end justify-between gap-6">
              <div>
                <h2 className="home-h2">The Nation&rsquo;s Vitals</h2>
                <p className="mt-4 max-w-[52ch] text-[19px] leading-relaxed text-[color:var(--mid)]">
                  Cardiovascular indicators across the United States, county by county. Search a place, compare two periods,
                  and follow a few places over time.
                </p>
              </div>
              <Link href="/map" className="btn btn-ink">
                Open the full atlas <span className="arrow" aria-hidden>↗</span>
              </Link>
            </div>
            <div className="mt-8 overflow-hidden rounded-[24px] border border-[color:var(--line)] bg-white">
              <MapExplorer embedded />
            </div>
          </div>
        </section>

        {/* ---------- How it works ---------- */}
        <section id="how" className="scroll-mt-20">
          <div className="mx-auto max-w-[1240px] px-5 py-20 sm:px-8 lg:py-28">
            <h2 className="home-h2 max-w-[16ch]">Light on skin, read on your phone.</h2>
            <div className="mt-12 grid gap-10 border-t border-[color:var(--line)] pt-10 sm:grid-cols-3 sm:gap-8">
              {STEPS.map((s) => (
                <div key={s.title}>
                  <h3 className="text-[21px] font-bold tracking-tight">{s.title}</h3>
                  <p className="mt-2 text-[16px] leading-relaxed text-[color:var(--mid)]">{s.body}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* ---------- Testimonials ---------- */}
        <section id="testimonials" className="scroll-mt-20 bg-[color:var(--paper)]">
          <div className="mx-auto max-w-[1240px] px-5 py-20 sm:px-8 lg:py-28">
            <div className="grid gap-6 lg:grid-cols-12 lg:items-end">
              <h2 className="home-h2 lg:col-span-5">Testimonials</h2>
              <p className="max-w-[52ch] text-[19px] leading-relaxed text-[color:var(--mid)] lg:col-span-7">
                MoniVitals set up a free screening table at the local farmers market. Neighbors stopped by for a check of
                their vitals in about a minute, read privately on their own phone. No needles, no cuffs, no sign-up.
              </p>
            </div>
            <figure className="relative mt-12 overflow-hidden rounded-[24px] bg-white">
              <Image
                src="/community/pic-33.jpg"
                alt="MoniVitals at a farmers market: a laptop showing how pixels become a pulse, next to the screening board"
                width={1600}
                height={721}
                sizes="(max-width: 1240px) 100vw, 1176px"
                className="h-auto w-full"
              />
            </figure>
            <div className="photo-grid mt-4">
              {PHOTO_COLUMNS.map((col, i) => (
                <div key={i} className="photo-col">
                  {col.map((p) => (
                    <figure key={p.src} className="overflow-hidden rounded-[20px] bg-white">
                      <Image src={p.src} alt={p.alt} width={p.w} height={p.h} sizes="(max-width: 560px) 100vw, (max-width: 900px) 50vw, 400px" className="h-auto w-full" />
                    </figure>
                  ))}
                </div>
              ))}
            </div>
            <p className="mt-8 text-[15px] text-[color:var(--mid)]">
              Photos from the community screening day. The screening used the MoniVitals app on visitors&rsquo; own phones.{" "}
              <a href="/community/monivitals-board.pdf" className="font-semibold text-[color:var(--ink)] underline underline-offset-4">
                See the project board
              </a>
              .
            </p>
          </div>
        </section>

        {/* ---------- FAQ ---------- */}
        <section id="faq" className="scroll-mt-20 bg-[color:var(--paper)]">
          <div className="mx-auto max-w-[1240px] px-5 py-20 sm:px-8 lg:py-28">
            <div className="grid gap-10 lg:grid-cols-12">
              <div className="lg:col-span-4">
                <h2 className="home-h2">Questions</h2>
                <p className="mt-4 text-[16px] text-[color:var(--mid)]">
                  More on the{" "}
                  <Link href="/support" className="font-semibold text-[color:var(--ink)] underline underline-offset-4">
                    support page
                  </Link>
                  .
                </p>
              </div>
              <div className="faq border-t border-[color:var(--line)] lg:col-span-8">
                {FAQS.map((f) => (
                  <details key={f.q} className="border-b border-[color:var(--line)]">
                    <summary className="flex cursor-pointer items-center justify-between gap-6 py-5 text-[19px] font-bold tracking-tight">
                      {f.q}
                      <span aria-hidden className="plus text-xl font-medium text-[color:var(--rose)]">
                        +
                      </span>
                    </summary>
                    <p className="max-w-[62ch] pb-6 text-[16px] leading-relaxed text-[color:var(--mid)]">{f.a}</p>
                  </details>
                ))}
              </div>
            </div>
          </div>
        </section>

        {/* ---------- Privacy ---------- */}
        <section id="privacy" className="scroll-mt-20">
          <div className="mx-auto max-w-[1240px] px-5 py-20 sm:px-8 lg:py-28">
            <h2 className="home-h2">Private by design</h2>
            <div className="mt-12 grid gap-x-8 gap-y-10 border-t border-[color:var(--line)] pt-10 sm:grid-cols-2 lg:grid-cols-4">
              {PRIVACY.map(([title, body]) => (
                <div key={title}>
                  <h3 className="text-[19px] font-bold tracking-tight">{title}</h3>
                  <p className="mt-2 text-[16px] leading-relaxed text-[color:var(--mid)]">{body}</p>
                </div>
              ))}
            </div>
            <p className="mt-10 max-w-[70ch] text-[15px] leading-relaxed text-[color:var(--mid)]">
              MoniVitals is for everyday general wellness and curiosity. The heart-rate value is an approximate estimate.
              MoniVitals is not a medical device and is not intended to diagnose, treat, cure, prevent, or monitor any
              disease or condition. If you have any health concern, please talk to a qualified professional. Read the full{" "}
              <Link href="/privacy" className="font-semibold text-[color:var(--ink)] underline underline-offset-4">
                privacy policy
              </Link>
              .
            </p>
          </div>
        </section>

        <footer className="border-t border-[color:var(--line)]">
          <div className="mx-auto flex max-w-[1240px] flex-wrap items-center justify-between gap-4 px-5 py-8 text-[15px] font-medium text-[color:var(--mid)] sm:px-8">
            <span className="font-semibold text-[color:var(--ink)]">MoniVitals</span>
            <div className="flex flex-wrap gap-6">
              <Link href="/palm" className="transition hover:text-[color:var(--ink)]">Palm scan</Link>
              <Link href="/map" className="transition hover:text-[color:var(--ink)]">Health atlas</Link>
              <Link href="/support" className="transition hover:text-[color:var(--ink)]">Support</Link>
              <Link href="/privacy" className="transition hover:text-[color:var(--ink)]">Privacy</Link>
            </div>
            <span>© 2026 MoniVitals</span>
          </div>
        </footer>
      </main>
    </>
  );
}
