# MoniVitals — website

Marketing site for **MoniVitals**, with the palm scan on the home page. Hold an
open palm to the camera for a 30-second capture and read an approximate pulse
estimate, for general wellness only, not a medical device.

## Pages

| Route      | What it is                                                        |
| ---------- | ----------------------------------------------------------------- |
| `/`        | Landing page: the palm scan, the health atlas, how it works, FAQ |
| `/measure` | Compatibility redirect to `/palm`                               |
| `/palm`    | How the palm scan works (informational)                          |
| `/map`     | 2D US cardiovascular atlas — counties, states, and period comparisons |
| `/support` | Support & FAQ (App Store support URL)                             |
| `/privacy` | Privacy policy (App Store privacy URL)                            |

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

The scan lives in the hero of the home page (`src/components/HomeScan.tsx`).
The pulse animation fills the square until the visitor taps Try palm scan; the
header and hero buttons trigger the same thing through a window event. The
browser asks for camera permission, the live feed replaces the animation for a
30-second capture with a countdown ring, then the feed fades, the animation
returns, and the reading appears below: heart rate, breathing rate, blood
oxygen and heart rate variability. The feed is never recorded or uploaded, and
the camera is released as soon as the capture ends or is cancelled.

The reading shown today is a placeholder drawn from healthy resting ranges; the
camera signal is not yet analysed in the browser. The earlier in-browser
estimator (MediaPipe hand tracking, PPG extraction, IndexedDB saving) was
removed in October 2026 and is in git history if it is needed again.

`/palm` is an explanation page and links back to the scan.

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
