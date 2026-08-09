# ChromeClone — Plan: Capture-Source App (Phase 2 architecture)

**Status**: approved (2026-08-09, Samson) — full scope: unified uploader +
visual crop picker + native camera source.
**Goal**: reliably deliver real-time visual content (screen OR camera,
optionally cropped) from the Android device to the CarryAI VLM service
(`visual_llm_complete`) for live captioning/analysis.

---

## 1. Why the current architecture keeps breaking

The current pipeline:

```
MediaProjection → VirtualDisplay → ImageReader → JPEG
  → base64 → evaluateJavascript("window.__onScreenFrame('data:...base64')")
  → <img> decode → canvas.drawImage → canvas.captureStream()
  → page treats it as a getDisplayMedia stream → page sends frames to backend
```

Structural weaknesses (not bugs — design fragility):

1. **base64-over-evaluateJavascript is the bottleneck.** Every frame is a
   multi-hundred-KB string pushed through the JS bridge.
2. **evaluateJavascript fails silently.** The WebView renderer is a separate
   process; when Android kills/freezes it in the background, native keeps
   pushing frames ("capture alive" keeps logging) but JS never receives them
   → permanent black canvas. This is the root cause of the real-device
   "recovering… but never recovers" symptom.
3. **The whole design fights Android.** It fakes a screen-share MediaStream
   WebView doesn't natively support, then asks the *page* to ship frames
   onward — two fragile hops where one direct hop would do.

## 2. What the frames are actually for

Confirmed with Samson: the screen content feeds the CarryAI VLM service for
real-time analysis (live captioning). **The consumer is the AI backend, not a
human watching a preview.** Frames do not need to round-trip through the page.

Backend contract (already implemented in `visual_llm_complete`,
`server/core/fastapi_app.py`):

- `WebSocket /ws/inference`, binary protocol:
  `[4-byte big-endian JSON header length][JSON header][JPEG/PNG bytes]`
- Header fields: `instruction`, `system_prompt`, `stream_id`, `num_images`,
  optional `include_keywords` / `exclude_keywords` / `stream`.
- The **same WebSocket** returns the result:
  `{"status":"success","result":"<caption>", ...}` (similarity-gated:
  near-duplicate frames return the last result + a `warning`).

### Crop / zoom: app-side, by design

The backend has **no crop/region field** in the protocol. Cropping therefore
happens **app-side, on the JPEG, before upload**: the backend simply receives
a smaller image. Zero backend changes; the similarity gate keeps working.
Crop rect is stored as **fractions of the frame** so it survives rotation and
resolution changes.

## 3. Target architecture

```
Sources (pick ONE at a time):
  ScreenCaptureService  — MediaProjection → VirtualDisplay → ImageReader
  CameraCaptureService  — CameraX ImageAnalysis (no MediaProjection at all)
        │
        ▼  (optional crop: fractions → pixel rect, applied pre-encode)
      JPEG bytes
        │
        ▼
  FrameUploader (OkHttp WebSocket)   ← single shared uploader
      ├─ connect ws(s)://<server>/ws/inference
      ├─ send [4-byte header len][JSON header][JPEG]
      ├─ pacing: next frame only after previous result + min interval
      ├─ reconnect with exponential backoff
      └─ captions out: evaluateJavascript("window.__onCaption(...)")
                       └─ tiny TEXT only — safe even on a flaky renderer
```

Key properties:

- **No frame ever crosses the JS bridge.** Only small caption strings do.
  A dead/frozen renderer can at worst hide caption text; capture + upload
  continue regardless. The black-screen failure mode is eliminated by
  construction — there is no canvas that can go black.
- **Camera is simpler than screen**: no consent prompts, no VirtualDisplay
  death modes — CameraX frames in a foreground service straight to the
  uploader.
- **Backend contract unchanged.** Zero changes to `visual_llm_complete`.

## 4. Components

| Component | Responsibility |
| --- | --- |
| `FrameUploader` (new) | OkHttp WS client; binary protocol; reconnect/backoff; response-paced sending; latest-wins frame slot |
| `CaptureConfig` (new) | SharedPreferences settings: server URL, instruction, system prompt, min interval, upload on/off, crop rect (fractions) |
| `ScreenCaptureService` (modify) | Routes JPEGs to the uploader; applies crop pre-encode |
| `CameraCaptureService` (new, Phase 2) | CameraX ImageAnalysis → JPEG → uploader; front/back switch; `foregroundServiceType="camera"` |
| `CropPickerActivity` (new, Phase 2) | Frozen last frame + draggable/resizable rect overlay; works for BOTH sources |
| `MainActivity` (modify) | Source picker (Screen / Camera), settings dialog, `__onCaption` relay |
| `bridge.js` (modify) | `window.__onCaption` callback + `chromeclone-caption` CustomEvent for pages |
| `ic_settings` + layout button | Settings entry point |

Dependencies: `com.squareup.okhttp3:okhttp`; CameraX (`camera-camera2`,
`camera-lifecycle`, `camera-view`) for Phase 2.

## 5. Phased implementation

**Phase 1 — FrameUploader + CaptureConfig + settings (this milestone)**
- FrameUploader: binary protocol, reconnect/backoff, response pacing,
  latest-wins slot, caption + state listeners.
- CaptureConfig: all settings incl. crop rect storage (fractions).
- ScreenCaptureService wiring: crop pre-encode + frame routing to uploader.
- MainActivity: settings dialog (server URL / instruction / interval /
  upload toggle), `__onCaption` relay.
- bridge.js: `window.__onCaption` + CustomEvent.
- Unit tests: protocol encode round-trip, WS URL normalization, crop rect
  clamping.

**Phase 2 — camera source + crop picker**
- CameraCaptureService (CameraX) feeding the same uploader; source picker
  in the bottom bar (`Screen` / `Camera`, mutually exclusive).
- CropPickerActivity: draggable/resizable rect over a frozen frame; saves
  fractions; applies to both sources.
- Manifest: `FOREGROUND_SERVICE_CAMERA` + service type.

**Phase 3 — frontend adaptation (visual_llm_complete repo, optional)**
- live-caption page prefers bridge captions (`window.__onCaption` /
  `chromeclone-caption` event) when the bridge is present; falls back to its
  own WS otherwise. (Frontend already models `webcam`/`screenshare`/`rtsp`
  sources, so the concept maps cleanly.)

**Phase 4 — cleanup**
- Remove/flag the legacy base64 frame path, canvas/captureStream shim
  internals, JS-delivery watchdog/heartbeat, and ladder rungs that existed
  only to rescue JS delivery.
- Keep: MediaProjection pipeline, KeepAliveService, surface-swap for the
  VirtualDisplay (Google issue 370625489), full-restart for dead-on-arrival
  sessions.

## 6. Frame pacing & bandwidth

VLM inference takes seconds per frame; uploading faster only saturates the
queue. FrameUploader sends the NEXT frame only after the previous result
returns, clamped to a configurable minimum interval (default 1000 ms). The
backend's similarity gate drops near-duplicates. Effective cadence ≈
inference speed — exactly what live captioning needs.

## 7. Verification criteria

- [ ] Phase 1: unit tests green; emulator smoke — capture unaffected with
      uploader mis/un-configured; captions relay when a server answers.
- [ ] Full loop: capture → upload → caption → page log (needs reachable
      backend, e.g. LAN test box).
- [ ] Switch apps repeatedly while capturing → upload continues
      (no dependence on the WebView renderer).
- [ ] Server unreachable → graceful offline state, auto-reconnect, capture
      unaffected.
- [ ] Crop: selected region matches uploaded JPEG (visual check), survives
      rotation.
- [ ] Camera: front/back switch works; frames upload; switching sources
      stops the other cleanly.
- [ ] Real device: original bug scenario shows no black screen — because
      there is no canvas.
- [ ] `./gradlew assembleRelease testReleaseUnitTest lintRelease` green.

## 8. Risks & rollback

- **Risk**: WS reachability over mobile network (TLS/ports). Mitigation:
  server URL configurable; LAN first; backend already serves HTTPS 9090 /
  API 5050.
- **Risk**: frontend page expects its own WS flow. Mitigation: Phase 3 is
  additive; upload mode is an extra path until proven on device.
- **Rollback**: upload mode is a setting; off restores legacy behaviour
  (legacy code stays until Phase 4).
