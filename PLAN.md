# ChromeClone — Plan: Native Direct Frame Upload (Phase 2 architecture)

**Status**: approved direction (2026-08-09, Samson)
**Goal**: deliver real-time screen content from the Android device to the
CarryAI VLM service (`visual_llm_complete`) reliably, so the live-caption
demo works without the WebView black-screen failure mode.

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
   multi-hundred-KB string pushed through the JS bridge. The code itself
   documents this ("WebView JS bridge is the bottleneck").
2. **evaluateJavascript fails silently.** The WebView renderer is a separate
   process; when Android kills/freezes it in the background, native keeps
   pushing frames ("capture alive" keeps logging) but JS never receives them
   → permanent black canvas with no visible error. This is the root cause of
   the real-device "recovering… but never recovers" symptom.
3. **The whole design fights Android.** It fakes a screen-share MediaStream
   that WebView doesn't natively support, then asks the *page* to ship the
   frames onward — two fragile hops where one direct hop would do.

Every fix so far (watchdog, surface-swap, heartbeat, full restart) patches
around weakness #2. The correct fix is to remove the fragile hop entirely.

## 2. What the frames are actually for

Confirmed with Samson: the screen content feeds the CarryAI VLM service for
real-time analysis (live captioning). **The consumer is the AI backend, not a
human watching a preview.** Frames do not need to round-trip through the
page at all.

Backend contract (already implemented in `visual_llm_complete`,
`server/core/fastapi_app.py`):

- `WebSocket /ws/inference`, binary protocol:
  `[4-byte big-endian JSON header length][JSON header][JPEG/PNG bytes]`
- Header fields: `instruction`, `system_prompt`, `stream_id`, `num_images`,
  optional `include_keywords` / `exclude_keywords` / `stream`.
- The **same WebSocket** returns the result:
  `{"status":"success","result":"<caption>", ...}`.
- Backend has a similarity gate + pacing designed for continuous frame
  streaming (it skips near-identical frames).

## 3. Target architecture

```
ScreenCaptureService (KEEP — rock solid)
  MediaProjection → VirtualDisplay → ImageReader → JPEG bytes
        │
        ├── [REMOVE] base64 → evaluateJavascript frame push
        │            canvas/captureStream shim, watchdog-for-JS, heartbeat
        │
        └── [NEW] FrameUploader (Java, OkHttp WebSocket)
              ├─ connect ws(s)://<server>/ws/inference
              ├─ send: [4-byte header len][JSON header][JPEG]
              ├─ receive: {"status":"success","result": caption}
              ├─ pacing: next frame only after previous result (or min interval)
              └─ evaluateJavascript("window.__onCaption('...')")
                       └─ tiny text only — safe even on a flaky renderer

Bridge / page:
  - getDisplayMedia shim stays, but resolves immediately with a placeholder
    stream (native capture is already running) — keeps existing page flow.
  - New window.__onCaption(text, meta) callback for the page to render
    captions from native instead of its own WS.
  - Page changes (frontend-demo repo) come in Phase 3 and are optional:
    until then, native captions are simply available to any page that wants
    them.
```

Key properties:

- **No frame ever crosses the JS bridge.** Only small caption strings do.
  A dead/frozen renderer can at worst hide the caption text; capture and
  upload continue regardless. The black-screen failure mode is eliminated by
  construction — there is no canvas that can go black.
- **Capture survives backgrounding** (existing mediaProjection foreground
  service) — which is the actual screen-share use case: user switches apps,
  the VLM keeps seeing the screen.
- **Backend contract unchanged.** Zero changes to `visual_llm_complete`.

## 4. Components

| Component | Location | Responsibility |
| --- | --- | --- |
| `FrameUploader` (new) | `app/src/main/java/.../FrameUploader.java` | OkHttp WS client; binary protocol encode/decode; reconnect with backoff; frame pacing |
| `CaptureConfig` (new) | `app/src/main/java/.../CaptureConfig.java` | SharedPreferences-backed settings: server URL, instruction, system prompt, min frame interval, upload mode on/off |
| `ScreenCaptureService` (modify) | existing | When upload mode on: hand JPEG bytes to FrameUploader instead of (or alongside) the JS push |
| `MainActivity` (modify) | existing | Settings UI entry; caption relay `__onCaption`; keep all lifecycle handling |
| `bridge.js` (modify) | existing | `window.__onCaption`; getDisplayMedia resolves placeholder stream immediately in upload mode |
| Settings screen (new) | `res/layout/settings.xml` + dialog | Server URL, instruction, interval, mode toggle |

Dependencies to add: `com.squareup.okhttp3:okhttp` (WebSocket client).

## 5. Phased implementation

**Phase 1 — FrameUploader + native WS path (no behaviour change by default)**
- FrameUploader with the binary protocol, reconnect/backoff, response-paced
  sending, min-interval guard.
- Settings storage with defaults (server URL, instruction, 1 fps min
  interval). Upload mode OFF by default.
- Unit tests: header encoding round-trip, pacing logic, config parsing.
- *Verifiable without the page*: run with upload mode on, watch logcat for
  frame-sent/caption-received, confirm against server logs.

**Phase 2 — wire capture → uploader, caption → page**
- ScreenCaptureService routes frames to FrameUploader when mode is on.
- Caption results delivered via `window.__onCaption(text, {ts, streamId})`.
- getDisplayMedia shim in upload mode resolves immediately (placeholder
  stream) so existing pages don't hang waiting for a canvas.
- Test on emulator: full loop — capture → upload → caption → page log.

**Phase 3 — frontend adaptation (visual_llm_complete repo, optional)**
- live-caption page prefers bridge captions (`window.__onCaption`) when the
  bridge is present; falls back to its own WS otherwise.
- This makes the demo fully native-uploaded on ChromeClone while staying
  compatible with desktop browsers.

**Phase 4 — cleanup**
- Remove or flag-legacy the old base64 frame path, canvas/captureStream
  shim internals, the JS-delivery watchdog/heartbeat, and the recovery
  ladder rungs that only existed to rescue JS delivery.
- Keep: MediaProjection pipeline, KeepAliveService, surface-swap for the
  VirtualDisplay itself (Google issue 370625489 is real), full-restart for
  dead-on-arrival sessions.

## 6. Frame pacing & bandwidth

VLM inference takes seconds per frame; 10 fps upload would only saturate the
queue. FrameUploader sends the NEXT frame only after the previous result
returns, clamped to a configurable minimum interval (default ~1 s). The
backend's similarity gate then drops near-duplicates. Effective cadence ≈
inference speed, which is exactly what live captioning needs.

## 7. Verification criteria

- [ ] Emulator: start capture with upload mode on → server log shows frames
      arriving on `/ws/inference`; captions return over the same WS.
- [ ] Switch apps repeatedly while capturing → upload continues uninterrupted
      (no dependence on the WebView renderer).
- [ ] Kill the WebView renderer (dev option / low RAM) → upload continues;
      captions resume when the page returns.
- [ ] Server unreachable → graceful offline state, auto-reconnect, capture
      unaffected.
- [ ] Real device: the original bug scenario (share screen → switch apps
      2-3 times) shows no black screen — because there is no canvas.
- [ ] `./gradlew assembleRelease testReleaseUnitTest lintRelease` green.

## 8. Risks & rollback

- **Risk**: OkHttp WS to the service over mobile network (TLS/ports).
  Mitigation: server URL configurable; test with LAN first; backend already
  serves HTTPS on 9090 / API on 5050.
- **Risk**: frontend page expects its own WS flow. Mitigation: Phase 3 is
  additive; until it lands, the page still works via its own WS — upload
  mode is an extra path, not a replacement, until proven on device.
- **Rollback**: upload mode is a setting; switching it off restores the
  legacy path entirely (legacy code stays until Phase 4).
