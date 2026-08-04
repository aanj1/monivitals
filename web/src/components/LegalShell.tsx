import Link from "next/link";
import type { ReactNode } from "react";

export const CONTACT_EMAIL = "aadyanj@gmail.com";
export const APP_NAME = "MoniVitals";

export function LegalShell(props: {
  title: string;
  subtitle: string;
  updated: string;
  children: ReactNode;
}) {
  return (
    <main className="min-h-screen bg-white text-ink-900">
      <header className="border-b border-ink-200 bg-white/85 backdrop-blur">
        <div className="mx-auto flex max-w-3xl flex-wrap items-center justify-between gap-3 px-5 py-3.5">
          <Link href="/" className="flex items-center gap-2.5 text-sm font-bold tracking-tight text-ink-900">
            <span className="flex h-7 w-7 items-center justify-center overflow-hidden rounded-lg bg-white ring-1 ring-ink-200">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/logo-192.png" alt="" className="h-6 w-6 object-contain" />
            </span>
            {APP_NAME}
          </Link>
          <nav className="flex items-center gap-4 text-sm font-semibold text-ink-800">
            <Link href="/map" className="hover:text-ink-900">Map</Link>
            <Link href="/privacy" className="hover:text-ink-900">Privacy</Link>
            <Link href="/support" className="hover:text-ink-900">Support</Link>
            <Link href="/palm" className="rounded-md bg-rose-600 px-2.5 py-1.5 text-white hover:bg-rose-500">
              Open app
            </Link>
          </nav>
        </div>
      </header>
      <article className="mx-auto max-w-3xl px-5 py-10">
        <h1 className="text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl">{props.title}</h1>
        <p className="mt-3 max-w-2xl text-lg font-medium leading-7 text-ink-800">{props.subtitle}</p>
        <p className="mt-4 text-sm font-medium text-ink-800">Last updated: {props.updated}</p>
        <div className="mt-10 space-y-9">{props.children}</div>
      </article>
      <footer className="mt-14 border-t border-ink-200">
        <div className="mx-auto flex max-w-3xl flex-col gap-2 px-5 py-6 text-xs text-ink-700 sm:flex-row sm:items-center sm:justify-between">
          <span>© {new Date().getFullYear()} {APP_NAME}. For general wellness only, not a medical device.</span>
          <span className="flex items-center gap-3">
            <Link href="/privacy" className="hover:text-ink-900">Privacy</Link>
            <Link href="/support" className="hover:text-ink-900">Support</Link>
          </span>
        </div>
      </footer>
    </main>
  );
}

export function Section(props: { heading: string; children: ReactNode }) {
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold tracking-tight text-ink-900">{props.heading}</h2>
      <div className="space-y-3">{props.children}</div>
    </section>
  );
}

export function P(props: { children: ReactNode }) {
  return <p className="text-[15px] leading-7 text-ink-700">{props.children}</p>;
}

export function UL(props: { children: ReactNode }) {
  return (
    <ul className="list-disc space-y-1.5 pl-5 text-[15px] leading-7 text-ink-700 marker:text-rose-500">
      {props.children}
    </ul>
  );
}

export function Mail() {
  return (
    <a href={`mailto:${CONTACT_EMAIL}`} className="font-medium text-rose-600 underline-offset-2 hover:underline">
      {CONTACT_EMAIL}
    </a>
  );
}

export function ExtLink(props: { href: string; children: ReactNode }) {
  return (
    <a href={props.href} target="_blank" rel="noreferrer" className="text-rose-600 underline-offset-2 hover:underline">
      {props.children}
    </a>
  );
}
