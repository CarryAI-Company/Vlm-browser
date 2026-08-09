# ChromeClone — Screen Share Black Screen Debug Notes

**App**: ChromeClone (`co.carryai.chromeclone`) — a WebView browser with camera + screen-share.

## The bug being investigated

Screen share (MediaProjection) **turns black**:
1. Originally: fine on first app-switch, black on 2nd app-switch.
2. After a watchdog change: **black from the very first frame** ("No frames from display" toast).

## Architecture

```
MediaProjection --VirtualDisplay--> ImageReader (capture thread)
    --> Bitmap --> JPEG --> base64 data URL
    --> evaluateJavascript("window.__onScreenFrame(dataUrl)")
    --> <img> decode --> canvas.drawImage --> canvas.captureStream() (the "screen share" stream the page receives)
```

Native: `ScreenCaptureService.java` (foreground service, `mediaProjection` type).
Bridge: `app/src/main/assets/bridge.js` (injected into allowlisted origins only).
Activity: `MainActivity.java` (WebView setup, lifecycle, keep-alive).

## Diagnostic toasts (no adb needed)

- `No frames from display — recovering…` → `requestFrame()` got `acquireLatestImage() == null`.
  **IMPORTANT**: this is NORMAL during startup (VirtualDisplay needs ~300ms for the first frame).
  The recovery surface-swap must NOT run here — it breaks the fresh display → black from frame 1.
  (Fixed in latest: requestFrame no longer recovers; only the watchdog does, and it arms only after `firstFrameSeen`.)
- `Screen capture stalled — recovering…` → watchdog: frames stopped for >3s AFTER at least one frame was seen. Surface-swap recovery (ScreenStream's fix for Google issue 370625489).

## Logcat

```
adb logcat -s ChromeClone ScreenCaptureService
```

Key markers:
- `First frame captured` — pipeline producing frames (if absent: VirtualDisplay never pumped → native capture issue)
- `capture alive: N frames pushed` — every 100 frames
- `requestFrame: no frame yet (normal during startup)` — benign
- `Watchdog: frames stalled ... attempting surface-swap recovery` — real stall detected
- `MediaProjection onStop fired` — system revoked capture
- `Recovery surface-swap done` — recovery ran

## Test procedure

1. Install APK, open app (defaults to PCF demo).
2. Tap Share Screen, grant the MediaProjection prompt.
3. Watch toasts + logcat:
   - `First frame captured` should appear within ~1s → screen should be live.
   - Switch apps 2-3 times; check for stalls + recovery.
4. Report: which toasts appear, and whether `First frame captured` ever appears.

## Known-good reference

`ScreenStream` (github.com/dkrivoruchko/ScreenStream) handles the same
VirtualDisplay resize/stall issue with:
```
virtualDisplay.surface = null
virtualDisplay.resize(w, h, dpi)
virtualDisplay.surface = imageReader.surface
```
(ScreenStream `BitmapCapture.kt`, Google issue 370625489.)

## Emulator testing (NOT viable on this host)

This dev VM (Proxmox LXC, x86_64, no /dev/kvm) cannot run the Android emulator
in reasonable time (software boot ~30min, SystemUI ANR). redroid fails with
`pivot_root: permission denied` (container runtime restriction). Real-device
adb testing is the reliable path.

## Files

- `app/src/main/java/co/carryai/chromeclone/ScreenCaptureService.java` — capture pipeline + watchdog + recovery
- `app/src/main/java/co/carryai/chromeclone/MainActivity.java` — lifecycle (onPause/onResume), keep-alive, bridge
- `app/src/main/java/co/carryai/chromeclone/KeepAliveService.java` — dynamic keep-alive (only while camera/capture active)
- `app/src/main/assets/bridge.js` — getDisplayMedia shim, __onScreenFrame, __chromeCloneResume, __onScreenRecovering
- `app/src/main/res/layout/activity_main.xml` — bottom nav buttons
- `app/src/test/java/...` — 43 unit tests (all green)
