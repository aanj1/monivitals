"use client";

import { START_SCAN_EVENT } from "./HomeScan";

/** Scrolls to the scan and starts it. Used by the header and the hero. */
export default function StartScanButton({ className, children }: { className: string; children: React.ReactNode }) {
  return (
    <button
      type="button"
      className={className}
      onClick={() => {
        document.getElementById("scan")?.scrollIntoView({ behavior: "smooth", block: "start" });
        window.dispatchEvent(new Event(START_SCAN_EVENT));
      }}
    >
      {children}
    </button>
  );
}
