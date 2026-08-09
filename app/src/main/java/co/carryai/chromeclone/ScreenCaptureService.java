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
     * Escalation state of the recovery ladder: 0 = surface-swap on next stall,
     * 1 = escalate to a full VirtualDisplay rebuild on the next stall. Reset
     * to 0 whenever a real frame arrives (the pipeline is healthy again).
     *
     * NOTE: rebuildVirtualDisplay() was removed because Android 14+ throws
     * SecurityException ("Don't take multiple captures by invoking
     * MediaProjection#createVirtualDisplay multiple times on the same
     * instance") — releasing and recreating the VirtualDisplay on the same
     * MediaProjection is ILLEGAL and kills the projection entirely. The only
     * valid recovery is surface-swap on the existing VirtualDisplay
     * (ScreenStream's approach for Google issue 370625489), so recoveryLevel
     * is now kept only for diagnostics / future escalation strategies that do
     * NOT recreate the VirtualDisplay.
     */
    private volatile int recoveryLevel = 0;
    /**
     * True while the Activity is in the foreground. The watchdog only fires
     * when visible: in the background the system legitimately pauses the
     * VirtualDisplay mirror (Android 14/15 behaviour), so firing the watchdog
     * there just wastes recovery attempts and eats the rate-limit window.
     */
    private volatile boolean activityVisible = false;
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
            if (firstFrameSeen && activityVisible
                    && now - lastFrameAt > STALL_THRESHOLD_MS
                    && now - lastRecoverAt > RECOVER_MIN_GAP_MS) {
                lastRecoverAt = now;
                // Escalating recovery ladder (both rungs are legal on
                // Android 14+: they never call createVirtualDisplay again,
                // which would throw SecurityException and kill the whole
                // projection). recoveryLevel resets to 0 when a SUSTAINED
                // frame flow returns (see the onImageAvailable listeners).
                //
                // NOTE: the watchdog deliberately does NOT fire while the
                // Activity is backgrounded — the system legitimately pauses
                // the VirtualDisplay mirror there, and burning recovery
                // attempts in the background wastes the rate-limit window.
                // wakeIfStalled() (called from onResume) handles the
                // background->foreground stall immediately instead.
                if (recoveryLevel == 0) {
                    recoveryLevel = 1;
                    android.util.Log.w("ScreenCaptureService",
                            "Watchdog: frames stalled for " + (now - lastFrameAt)
                                    + "ms, attempting surface-swap recovery");
                    showToast("Screen capture stalled — recovering…");
                    recoverCapture();
                } else {
                    recoveryLevel = 1; // Stay at top rung; keep retrying it.
                    android.util.Log.w("ScreenCaptureService",
                            "Watchdog: still stalled after surface-swap ("
                                    + (now - lastFrameAt)
                                    + "ms), refreshing capture pipeline");
                    showToast("Screen capture stalled — refreshing…");
                    refreshCapturePipeline();
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
                if (wasLive) {
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
