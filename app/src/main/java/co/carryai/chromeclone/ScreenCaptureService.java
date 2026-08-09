package co.carryai.chromeclone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that owns the MediaProjection and pumps screen frames
 * into the WebView's JavaScript shim as base64-encoded JPEG data URLs.
 *
 * Pipeline:
 *   MediaProjection -> VirtualDisplay -> ImageReader (RGBA_8888)
 *     -> Bitmap -> JPEG compress -> base64 -> webView.evaluateJavascript(
 *            "window.__onScreenFrame(...)").
 *
 * The JS side assembles frames onto a canvas and exposes
 * canvas.captureStream() as the result of navigator.mediaDevices.getDisplayMedia().
 */
public class ScreenCaptureService extends Service {

    public static final String ACTION_START = "co.carryai.chromeclone.action.START_CAPTURE";
    public static final String ACTION_STOP = "co.carryai.chromeclone.action.STOP_CAPTURE";

    public static final String EXTRA_RESULT_CODE = "extra_result_code";
    public static final String EXTRA_RESULT_DATA = "extra_result_data";

    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 0xC0DE;

    /** Max frames per second pushed to JS (kept modest: WebView JS bridge is the bottleneck). */
    private static final long FRAME_INTERVAL_MS = 100; // ~10 fps
    /** JPEG quality for frame compression. */
    private static final int JPEG_QUALITY = 62;
    /** Downscale factor applied to captured frames to keep the JS bridge responsive. */
    private static final int MAX_FRAME_DIM = 960;

    /** The currently-running instance, so MainActivity can wire in its WebView. */
    private static WeakReference<ScreenCaptureService> sInstance = new WeakReference<>(null);

    /**
     * The WebView receiving __onScreenFrame(...) calls, held weakly so this
     * long-lived service never leaks the Activity/WebView after rotation or
     * finish. MainActivity re-attaches on recreate and detaches in onDestroy().
     */
    private WeakReference<WebView> webViewRef = new WeakReference<>(null);
    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private Handler mainHandler;

    private volatile boolean capturing = false;
    /**
     * Guards teardown against re-entrancy: MediaProjection.Callback.onStop()
     * calls stopCapture(), and stopCapture() itself calls mediaProjection.stop(),
     * which can re-enter onStop(). The CAS makes any nested call a no-op, and all
     * teardown work is posted to the main handler so it runs on one thread.
     */
    private final AtomicBoolean stopInProgress = new AtomicBoolean(false);
    private volatile long lastFrameAt = 0L;
    private long framesPushed = 0L;
    /** True once at least one frame has been captured (watchdog arm signal). */
    private volatile boolean firstFrameSeen = false;
    /** When the watchdog last rebuilt the display (rate-limit recoveries). */
    private volatile long lastRecoverAt = 0L;
    /**
     * Escalation state of the recovery ladder:
     *   0 = healthy; next stall tries a surface-swap
     *   1 = surface-swap tried; next stall refreshes the pipeline
     *       (fresh ImageReader on the SAME VirtualDisplay)
     *   2 = refresh tried; next stall requests a FULL restart (a brand-new
     *       MediaProjection session via the consent prompt — the only
     *       guaranteed recovery when the mirror is truly dead)
     *   3 = full restart in flight (consent prompt showing); watchdog idles
     * Reset to 0 when a SUSTAINED frame flow returns.
     *
     * NOTE: a VirtualDisplay can never be recreated on an existing
     * MediaProjection — Android 14+ throws SecurityException ("Don't take
     * multiple captures by invoking MediaProjection#createVirtualDisplay
     * multiple times on the same instance") and invalidates the whole
     * projection. That is why rung 2 asks the Activity for a fresh session
     * instead of rebuilding the display itself.
     */
    private volatile int recoveryLevel = 0;
    /**
     * Full-restart attempts within the current (unhealthy) stretch. Capped so
     * a fundamentally broken device cannot spam the consent prompt forever;
     * resets to 0 when a sustained frame flow returns.
     */
    private volatile int restartCount = 0;
    /** Hard cap on consecutive full restarts before giving up. */
    private static final int MAX_FULL_RESTARTS = 2;
    /**
     * Set by MainActivity: invoked when the watchdog escalates all the way to
     * a full restart and needs the Activity to show the MediaProjection
     * consent prompt again. Called on the capture thread — implementations
     * must hop to the UI thread.
     */
    public interface RestartHandler {
        void onCaptureRestartNeeded();
    }
    private volatile RestartHandler restartHandler;

    public void setRestartHandler(RestartHandler handler) {
        restartHandler = handler;
    }

    /**
     * True while the Activity is in the foreground. The watchdog only fires
     * when visible: in the background the system legitimately pauses the
     * VirtualDisplay mirror (Android 14/15 behaviour), so firing the watchdog
     * there just wastes recovery attempts and eats the rate-limit window.
     */
    private volatile boolean activityVisible = false;
    /**
     * When the current capture session started (System.currentTimeMillis).
     * Used by the startup-stall detector: a VirtualDisplay that never pumps
     * its first frame within the budget is dead on arrival and cannot be
     * fixed by the mid-session ladder (which is gated on firstFrameSeen).
     */
    private volatile long captureStartedAt = 0L;
    /** True once the startup-stall detector has tried its surface-swap kick. */
    private volatile boolean startupSwapTried = false;
    /**
     * Startup-stall budget: on healthy devices the first frame arrives within
     * ~300ms-1.5s. Past this with zero frames the display is considered dead
     * on arrival and recovery starts (surface-swap kick, then full restart).
     */
    private static final long STARTUP_FIRST_FRAME_MS = 5000L;
    /** Second startup pass: still no first frame => request a full restart. */
    private static final long STARTUP_RESTART_MS = 9000L;

    private int width;
    private int height;
    private int densityDpi;
    private Bitmap reusableBitmap;

    public static ScreenCaptureService getInstance() {
        return sInstance.get();
    }

    /**
     * Stall watchdog: while capturing, if no frame has arrived for a while
     * (after at least one frame was seen), the VirtualDisplay mirror has
     * likely paused (typical after switching apps on Android 14/15). Try the
     * surface-swap recovery, rate-limited. Scheduled on the capture thread.
     */
    private static final long WATCHDOG_INTERVAL_MS = 2000L;
    private static final long STALL_THRESHOLD_MS = 3000L;
    /**
     * Minimum gap between recovery attempts — long enough for one attempt to
     * start producing frames again, short enough to escalate quickly when it
     * doesn't (the old flat 30s cooldown left the user staring at a black
     * screen for half a minute when the first attempt failed).
     */
    private static final long RECOVER_MIN_GAP_MS = 4000L;
    private final Runnable captureWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!capturing) return;
            long now = System.currentTimeMillis();

            // ------------------------------------------------------------
            // Startup-stall path: the VirtualDisplay NEVER produced its first
            // frame. The mid-session ladder below is gated on firstFrameSeen,
            // so without this branch a dead-on-arrival display would sit black
            // forever with zero recovery (the exact "recovering but only ever
            // black from the start" report). A display that never pumps cannot
            // be fixed by a surface-swap alone, so we give one cheap surface-
            // swap kick (some devices need the re-mirror nudge), then go
            // straight to a full restart (fresh MediaProjection session).
            // ------------------------------------------------------------
            if (!firstFrameSeen) {
                long sinceStart = now - captureStartedAt;
                if (activityVisible && now - lastRecoverAt > RECOVER_MIN_GAP_MS) {
                    if (!startupSwapTried && sinceStart >= STARTUP_FIRST_FRAME_MS) {
                        startupSwapTried = true;
                        lastRecoverAt = now;
                        android.util.Log.w("ScreenCaptureService",
                                "Startup stall: no first frame after " + sinceStart
                                        + "ms, attempting surface-swap kick");
                        showToast("Screen capture stalled — recovering…");
                        recoverCapture();
                    } else if (startupSwapTried && sinceStart >= STARTUP_RESTART_MS) {
                        lastRecoverAt = now;
                        android.util.Log.w("ScreenCaptureService",
                                "Startup stall: still no first frame after " + sinceStart
                                        + "ms, requesting full capture restart");
                        showToast("Screen capture stalled — restarting…");
                        requestFullRestart();
                        // requestFullRestart tears down + restarts; the fresh
                        // session resets captureStartedAt/startupSwapTried in
                        // startCaptureInternal, so the detector re-arms.
                    }
                }
                captureHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
                return;
            }

            if (firstFrameSeen && activityVisible
                    && now - lastFrameAt > STALL_THRESHOLD_MS
                    && now - lastRecoverAt > RECOVER_MIN_GAP_MS) {
                lastRecoverAt = now;
                // Escalating recovery ladder (rungs 0-2 are legal on
                // Android 14+: they never call createVirtualDisplay again on
                // the same projection). recoveryLevel resets to 0 when a
                // SUSTAINED frame flow returns (see newFrameListener).
                //
                // NOTE: the watchdog deliberately does NOT fire while the
                // Activity is backgrounded — the system legitimately pauses
                // the VirtualDisplay mirror there, and burning recovery
                // attempts in the background wastes the rate-limit window.
                // wakeIfStalled() (called from onResume) handles the
                // background->foreground stall immediately instead.
                switch (recoveryLevel) {
                    case 0:
                        recoveryLevel = 1;
                        android.util.Log.w("ScreenCaptureService",
                                "Watchdog: frames stalled for " + (now - lastFrameAt)
                                        + "ms, attempting surface-swap recovery");
                        showToast("Screen capture stalled — recovering…");
                        recoverCapture();
                        break;
                    case 1:
                        recoveryLevel = 2;
                        android.util.Log.w("ScreenCaptureService",
                                "Watchdog: still stalled after surface-swap ("
                                        + (now - lastFrameAt)
                                        + "ms), refreshing capture pipeline");
                        showToast("Screen capture stalled — refreshing…");
                        refreshCapturePipeline();
                        break;
                    case 2:
                        recoveryLevel = 3; // Restart in flight; idles until flow returns.
                        android.util.Log.w("ScreenCaptureService",
                                "Watchdog: still stalled after pipeline refresh ("
                                        + (now - lastFrameAt)
                                        + "ms), requesting full capture restart");
                        showToast("Screen capture stalled — restarting…");
                        requestFullRestart();
                        break;
                    default:
                        // Level 3: a full restart (consent prompt) is already
                        // in flight — nothing more to do until it completes
                        // and a real frame flow resumes.
                        android.util.Log.i("ScreenCaptureService",
                                "Watchdog: stalled, full restart already in flight");
                        break;
                }
            }
            captureHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    /**
     * Builds the ImageReader.OnImageAvailableListener used by both the initial
     * ImageReader and any replacement created during pipeline recovery. Pulled
     * into a factory so recoverCapture/refreshCapturePipeline always wire the
     * identical frame-handling logic.
     */
    private ImageReader.OnImageAvailableListener newFrameListener() {
        return reader -> {
            long now = System.currentTimeMillis();
            if (now - lastFrameAt < FRAME_INTERVAL_MS) {
                // Drain and drop the frame to keep the pipeline unblocked.
                Image drop = reader.acquireLatestImage();
                if (drop != null) drop.close();
                return;
            }
            lastFrameAt = now;
            // Only reset the recovery ladder on SUSTAINED healthy flow: a
            // surface-swap/rebuild always pumps one token re-mirror frame
            // immediately, which must NOT count as "recovered" — otherwise the
            // watchdog would never escalate past surface-swap and the pipeline
            // would loop "recovering…" forever without recovering (the exact
            // symptom reported on the real device). A frame that arrives well
            // after the last recovery attempt proves the pipeline really flows.
            if (now - lastRecoverAt > STALL_THRESHOLD_MS) {
                recoveryLevel = 0;
                restartCount = 0;
            }
            if (!firstFrameSeen) {
                firstFrameSeen = true;
                android.util.Log.i("ScreenCaptureService", "First frame captured");
            }
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                byte[] jpeg = imageToJpeg(image);
                if (jpeg != null) {
                    String dataUrl = Base64Utils.toJpegDataUrl(jpeg);
                    pushFrameToJs(dataUrl);
                    framesPushed++;
                    if (framesPushed % 100 == 0) {
                        android.util.Log.i("ScreenCaptureService",
                                "capture alive: " + framesPushed + " frames pushed");
                    }
                } else {
                    android.util.Log.w("ScreenCaptureService", "imageToJpeg returned null");
                }
            } catch (Throwable t) {
                // Frame-level failures must never kill the service.
                android.util.Log.e("ScreenCaptureService", "frame error", t);
            } finally {
                image.close();
            }
        };
    }

    /**
     * Tracks whether the host Activity is currently resumed. The stall
     * watchdog only acts while visible; in the background the system
     * legitimately pauses the VirtualDisplay mirror (Android 14/15), so
     * recovery there would just waste attempts. MainActivity calls this from
     * onResume/onPause.
     */
    public void setActivityVisible(boolean visible) {
        activityVisible = visible;
    }

    /**
     * Surface-swap recovery (ScreenStream's fix for Google issue 370625489):
     * detach the VirtualDisplay surface, resize to the same size (forces the
     * display to re-mirror), re-attach, then push a fresh frame. If this does
     * not un-stall the pipeline, the watchdog escalates to
     * {@link #refreshCapturePipeline()}.
     */
    private void recoverCapture() {
        captureHandler.post(() -> {
            if (!capturing || virtualDisplay == null || imageReader == null) return;
            try {
                notifyJs("window.__onScreenRecovering && window.__onScreenRecovering();");
                virtualDisplay.setSurface(null);
                virtualDisplay.resize(width, height, densityDpi);
                virtualDisplay.setSurface(imageReader.getSurface());
                // Deliberately NOT touching lastFrameAt here: the watchdog
                // decides when to escalate based on how long it has been
                // since a REAL frame arrived. Faking lastFrameAt would hide
                // the stall and loop surface-swaps forever (exactly the
                // "recovering… but never recovers" symptom). lastRecoverAt
                // already rate-limits the attempts.
                Image image = imageReader.acquireLatestImage();
                if (image != null) {
                    try {
                        byte[] jpeg = imageToJpeg(image);
                        if (jpeg != null) {
                            pushFrameToJs(Base64Utils.toJpegDataUrl(jpeg));
                        }
                    } catch (Throwable t) {
                        android.util.Log.e("ScreenCaptureService", "recover frame error", t);
                    } finally {
                        image.close();
                    }
                }
                android.util.Log.i("ScreenCaptureService", "Recovery surface-swap done");
            } catch (Throwable t) {
                android.util.Log.e("ScreenCaptureService", "recoverCapture failed", t);
            }
        });
    }

    /**
     * Second-rung recovery when a plain surface-swap did not un-stall the
     * pipeline: rebuild the CONSUMER side only — a fresh ImageReader at the
     * same size plus the null->resize->attach surface dance — while keeping
     * the SAME VirtualDisplay instance. This is always legal on Android 14+
     * (no second createVirtualDisplay call), unlike the old
     * rebuildVirtualDisplay(), which threw SecurityException and killed the
     * MediaProjection entirely.
     */
    private void refreshCapturePipeline() {
        captureHandler.post(() -> {
            if (!capturing || virtualDisplay == null || mediaProjection == null) return;
            try {
                notifyJs("window.__onScreenRecovering && window.__onScreenRecovering();");
                final ImageReader newReader = ImageReader.newInstance(
                        width, height, PixelFormat.RGBA_8888, 2);
                newReader.setOnImageAvailableListener(
                        newFrameListener(), captureHandler);
                virtualDisplay.setSurface(null);
                virtualDisplay.resize(width, height, densityDpi);
                virtualDisplay.setSurface(newReader.getSurface());
                ImageReader oldReader = imageReader;
                imageReader = newReader;
                if (oldReader != null) {
                    try { oldReader.close(); } catch (Throwable ignored) {}
                }
                if (reusableBitmap != null) {
                    reusableBitmap.recycle();
                    reusableBitmap = null;
                }
                android.util.Log.i("ScreenCaptureService",
                        "Recovery pipeline refresh done (fresh ImageReader)");
            } catch (Throwable t) {
                android.util.Log.e("ScreenCaptureService", "refreshCapturePipeline failed", t);
            }
        });
    }

    /**
     * Last rung: the VirtualDisplay mirror is truly dead (both surface-swap
     * and pipeline refresh failed). Android 14+ forbids recreating a
     * VirtualDisplay on an existing MediaProjection, so the only guaranteed
     * recovery is a BRAND-NEW MediaProjection session: tear down the current
     * pipeline cleanly, then ask the Activity to show the consent prompt
     * again (the resulting ACTION_START intent rebuilds everything from
     * scratch and frames resume into the SAME canvas, so the page's stream
     * revives without a page reload).
     *
     * Capped at MAX_FULL_RESTARTS per unhealthy stretch; the counter resets
     * when a sustained frame flow returns (newFrameListener).
     */
    private void requestFullRestart() {
        if (restartCount >= MAX_FULL_RESTARTS) {
            android.util.Log.e("ScreenCaptureService",
                    "Full restart cap reached (" + MAX_FULL_RESTARTS
                            + ") — giving up; user should re-tap Share Screen");
            showToast("Screen capture could not be recovered");
            // The pipeline is dead: tear it down normally (this emits
            // __onScreenEnded so the page stops its stream) and stop the
            // foreground service so its notification doesn't linger forever.
            stopCapture();
            stopSelf();
            return;
        }
        restartCount++;
        RestartHandler handler = restartHandler;
        if (handler == null) {
            android.util.Log.e("ScreenCaptureService",
                    "requestFullRestart: no restart handler registered");
            recoveryLevel = 0; // Fall back to the ladder start.
            return;
        }
        // Tell the page what's happening, then tear down the dead pipeline.
        // Silent teardown (NO __onScreenEnded): the JS shim must keep its
        // canvas/stream objects alive so the new session's __onScreenStarted
        // + first frame revives the same preview.
        notifyJs("window.__onScreenRecovering && window.__onScreenRecovering();");
        android.util.Log.w("ScreenCaptureService",
                "requestFullRestart: tearing down dead pipeline, re-requesting MediaProjection");
        // Full teardown on the main handler, then hand off to the Activity
        // for the consent prompt.
        stopCaptureSilently();
        mainHandler.post(() -> {
            try {
                handler.onCaptureRestartNeeded();
            } catch (Throwable t) {
                android.util.Log.e("ScreenCaptureService", "restart handler failed", t);
                recoveryLevel = 0;
            }
        });
    }

    /** Attach the WebView that will receive __onScreenFrame(...) calls. */
    public void attachWebView(WebView view) {
        this.webViewRef = new WeakReference<>(view);
        // If capture already started, the __onScreenStarted signal may have been
        // dropped before attach — re-send so the JS shim flips its active flag.
        if (capturing) {
            notifyJs("window.__onScreenStarted && window.__onScreenStarted();");
        }
    }

    /** Detach the WebView (called from MainActivity.onDestroy) to avoid leaks. */
    public void detachWebView() {
        webViewRef.clear();
    }

    public boolean isCapturing() {
        return capturing;
    }

    /**
     * Forces one fresh frame to be pushed to the JS side immediately.
     * Used on activity resume: when the app returns from the background the
     * VirtualDisplay may have paused producing frames, leaving the canvas
     * (and the captureStream track the page holds) black.
     */
    public void requestFrame() {
        if (!capturing || imageReader == null) return;
        captureHandler.post(() -> {
            Image image = imageReader.acquireLatestImage();
            if (image == null) {
                // Normal during startup: the VirtualDisplay takes a few hundred
                // ms to pump the first frame. Do NOT recover here — a surface
                // swap at this point breaks the freshly-created display and
                // produces a black screen from the very start. The watchdog
                // (which only arms after firstFrameSeen) handles true stalls.
                android.util.Log.i("ScreenCaptureService",
                        "requestFrame: no frame yet (normal during startup)");
                return;
            }
            try {
                byte[] jpeg = imageToJpeg(image);
                if (jpeg != null) {
                    pushFrameToJs(Base64Utils.toJpegDataUrl(jpeg));
                    android.util.Log.i("ScreenCaptureService",
                            "requestFrame: pushed " + jpeg.length + " bytes");
                } else {
                    android.util.Log.w("ScreenCaptureService", "requestFrame: jpeg was null");
                }
            } catch (Throwable t) {
                android.util.Log.e("ScreenCaptureService", "requestFrame error", t);
            } finally {
                image.close();
            }
        });
    }

    /**
     * Wakes up a stalled VirtualDisplay using ScreenStream's surface-swap
     * workaround (Google issue 370625489). Detaches the surface, resizes to
     * the same size (a no-op size-wise but forces the display to re-mirror),
     * then re-attaches. Call this on resume even when the size didn't change —
     * it's what actually un-stalls the pipeline after backgrounding.
     */
    public void wakeUpVirtualDisplay() {
        if (!capturing || virtualDisplay == null || imageReader == null) return;
        captureHandler.post(() -> {
            try {
                android.util.Log.i("ScreenCaptureService",
                        "wakeUpVirtualDisplay: surface swap to re-mirror display");
                virtualDisplay.setSurface(null);
                virtualDisplay.resize(width, height, densityDpi);
                virtualDisplay.setSurface(imageReader.getSurface());
                lastFrameAt = 0L; // Allow the next frame through immediately.
                android.util.Log.i("ScreenCaptureService", "wakeUpVirtualDisplay: done");
            } catch (Throwable t) {
                android.util.Log.e("ScreenCaptureService", "wakeUpVirtualDisplay failed", t);
            }
        });
        requestFrame();
    }

    /**
     * Proactive wake for activity resume: un-stalls the VirtualDisplay
     * IMMEDIATELY when the pipeline is provably stalled, instead of waiting
     * ~5s for the watchdog to notice.
     *
     * The three guards make this safe where the old onResume call to
     * wakeUpVirtualDisplay() was not:
     *   1. firstFrameSeen  — never touches a freshly-created display (the
     *      permission-prompt onResume fires right after startCapture; a swap
     *      there is exactly what caused the original black-from-start bug).
     *   2. stalled         — a still-flowing pipeline needs no swap; touching
     *      it risks a frame-drop hiccup for nothing.
     *   3. rate limit      — reuses the watchdog's recovery timestamp so this
     *      can't pile extra swaps on top of watchdog recoveries.
     */
    public void wakeIfStalled() {
        if (!capturing || !firstFrameSeen) return;
        long now = System.currentTimeMillis();
        if (now - lastFrameAt <= STALL_THRESHOLD_MS) return; // Still healthy.
        if (now - lastRecoverAt <= RECOVER_MIN_GAP_MS) return; // Recently handled.
        lastRecoverAt = now;
        android.util.Log.i("ScreenCaptureService",
                "wakeIfStalled: pipeline stalled for " + (now - lastFrameAt)
                        + "ms during background — waking on resume");
        wakeUpVirtualDisplay();
    }

    /**
     * Re-checks the current display size and, if it changed, resizes the
     * capture pipeline. This mirrors ScreenStream's fix for Google issue
     * 370625489 ("After onCapturedContentResize + resize/surface swap, no
     * output" — Android 14/15): after the app returns from the background the
     * display metrics can differ, and swapping the VirtualDisplay surface the
     * naive way leaves the capture black. The workaround is:
     *   virtualDisplay.surface = null
     *   virtualDisplay.resize(w, h, dpi)
     *   virtualDisplay.surface = imageReader.surface
     *
     * @return true when the pipeline was resized (or already matched).
     */
    public boolean resizeIfNeeded() {
        if (!capturing || virtualDisplay == null || imageReader == null) return false;
        final int[] size = new int[2];
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            size[0] = metrics.widthPixels;
            size[1] = metrics.heightPixels;
        } catch (Throwable t) {
            return false;
        }
        if (size[0] == width && size[1] == height) return true; // Unchanged.

        final int newW = size[0];
        final int newH = size[1];
        captureHandler.post(() -> {
            try {
                // Recreate ImageReader at the new size (like ScreenStream.resize).
                final ImageReader newReader = ImageReader.newInstance(
                        newW, newH, PixelFormat.RGBA_8888, 2);
                newReader.setOnImageAvailableListener(reader -> {
                    long now = System.currentTimeMillis();
                    if (now - lastFrameAt < FRAME_INTERVAL_MS) {
                        Image drop = reader.acquireLatestImage();
                        if (drop != null) drop.close();
                        return;
                    }
                    lastFrameAt = now;
                    // Sustained-flow reset only (see the main listener's comment):
                    // a token frame right after a recovery attempt must not
                    // reset the escalation ladder.
                    if (now - lastRecoverAt > STALL_THRESHOLD_MS) {
                        recoveryLevel = 0;
                    }
                    Image image = reader.acquireLatestImage();
                    if (image == null) return;
                    try {
                        byte[] jpeg = imageToJpeg(image, newW, newH);
                        if (jpeg != null) {
                            pushFrameToJs(Base64Utils.toJpegDataUrl(jpeg));
                        }
                    } catch (Throwable t) {
                        android.util.Log.e("ScreenCaptureService", "frame error", t);
                    } finally {
                        image.close();
                    }
                }, captureHandler);

                // Issue 370625489 workaround: null surface -> resize -> new surface.
                virtualDisplay.setSurface(null);
                virtualDisplay.resize(newW, newH, densityDpi);
                virtualDisplay.setSurface(newReader.getSurface());

                ImageReader oldReader = imageReader;
                imageReader = newReader;
                width = newW;
                height = newH;
                lastFrameAt = 0L;
                if (oldReader != null) {
                    try { oldReader.close(); } catch (Throwable ignored) {}
                }
                // Also refresh bitmap buffers for the new size.
                if (reusableBitmap != null) {
                    reusableBitmap.recycle();
                    reusableBitmap = null;
                }
                android.util.Log.i("ScreenCaptureService",
                        "Resized capture to " + newW + "x" + newH);
            } catch (Throwable t) {
                android.util.Log.e("ScreenCaptureService", "resize failed", t);
            }
        });
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = new WeakReference<>(this);
        mainHandler = new Handler(Looper.getMainLooper());
        captureThread = new HandlerThread("ScreenCaptureThread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        switch (intent.getAction()) {
            case ACTION_START:
                int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
                // API 33+: typed overload is required for reliable parceling.
                Intent resultData;
                if (Build.VERSION.SDK_INT >= 33) {
                    resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
                } else {
                    resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                }
                startForegroundWithNotification();
                startCapture(resultCode, resultData);
                break;
            case ACTION_STOP:
                stopCapture();
                stopSelf();
                break;
            default:
                stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startForegroundWithNotification() {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startCapture(int resultCode, Intent resultData) {
        // Fresh capture session: allow a future teardown to run.
        stopInProgress.set(false);
        try {
            startCaptureInternal(resultCode, resultData);
        } catch (Throwable t) {
            // NEVER let an exception kill the service silently — that triggers
            // onDestroy -> stopCapture -> "Screen capture ended" in JS. Surface
            // the real error to the page instead.
            android.util.Log.e("ScreenCaptureService", "startCapture failed", t);
            notifyJs("window.__onScreenError && window.__onScreenError('Capture start failed: "
                    + safeLogMessage(t) + "');");
            stopSelf();
        }
    }

    private String safeLogMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m.replace("'", "").replace("\n", " ");
    }

    private void startCaptureInternal(int resultCode, Intent resultData) {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null || resultData == null) {
            notifyJs("window.__onScreenError && window.__onScreenError('MediaProjection unavailable');");
            stopSelf();
            return;
        }

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        densityDpi = metrics.densityDpi;
        width = metrics.widthPixels;
        height = metrics.heightPixels;

        mediaProjection = mpm.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            notifyJs("window.__onScreenError && window.__onScreenError('MediaProjection grant failed');");
            stopSelf();
            return;
        }
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                android.util.Log.w("ScreenCaptureService",
                        "MediaProjection onStop fired (system revoked or stop called)");
                // Re-entrancy-safe: stopCapture() CAS-guards teardown, so this
                // nested call (triggered by our own mediaProjection.stop()) is a
                // no-op when teardown is already running.
                stopCapture();
            }
        };
        mediaProjection.registerCallback(projectionCallback, captureHandler);

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(newFrameListener(), captureHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ChromeCloneScreenCapture",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);

        capturing = true;
        firstFrameSeen = false;
        // Fresh session: reset the recovery ladder and the restart counter so
        // this capture gets the full escalation budget again, and arm the
        // startup-stall detector from this moment.
        recoveryLevel = 0;
        restartCount = 0;
        startupSwapTried = false;
        captureStartedAt = System.currentTimeMillis();
        notifyJs("window.__onScreenStarted && window.__onScreenStarted();");
        // Start the stall watchdog (fires every 2s; recovers after 3s of no frames).
        captureHandler.removeCallbacks(captureWatchdog);
        captureHandler.postDelayed(captureWatchdog, WATCHDOG_INTERVAL_MS);
    }

    /** Converts an RGBA_8888 Image plane into JPEG bytes. Runs on the capture thread. */
    private byte[] imageToJpeg(Image image) {
        return imageToJpeg(image, width, height);
    }

    /** Size-aware variant used by resizeIfNeeded() after the display resizes. */
    private byte[] imageToJpeg(Image image, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * w;

        Bitmap full;
        if (reusableBitmap == null
                || reusableBitmap.getWidth() != w + rowPadding / pixelStride
                || reusableBitmap.getHeight() != h) {
            if (reusableBitmap != null) reusableBitmap.recycle();
            reusableBitmap = Bitmap.createBitmap(
                    w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888);
        }
        buffer.rewind();
        reusableBitmap.copyPixelsFromBuffer(buffer);
        full = Bitmap.createBitmap(reusableBitmap, 0, 0, w, h);

        // Downscale to keep the JS bridge fast.
        int targetW = w;
        int targetH = h;
        int maxDim = Math.max(w, h);
        if (maxDim > MAX_FRAME_DIM) {
            float scale = (float) MAX_FRAME_DIM / (float) maxDim;
            targetW = Math.max(1, Math.round(w * scale));
            targetH = Math.max(1, Math.round(h * scale));
        }
        Bitmap scaled = Bitmap.createScaledBitmap(full, targetW, targetH, true);
        if (scaled != full) full.recycle();

        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        scaled.recycle();
        return out.toByteArray();
    }

    private void pushFrameToJs(String dataUrl) {
        // Escape single quotes defensively (base64 never contains them, but be safe).
        String js = "window.__onScreenFrame && window.__onScreenFrame('" + dataUrl + "');";
        notifyJs(js);
    }

    private void notifyJs(String js) {
        WebView view = webViewRef.get();
        if (view == null) {
            // WebView is gone (rotated/finished before re-attach): drop the frame.
            return;
        }
        mainHandler.post(() -> {
            WebView wv = webViewRef.get();
            if (wv == null) return; // Detached between post and run: drop.
            try {
                wv.evaluateJavascript(js, null);
            } catch (Throwable ignored) {
                // WebView already destroyed or detached from window: drop.
            }
        });
    }

    /** Shows a short Toast on the main thread (diagnostics without logcat). */
    private void showToast(final String message) {
        mainHandler.post(() -> {
            try {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // Toast can throw on some backgrounds; diagnostics only.
            }
        });
    }

    /**
     * Tears down the capture pipeline. Guarded by an AtomicBoolean so re-entrant
     * calls (MediaProjection.Callback.onStop triggered by our own
     * mediaProjection.stop(), plus user stop, plus onDestroy) collapse into a
     * single teardown. The actual resource release runs on the main handler so
     * all teardown happens on one thread in a fixed order.
     */
    public void stopCapture() {
        stopCaptureInternal(true);
    }

    /**
     * Silent teardown for the full-restart path: releases every native
     * resource exactly like {@link #stopCapture()} but does NOT emit
     * __onScreenEnded. That signal makes the JS shim stop the stream tracks —
     * fatal for a restart, which needs the page's canvas/captureStream to
     * stay alive so the fresh session's frames revive the same preview.
     *
     * Ordering note: the teardown body and the follow-up restart prompt are
     * both posted to the same main handler in FIFO order, so by the time the
     * consent prompt appears the old MediaProjection/VirtualDisplay are fully
     * released (Android 14+ allows only ONE active MediaProjection per app).
     */
    private void stopCaptureSilently() {
        stopCaptureInternal(false);
    }

    private void stopCaptureInternal(boolean notifyEnded) {
        capturing = false;
        captureHandler.removeCallbacks(captureWatchdog); // Stop the watchdog.
        if (!stopInProgress.compareAndSet(false, true)) {
            return; // Teardown already in progress: nested call is a no-op.
        }
        // Only notify the JS side that capture ENDED if a capture session was
        // actually live. If stopCapture runs because startCapture failed and
        // stopSelf() was called, mediaProjection is null — the error was
        // already surfaced via __onScreenError, so sending __onScreenEnded
        // here would replace the real error with a misleading AbortError.
        boolean wasLive = mediaProjection != null;
        mainHandler.post(() -> {
            try {
                if (virtualDisplay != null) {
                    try { virtualDisplay.release(); } catch (Throwable ignored) {}
                    virtualDisplay = null;
                }
                if (imageReader != null) {
                    try { imageReader.close(); } catch (Throwable ignored) {}
                    imageReader = null;
                }
                if (mediaProjection != null) {
                    if (projectionCallback != null) {
                        try { mediaProjection.unregisterCallback(projectionCallback); }
                        catch (Throwable ignored) {}
                        projectionCallback = null;
                    }
                    try { mediaProjection.stop(); } catch (Throwable ignored) {}
                    mediaProjection = null;
                }
                if (reusableBitmap != null) {
                    reusableBitmap.recycle();
                    reusableBitmap = null;
                }
                if (wasLive && notifyEnded) {
                    notifyJs("window.__onScreenEnded && window.__onScreenEnded();");
                }
            } finally {
                stopInProgress.set(false);
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        stopCapture();
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        sInstance = new WeakReference<>(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
