import type { Metadata } from "next";
import type { ReactNode } from "react";
import { APP_NAME, ExtLink, LegalShell, Mail, P, Section, UL } from "@/components/LegalShell";

export const metadata: Metadata = {
  title: `Support - ${APP_NAME}`,
  description: `Support, FAQ, contact, privacy, and troubleshooting information for ${APP_NAME}.`,
};

const UPDATED = "October 6, 2026";

function Faq(props: { q: string; children: ReactNode }) {
  return (
    <div className="rounded-xl border border-ink-700/50 bg-ink-900/40 p-4">
      <h3 className="mb-1.5 text-[15px] font-semibold text-white">{props.q}</h3>
      <div className="space-y-2 text-[15px] text-ink-100/85">{props.children}</div>
    </div>
  );
}

export default function SupportPage() {
  return (
    <LegalShell title="Support" subtitle={`Help, answers, and contact information for ${APP_NAME}.`} updated={UPDATED}>
      <Section heading="Contact support">
        <P>Email is the fastest way to reach us. We typically reply within <strong>2-3 business days</strong>.</P>
        <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-4">
          <div className="text-sm font-medium text-rose-100">Email support</div>
          <div className="mt-1 text-lg font-semibold"><Mail /></div>
        </div>
        <P>Please include your device and app or browser version, what happened, and a screenshot if available.</P>
      </Section>
      <Section heading="What MoniVitals is">
        <P>
          The {APP_NAME} web app gives you an approximate pulse estimate from your palm. Rest the rear
          open palm facing the camera and hold still for a guided 30-second capture.
          Open it at <ExtLink href="/palm">monivitals.com/palm</ExtLink>.
        </P>
      </Section>
      <Section heading="Frequently asked questions">
        <div className="grid gap-3">
          <Faq q="How do I use Palm scan?">
            <P>Open <ExtLink href="/palm">Palm scan</ExtLink>, tap Start palm scan, and allow camera access. Hold your open palm facing the camera, about a hand's width away, and hold still for the 30-second capture. Save the result to find it in Review, labeled Palm scan.</P>
          </Faq>
          <Faq q="Why is Palm scan unavailable in my browser?">
            <P>Palm scanning requires HTTPS, a rear camera, and a flash that your browser can control. Some phone browsers do not expose the flash. The scanner checks this before capture and explains when the light is unavailable. Use a phone browser that supports control of its rear-camera flash.</P>
          </Faq>
          <Faq q="Why did the palm timer restart?">
            <P>The timer starts after contact settles and resets if contact is lost or camera frames pause. Keep the camera and flash covered gently, without pressing hard. Cancel and lift the phone away if the light feels warm or uncomfortable. A weak signal produces a retry message rather than a saved estimate.</P>
          </Faq>
          <Faq q="Is MoniVitals a medical app?">
            <P>No. {APP_NAME} is for everyday general wellness and curiosity. The heart-rate value is an approximate estimate. {APP_NAME} is not a medical device and is not intended to diagnose, treat, cure, prevent, or monitor any disease or condition. If you have any health concern, please talk to a qualified professional.</P>
          </Faq>
          <Faq q="How does it work?">
            <P>{APP_NAME} uses photoplethysmography (PPG): the flash illuminates your palm, and the camera picks up the small color changes caused by each heartbeat. Those changes are processed on your device into an approximate heart-rate estimate in beats per minute.</P>
          </Faq>
          <Faq q="Why does my estimate take time to appear?">
            <P>The scan first waits until your palm is detected and steady, then captures for 30 seconds. Keep your open palm facing the camera. An estimate appears only when a steady pulse signal is found.</P>
          </Faq>
          <Faq q="Do you collect my data?">
            <P>Everything is worked out right on your device. Camera frames are never saved and never leave your device. The app works fully without an account; an optional free account only backs up your saved list. See the <ExtLink href="/privacy">Privacy Policy</ExtLink>.</P>
          </Faq>
          <Faq q="Does the web version need anything special?">
            <P>Use HTTPS and a phone browser that can control its rear-camera flash. Allow camera access when prompted. Saved readings stay in your browser on this device.</P>
          </Faq>
        </div>
      </Section>
      <Section heading="Troubleshooting">
        <UL>
          <li>Make sure the center of your palm gently covers both the rear camera and flash.</li>
          <li>Hold still, movement is the most common reason an estimate will not settle.</li>
          <li>On the web version, check that camera permission is allowed for this site.</li>
          <li>Restart the app or refresh the page if the camera does not start.</li>
          <li>Email support with your device details if the issue continues.</li>
        </UL>
      </Section>
      <Section heading="Wellness disclaimer">
        <P>
          {APP_NAME} provides approximate wellness estimates only. Do not use it for medical decisions.
          If you have health concerns or symptoms, consult a qualified healthcare professional.
        </P>
      </Section>
    </LegalShell>
  );
}
