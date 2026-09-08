# MoniVitals — website

Marketing site and palm-pulse web companion for **MoniVitals**. Rest the rear 
camera and flash flat against your palm for a guided 60-second capture.
MoniVitals reads tiny color changes with each heartbeat and shows an approximate
pulse estimate — for general wellness only, not a medical device.

## Pages

| Route      | What it is                                                        |
| ---------- | ----------------------------------------------------------------- |
| `/`        | Landing page for palm scanning and the health atlas              |
| `/measure` | Compatibility redirect to `/palm`                               |
| `/palm`    | Guided 60-second palm-pulse capture, with local saving and Review |
| `/map`     | 2D US cardiovascular atlas — counties, states, and period comparisons |
| `/support` | Support & FAQ (App Store support URL)                             |
| `/privacy` | Privacy policy (App Store privacy URL)                            |

The `/palm` app runs entirely in the browser: camera frames are processed in
memory and discarded; saved readings live in IndexedDB (Dexie) on the device.

The `/map` route restores the original atlas as an SVG choropleth, including
Alaska and Hawaii insets. It supports three indicators, population filters,
county/state/FIPS search, pan and zoom, side-by-side time periods, and value
histories for up to three selected places. The homepage remains at `/`.

**The committed atlas dataset is illustrative sample data**, restored from the
original map. It is labeled throughout the interface and must not be presented
as observed CDC statistics. Geometry and values are served from `public/geo`
and `public/data`; no map API key, external tile service, or WebGL is needed.
Periods and color domains come from `public/data/manifest.json`, with a fixed
scale across periods for each indicator. Territories are not shown.

## Palm scan

Open `/palm`; existing `/measure` links redirect there. Palm scan is the only
capture option, alongside Review and Settings. This follows the contact-palm
flow in the local VitaPalm/PulsePal iOS app: rear camera and flash flat against the
palm, 1.5 seconds of settled contact, then 60 seconds of capture. Contact loss or
a frame gap resets the timer. A completed reading can be saved to Review and
exported with `placement: "palm"`; older readings default to fingertip.

The browser version requires HTTPS (or localhost) and a camera track with a
confirmed controllable torch. Unlike iOS, the web torch API does not provide the
native half-power setting. Unsupported cameras get an explanation to use a phone
browser with rear-camera flash control. Frames never leave the browser and are never stored. Only a
user-saved pulse trace and summary are written to IndexedDB.

The web estimator resamples camera timestamps and checks pulse amplitude and
periodicity before displaying an estimate. This is an experimental wellness
feature, not clinical validation or full parity with the native signal pipeline.
Run `npm test` for synthetic signal, capture-state, and camera-lifecycle checks.

## Community photos

`public/community/` holds the farmers-market screening photos and the project
board PDF shown in the Testimonials section of the home page. They came over
from the aadya portfolio repo (`public/gallery`, `public/projects`,
`public/docs`); edit `PHOTO_COLUMNS` in `src/app/page.tsx` to change the grid.

## Develop

```bash
npm install
npm run dev     # http://localhost:3000
npm run build   # production build (Vercel runs this)
```

Next.js 14 (app router) + TypeScript + Tailwind. Deployed on Vercel.
