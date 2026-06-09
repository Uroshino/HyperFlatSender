# HyperFlatSender

An Android TV app that captures the screen and streams it to a
**[Hyperion](https://github.com/hyperion-project/hyperion.ng)** or
**[HyperHDR](https://github.com/awawa-dev/HyperHDR)** ambient-lighting server, so your
LEDs follow whatever is on the TV — from *any* app, including ones with no ambient-light integration
of their own.

It grabs the screen via `MediaProjection`, scales each frame down to your LED-layout size, and sends
it over the Hyperion FlatBuffers TCP protocol (port `19400`) — which both Hyperion and HyperHDR speak.

## Features

- **Universal screen capture** — works with any app (streaming, games, live TV); no per-app
  integration needed.
- **Aspect-correct, low-bandwidth frames** — captures directly at your small Hyperion layout size
  (e.g. 79×43), snapped to the screen's real aspect ratio so the image is never distorted. A ×1–×4
  multiplier trades detail for bandwidth.
- **Frame-rate control with guidance** — pick a capture rate (default 24 fps, aimed at film); an
  inline hint flags rates that judder on your content and points to a better one.
- **Tuned for Android 12+ / Arm Mali GPUs** — low-latency TCP (Nagle off, per-frame flush), bulk
  pixel conversion, and an optional **Gate mirror** mode that briefly attaches the capture surface
  each cycle so the TV can keep its hardware video-overlay path and drop fewer video frames while
  capturing.
- **Android TV UI** — fully D-pad-navigable Jetpack Compose interface with live connection status
  and an optional FPS / throughput overlay for diagnostics.
- **Set-and-forget** — settings autosave, the foreground service auto-reconnects with backoff, and
  the app can auto-start on boot (one tap to authorize screen capture). A keepalive holds the lights
  on a static screen instead of letting Hyperion time out to its background colour.
- **Calibration screen** — send test patterns (solid colours, a walking chase dot, a gamma ramp, an
  edge map) to verify LED count/order/orientation, and tune gamma / saturation / brightness with
  D-pad sliders over the server's JSON-RPC API (optionally token-authenticated for off-subnet setups).
  Adjustments are saved and re-applied on every connect.
- **Hyperion *or* HyperHDR** — a **Server type** setting maps the colour adjustments to the chosen
  server (the two diverged: HyperHDR uses a single `gamma` + `luminanceGain` where Hyperion has
  per-channel gamma + `brightnessGain`). The capture/stream path is identical for both.

## Requirements

- Android TV / device on **Android 12 (API 31)** or newer.
- A running **Hyperion** or **HyperHDR** server with the **Flatbuffers** server enabled (default port
  `19400`), reachable on the same network.
- *(Calibration colour tuning only)* the server's **JSON-RPC** API reachable (default port `19444`).
  A token is needed only if the device isn't on Hyperion's local subnet — see
  [Calibration & colour tuning](#calibration--colour-tuning).

## Setup

1. Build and install the app (Android Studio, or `./gradlew installDebug`).
2. On the TV, open **Settings** → enter your server **IP** and **port**, pick the **Server type**
   (Hyperion or HyperHDR), set the capture **resolution** to match your LED layout, and adjust
   **FPS** / **priority** as needed. Changes save automatically.
3. Back on the main screen, press **START** and grant the screen-capture permission. Your lights
   should now follow the screen.

> **Tip:** for the smoothest result, match the capture rate to your content — **24 fps** for film,
> or a divisor of 60 (**30 / 20 / 15**) for 60 fps content. The Settings hint will guide you.

## Calibration & colour tuning

Press **CALIBRATE** on the main screen to verify your LED layout and tune colour. It runs its own
connection (live capture is stopped while it's open) and uses the server's **JSON-RPC API**
(default port `19444`) for the colour adjustments — a separate channel from the image stream.

- **Test patterns** — solid colours, a walking chase dot, a gamma ramp and an edge map — to read off
  LED count, order, direction and start corner.
- **Colour adjustments** — the sliders follow the **Server type**: on **Hyperion**, gamma R/G/B +
  saturation + brightness gain; on **HyperHDR**, a single gamma + saturation + brightness (sent as
  HyperHDR's `gamma` / `saturationGain` / `luminanceGain`). Move the **D-pad left/right** to adjust
  the focused slider, up/down to move between them. Values are saved and re-applied automatically on
  every capture connect (neither server persists them).

### Adjustments not applying? (API authentication)

The "Colour adjustments" line on the Calibration screen states whether the channel is live, and if
not, why:

- **"No Authorization"** — Hyperion and HyperHDR only treat **loopback / same-subnet** clients as
  "local", and only those are auto-authorised when *Local API Authentication* is off. A TV on a
  **different subnet/VLAN** must authenticate with a token (same `authorize`/`login` flow on both):
  1. In Hyperion: **Configuration → Network Services → Manage Tokens**, create a token and copy the
     36-character value.
  2. In the app: **Settings → API Token**, paste it (autosaves), then re-open **Calibrate**.

  > Typing a long token with a remote is tedious — if you have `adb`, run
  > `adb shell input text "<token>"` while the field is in edit mode.

- **"Connection refused" / timeout** — the JSON-RPC server isn't reachable. Enable it in Hyperion
  (**Network Services → JSON server**), make sure it isn't firewalled, and confirm the app's
  **JSON-RPC Port** matches Hyperion's (default `19444`).

> If your TV is on the **same subnet** as Hyperion, no token is needed — just turn off *Local API
> Authentication* in Hyperion (or leave it off).

## How it works

```
MediaProjection → VirtualDisplay → ImageReader (RGBA) → FrameProcessor (RGBA→RGB)
→ FlatBuffers RawImage → TCP (4-byte length-prefixed) → Hyperion / HyperHDR
```

See [`CHANGELOG.md`](CHANGELOG.md) for the full feature and fix list.

## Credits

Designed and built with **[Claude](https://claude.com/claude-code)** (Anthropic) — from the
screen-capture pipeline and Hyperion FlatBuffers protocol to the Android-12 / Arm-Mali performance
tuning and the Compose TV interface.
