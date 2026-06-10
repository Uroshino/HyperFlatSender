# Changelog

All notable changes to **HyperFlatSender** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3] - 2026-06-10

### Added
- **One-press capture toggle** (`ToggleActivity`) — a second, headless launcher entry
  ("HyperFlat Toggle") that starts capture if stopped and stops it if running, with no UI of its own. It appears
  in the TV Apps row and in the **"Apps" list of remote-button-mapper apps** (e.g. Button Mapper), so
  a spare remote key can toggle streaming without opening the app. Being `exported`, it is also
  triggerable from automation or a shell: `adb shell am start -n com.hyperflatsender/.ToggleActivity`.
  Starting still shows the system screen-capture consent dialog (a MediaProjection token can't be
  reused or obtained from the background); stopping is instant and silent.

### Fixed / hardened
- **Boot auto-start could leave capture "running but broken."** Two stacking guards: the
  `EXTRA_FROM_BOOT` flag is now consumed after it pops the consent dialog, so an activity recreation
  can't re-fire it; and `CaptureService` ignores a projection-start that arrives while a session is
  already live instead of building a second `ImageReader`/`VirtualDisplay`/capture thread over the
  first. If a boot start ever does come up wedged, the new toggle (or `STOP`/`START`) is a clean,
  one-press recovery.

## [1.2] - 2026-06-09

### Added
- **HyperHDR support** for the calibration colour-adjustment channel. A new **Server type** setting
  (*Hyperion* / *HyperHDR*) formats the JSON-RPC `adjustment` keys for the selected server — HyperHDR
  uses a single `gamma` (no per-channel) and `luminanceGain` in place of Hyperion's per-channel gamma
  and `brightnessGain`, and like Hyperion rejects unknown keys wholesale (`additionalProperties:false`).
  The FlatBuffers image stream (port `19400`) and JSON-RPC token auth are identical for both servers.

### Changed
- **Renamed the app from HyperionFlatSender to HyperFlatSender** (package `com.hyperflatsender`). The
  application ID changed, so v1.2 installs *alongside* an existing HyperionFlatSender build rather than
  upgrading it — uninstall the old app if you don't want both.

## [1.1] - 2026-06-09

### Added
- **Calibration screen** (new `CALIBRATE` button on the main screen): a self-contained Hyperion
  client used to verify the LED layout and tune colour, independent of screen capture.
  - **Test patterns** streamed to the LED layout: solid White/Red/Green/Blue/Cyan/Magenta/Yellow/Black
    (sent as Hyperion `Color` commands), plus a walking **Chase** block sized to fill an LED's
    sampling region (so it reads as solid white, not a diluted grey), a **Gamma ramp**, and an
    **Edge map** (top=red, right=green, bottom=blue, left=yellow) sent as synthesized `RawImage`s — to
    read off LED count, order, direction, start corner and strip orientation.
  - **Colour adjustments** — gamma R/G/B, saturation and brightness gain — pushed **live** over
    Hyperion's JSON-RPC API (default port `19444`) as you drag the D-pad sliders. Values are saved and
    **re-applied automatically every time a capture connects**, so they survive a Hyperion restart
    (Hyperion itself does not persist them).
  - Opening Calibration **stops live capture first** so the two don't fight over the same priority;
    leaving the screen releases the priority and the normal output resumes.
- **Configurable JSON-RPC port** setting (default `19444`), used for the adjustments channel.
- **Optional API token** for the JSON-RPC adjustments channel (Settings → *API Token*). Hyperion only
  auto-authorises loopback / same-subnet clients when *Local API Authentication* is off, so a device on
  a **different subnet/VLAN** can now present a token (created in Hyperion → *Network Services → Manage
  Tokens*) to authorise the channel. Sent as an `authorize`/`login` right after connecting; blank keeps
  the previous local-network behaviour.
- **Calibration "Chase block size" slider** — tunes the walking block's size (as a % of each layout
  axis) so it can be matched to your LED sampling and read as solid white; persisted, applied live.
- **"Spokes" demo overlay** on the main screen — a spinning radial pattern whose spoke thickness is
  driven by the chase block size, alongside the colour wheel in the demo-mode cycle.

### Fixed
- **Calibration sliders ignored the D-pad.** The Material3 sliders didn't react to left/right on a
  remote, so colour adjustments couldn't be changed. Left/right now step the focused slider (≈1/40 of
  its range per press) and keep focus; up/down move between sliders.
- **Calibration header scrolled off-screen.** The title and connection status lived inside the
  scrolling list with no focusable element above the first control, so D-pad navigation pushed them off
  the top with no way to scroll back — leaving only part of the status row visible. They are now a fixed
  header; only the controls below scroll.
- **Adjustment channel always reported a generic "not reachable".** Connect failures, timeouts and
  Hyperion rejections now surface the actual reason (e.g. *No Authorization*, *Connection refused*) on
  the Calibration screen and in logcat, so the cause is actionable.
- **`brightnessGain` could be set to `0`,** below Hyperion's accepted minimum of `0.1`, which failed
  the whole `adjustment` command's schema validation. The slider floor is now `0.1`, and every
  adjustment value is clamped to Hyperion's accepted range before being sent.
- **Calibration left a stale overlay (e.g. the Chase block) after switching patterns.** Two causes:
  the per-pattern renderers handed over without a clean stop, and solids were sent as a `Color`
  command which — unlike an image — didn't reliably overwrite the previous Chase frame server-side
  (Hyperion routes `Color` and `Image` through different paths). Rendering is now a single loop that
  is the sole socket writer, and *every* pattern (solids included) is sent as a full RawImage, which
  always replaces whatever was on the LEDs.
- **Stream dropping to Hyperion's background colour after ~20 s of a static image.** When the screen
  holds still, the compositor stops delivering frames, so nothing was sent and Hyperion timed out our
  priority / closed the idle socket — the LEDs fell back to amber. A keepalive now re-sends the last
  frame every 2 s while connected-but-idle, holding the priority (and the connection) alive; it never
  fires during active streaming. The Calibration screen holds static patterns the same way.

### Changed
- **Colour wheel is now an opt-in demo toggle, and the main buttons form a 2×2 grid.** The wheel no
  longer appears automatically on START; a button beside START cycles on-screen demo overlays
  (off → colour wheel → spokes). START / demo sit above CALIBRATE / SETTINGS.
- **Release builds are signed with the debug key** so `installRelease` works for local performance
  testing without a keystore. Replace with a real signing config before publishing.

### Performance
- **Chase animation no longer allocates per frame.** It reuses a single image buffer (re-drawn each
  step) instead of allocating a new one every ~80 ms, removing that GC churn during calibration.

## [1.0] - 2026-06-08

Initial release. Android TV-first app that captures the screen via `MediaProjection`
and streams scaled-down RGB frames to a Hyperion ambient-lighting server over the
Hyperion FlatBuffers TCP protocol (port 19400). Tuned for Android 12+ and Arm Mali
GPUs (developed against a MediaTek MT5895 / Mali-G57 Android 12 TV).

### Added
- **Screen-capture pipeline**: `MediaProjection` → `VirtualDisplay` → `ImageReader`
  (RGBA_8888) → `FrameProcessor` (RGBA→RGB) → FlatBuffers `RawImage` →
  `HyperionClient` (TCP with 4-byte big-endian length-prefix framing).
- **Aspect-ratio-correct capture**: the requested resolution is snapped to the nearest
  pair that matches the screen's real aspect ratio (only the single axis that minimises
  the ratio error is nudged). Capture and output share these dimensions, so the image
  sent to Hyperion is never geometrically distorted.
- **Resolution multiplier** (×1–×4) to trade detail for bandwidth.
- **Jetpack Compose TV UI**, fully D-pad navigable:
  - Main screen showing server/connection status, the screen and streamed resolutions,
    and an optional captured→sent FPS / bytes overlay (off by default) to diagnose
    app-side vs. compositor frame loss.
  - Settings screen for server IP/port, width/height, multiplier, FPS, priority and
    origin, with live display of the selected and aspect-scaled resolution.
  - **FPS guidance hint** under the rate field: 60fps content resamples cleanly only to
    divisors of 60 (30/20/15/…) while 24fps suits film, so the hint flags rates that will
    judder and points to a better choice for the content.
  - **Configurable colour wheel** (its own FPS and seconds-per-spin) shown while capturing.
  - Experimental **Gate mirror** toggle (off by default) — see Performance.
  - On-screen **app version**.
- **Hyperion registration**: a `Register` message with configurable `origin` (the source
  label shown in Hyperion) and `priority` (100–199) on every connect.
- **Foreground service** (`CaptureService`) with auto-reconnect and exponential backoff,
  settings persisted via DataStore, and auto-start on boot (`BootReceiver`, which waits
  for one-tap MediaProjection authorisation).

### Performance
Tuned for Android 12+ on Arm Mali (MT5895 / Mali-G57):
- **Low-latency networking**: `TCP_NODELAY` (Nagle off) with a per-frame flush (replacing
  a 100 ms batch), keep-alive, and a send buffer sized for the worst-case frame — the
  lights track the screen instead of lagging behind it.
- **Faster RGBA→RGB conversion**: the per-pixel `ByteBuffer` loop is replaced with bulk
  whole-frame / per-row copies, shortening the Mali gralloc/ION cache-sync window the CPU
  holds each frame.
- **Single-allocation frame framing**: the length-prefixed message is built with one
  allocation and one copy per frame instead of two, cutting large-object GC churn.
- **Gate mirror, hardened**: a floored detached window each cycle guarantees SurfaceFlinger
  can drop back to the hardware video-overlay path (the dominant cause of dropped video
  frames while mirroring); the gate also parks — no compositing — while disconnected, and
  starts detached.
- **Decoupled frame send**: capture hands frames to a conflated (latest-wins) channel
  drained by a dedicated coroutine, so socket/network backpressure can never stall the
  screen mirror.
- **Resource shrinking** enabled for release builds.

### Fixed
- **Capture-rate undershoot**: the continuous-mode throttle reset its window to each
  frame's arrival time, rounding the target interval up to the next whole compositor
  frame — so 25 fps from a 60 fps source actually delivered ~20 fps, unevenly. A
  phase-accumulated deadline now holds a true average cadence.
- **Output distortion on non-16:9 resolutions** (e.g. 79×43): the previous pipeline
  captured at the screen aspect then resampled rows to the requested height (e.g.
  79×44 → 79×43), causing row-dropping artifacts. Capture and output now share the same
  aspect-snapped dimensions, eliminating the resample entirely.
- **Stutter / micro-freezing of captured video**: the blocking socket write ran on the
  capture thread while still holding the captured `Image`; a slow Hyperion/network stalled
  buffer recycling, filled the `ImageReader` queue and back-pressured SurfaceFlinger's
  screen mirror — freezing the display. The frame is now released immediately and the send
  is decoupled onto a conflated channel, so backpressure can no longer stall the mirror.
- **Crash when pressing STOP**: `CaptureService.onDestroy()` closed the `ImageReader` while
  the `VirtualDisplay` still fed its `Surface` and a frame callback could be in flight.
  Teardown now stops the producer first, halts callback dispatch and the gate cycle, drains
  the capture thread, and only then closes the reader; the gate's surface writes are also
  guarded against the released display.

### Changed
- **Settings autosave**: changes persist automatically (debounced, and flushed when you
  leave the screen) — the explicit "Save & Back" button is gone; press Back to return.
- **Default capture rate** is now 24 fps (aimed at 24 fps film).
- **Accent-styled toggles** with a strong focus highlight for clear D-pad navigation.
- **Colour wheel** is decoupled from the capture rate (its own FPS / spin time) and spins
  via a cached GPU layer instead of re-rasterising the full screen each frame.
- **Settings input on TV**: text/number fields are read-only while navigating, so the soft
  keyboard no longer pops up on every D-pad move. Press centre/OK to edit (which shows the
  keyboard) and Back to stop editing without leaving the screen.

[1.1]: https://github.com/Uroshino/HyperFlatSender/releases/tag/v1.1
[1.0]: https://github.com/Uroshino/HyperFlatSender/releases/tag/v1.0
