import type { Metadata } from "next";
import { APP_NAME, LegalShell, Mail, P, Section, UL } from "@/components/LegalShell";

export const metadata: Metadata = {
  title: `Privacy Policy - ${APP_NAME}`,
  description: `${APP_NAME} privacy policy: on-device processing, camera frames never saved, optional account, no tracking, and no advertising.`,
};

const UPDATED = "October 6, 2026";

export default function PrivacyPage() {
  return (
    <LegalShell
      title="Privacy Policy"
      subtitle={`${APP_NAME} works everything out on your device. Camera frames are never saved and never leave your device.`}
      updated={UPDATED}
    >
      <Section heading="Summary">
        <UL>
          <li>No account, sign-in, or registration is required, the app is fully usable without one.</li>
          <li>Heart-rate estimates are computed on your device from the camera signal.</li>
          <li>Raw camera frames are never saved, uploaded, or shared.</li>
          <li>An optional free account only backs up your profiles and saved-clip list; recorded samples stay on the device that captured them.</li>
          <li>We do not use advertising SDKs, tracking SDKs, or Apple&apos;s advertising identifier.</li>
          <li>We do not sell personal information.</li>
        </UL>
      </Section>
      <Section heading="What MoniVitals does">
        <P>
          The {APP_NAME} web app estimates your pulse from your palm using photoplethysmography (PPG):
          you rest the rear camera and flash against your palm, and the tiny color changes with each
          heartbeat are processed on your device into an approximate heart-rate estimate in beats per
          minute. {APP_NAME} is a general-wellness app, not a medical device.
        </P>
      </Section>
      <Section heading="Camera">
        <P>
          The camera is used only while you are taking a measurement, to read the light changes at your
          skin. Frames are processed in memory on your device and immediately discarded. They are
          never stored, never uploaded, and never leave your device.
        </P>
        <P>
          The web Palm scan uses the rear camera and flash against your palm for a guided pulse
          reading. Camera images stay in memory and are discarded. When you choose Save reading,
          the pulse trace, approximate estimate, signal quality, capture time, and palm placement
          label are saved locally in your browser&apos;s IndexedDB. No palm photograph or video is saved.
        </P>
      </Section>
      <Section heading="Information we collect">
        <P><strong>Without an account:</strong> none. Estimates and any clips you save are stored locally on your device only. In the web version, saved readings live in your browser&apos;s local storage and are not uploaded.</P>
        <P>
          <strong>With an optional account:</strong> if you choose to create a free account (email,
          Google, or Apple sign-in), we store your email address and your backed-up profiles and
          saved-clip list (heart-rate estimates and session metadata) in your own private cloud space.
          Recorded sample files themselves always stay on your device.
        </P>
        <P>
          <strong>Server logs:</strong> the hosting provider may process standard technical request logs
          to serve the site, maintain security, and diagnose problems. We do not use these logs for
          advertising profiles.
        </P>
      </Section>
      <Section heading="Your choices and data deletion">
        <UL>
          <li>Use the app without any account, nothing is collected.</li>
          <li>Delete saved clips at any time from within the app.</li>
          <li>Delete your account and all cloud data at any time from Settings.</li>
          <li>Email us for any privacy request and we will help.</li>
        </UL>
      </Section>
      <Section heading="Cookies and tracking">
        <P>{APP_NAME} does not use advertising cookies or analytics trackers.</P>
      </Section>
      <Section heading="Wellness disclaimer">
        <P>
          {APP_NAME} is for everyday general wellness and curiosity. The heart-rate value is an
          approximate estimate. {APP_NAME} is not a medical device and is not intended to diagnose,
          treat, cure, prevent, or monitor any disease or condition. Do not use it for medical
          decisions. If you have health concerns, consult a qualified healthcare professional.
        </P>
      </Section>
      <Section heading="Contact">
        <P>Questions or privacy requests? Email <Mail />.</P>
      </Section>
    </LegalShell>
  );
}
