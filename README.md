# MoniVitals

Palm and fingertip pulse estimates for general wellness, across a wearable, two phone apps and the web.

| Folder | What it is | Stack |
|---|---|---|
| [`firmware/`](firmware) | ESP32-C3 Super Mini + MAX30102 PPG sensor, streams over BLE as `MoniVitals-XXXX` | ESP-IDF via PlatformIO |
| [`ios/`](ios) | iPhone app: camera palm scan, BLE wearable, review and CSV export | SwiftUI, CoreBluetooth, XcodeGen |
| [`android/`](android) | Android port of the iOS app | Kotlin, Jetpack Compose |
| [`web/`](web) | monivitals.com: landing page with the palm scan, how-it-works page, US cardiovascular atlas | Next.js 14, TypeScript, Tailwind |

Each folder builds on its own; see its README. The BLE GATT table in [`firmware/README.md`](firmware/README.md) is the contract the apps follow, and the DSP and CSV formats are kept in step between `ios/MoniVitals` and `android/app/src/main/java/com/monivitals`.

## Quick start

```bash
# web
cd web && npm install && npm run dev

# iOS (needs XcodeGen)
cd ios && xcodegen generate && open MoniVitals.xcodeproj

# Android
cd android && ./gradlew assembleDebug

# firmware (needs PlatformIO)
cd firmware && pio run -t upload
```

MoniVitals is for everyday general wellness and curiosity. It is not a medical device and is not intended to diagnose, treat, cure, prevent or monitor any condition.
