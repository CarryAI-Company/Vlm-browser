# ChromeClone

An Android WebView browser (package `co.carryai.chromeclone`) with:

- **Screen share** — MediaProjection → VirtualDisplay → ImageReader → JPEG frames pushed to the page via a JS bridge → `canvas.captureStream()` so the page receives a real MediaStream.
- **Camera** — getUserMedia passthrough with front/back switch (facingMode).
- **Bookmarks** — with default PCF demo bookmark.
- **Keep-alive** — dynamic foreground service; only runs while camera/screen-capture is active (lets the device sleep otherwise).

Default home page: `https://2026-pcf-demo.carryai.co/live-caption`

## Build

```
./gradlew assembleRelease testReleaseUnitTest lintRelease
```

43 unit tests, lint clean, APK signed with v2.

APK: `app/build/outputs/apk/release/app-release.apk` (also copied to `./app-release.apk`).

## See also

- `DEBUG.md` — screen-share black-screen investigation notes, toasts, logcat markers, test procedure.
