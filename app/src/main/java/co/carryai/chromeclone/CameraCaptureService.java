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
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import java.io.ByteArrayOutputStream;

/**
 * Native CAMERA capture source (Phase 2): captures camera frames with CameraX
 * and hands JPEGs straight to the shared {@link FrameUploader} for the VLM
 * service. Simpler and MORE robust than screen capture — no MediaProjection,
 * no consent re-prompts, no VirtualDisplay death modes.
 *
 * Pipeline: CameraX ImageAnalysis (YUV) -> Bitmap -> crop -> JPEG -> uploader.
 *
 * The WebView is not involved at all; captions still come back through
 * MainActivity's __onCaption relay (via the uploader listener).
 */
public class CameraCaptureService extends Service implements LifecycleOwner {

    public static final String ACTION_START = "co.carryai.chromeclone.action.START_CAMERA";
    public static final String ACTION_STOP = "co.carryai.chromeclone.action.STOP_CAMERA";
    public static final String ACTION_SWITCH = "co.carryai.chromeclone.action.SWITCH_CAMERA";
    public static final String EXTRA_LENS = "extra_lens"; // CameraSelector.LENS_FACING_*

    private static final String CHANNEL_ID = "camera_capture_channel";
    private static final int NOTIFICATION_ID = 0xCA7;
    /** Max JPEG edge for uploads (keeps bandwidth + inference sane). */
    private static final int MAX_DIM = 960;
    private static final int JPEG_QUALITY = 62;

    private static volatile CameraCaptureService sInstance;

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageAnalysis analysis;
    private HandlerThread workerThread;
    private Handler worker;
    private Handler mainHandler;
    private FrameUploader uploader;

    private volatile boolean capturing = false;
    private volatile int lensFacing = CameraSelector.LENS_FACING_BACK;
    /** Crop fractions [x,y,w,h], shared with the screen pipeline semantics. */
    private volatile float[] cropFraction = null;
    private volatile boolean uploadMode = false;
    /** Throttle: minimum ms between analysed frames. */
    private volatile long minFrameGapMs = 250;
    private volatile long lastFrameAt = 0L;

    public static CameraCaptureService getInstance() {
        return sInstance;
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
        mainHandler = new Handler(Looper.getMainLooper());
        workerThread = new HandlerThread("CameraCaptureWorker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
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
                lensFacing = intent.getIntExtra(EXTRA_LENS, CameraSelector.LENS_FACING_BACK);
                startForegroundWithNotification();
                lifecycle.setCurrentState(Lifecycle.State.STARTED);
                startCapture();
                break;
            case ACTION_SWITCH:
                lensFacing = intent.getIntExtra(EXTRA_LENS,
                        lensFacing == CameraSelector.LENS_FACING_BACK
                                ? CameraSelector.LENS_FACING_FRONT
                                : CameraSelector.LENS_FACING_BACK);
                if (capturing) {
                    bindCamera(); // re-bind with the new lens
                }
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

    @Override
    public void onDestroy() {
        stopCapture();
        if (uploader != null) {
            uploader.destroy();
            uploader = null;
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        sInstance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Capture pipeline
    // ------------------------------------------------------------------

    private void startCapture() {
        capturing = true;
        final Runnable listener = () -> {
            try {
                cameraProvider = ProcessCameraProvider.getInstance(this).get();
                bindCamera();
            } catch (Throwable t) {
                android.util.Log.e("CameraCaptureService", "camera provider failed", t);
                stopSelf();
            }
        };
        try {
            ProcessCameraProvider.getInstance(this).addListener(listener, worker::post);
        } catch (Throwable t) {
            android.util.Log.e("CameraCaptureService", "addListener failed", t);
            stopSelf();
        }
    }

    /** (Re)binds the camera use-case with the current lens facing. */
    private void bindCamera() {
        if (cameraProvider == null) return;
        try {
            cameraProvider.unbindAll();
        } catch (Throwable ignored) {}

        ImageAnalysis.Builder b = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888);
        analysis = b.build();
        analysis.setAnalyzer(worker::post, this::onFrame);

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing).build();
        try {
            camera = cameraProvider.bindToLifecycle(this, selector, analysis);
            android.util.Log.i("CameraCaptureService",
                    "camera bound, lens=" + (lensFacing == CameraSelector.LENS_FACING_BACK
                            ? "back" : "front"));
        } catch (Throwable t) {
            android.util.Log.e("CameraCaptureService", "bindCamera failed", t);
            stopSelf();
        }
    }

    /** CameraX analyzer callback — converts YUV/RGBA -> JPEG and uploads. */
    private void onFrame(ImageProxy proxy) {
        try {
            if (!capturing) return;
            long now = System.currentTimeMillis();
            if (now - lastFrameAt < minFrameGapMs) return; // throttle
            lastFrameAt = now;

            Bitmap bmp = toBitmap(proxy);
            if (bmp == null) return;
            byte[] jpeg = encodeJpeg(bmp);
            if (jpeg != null && uploadMode && uploader != null) {
                uploader.submitFrame(jpeg);
            }
        } catch (Throwable t) {
            android.util.Log.e("CameraCaptureService", "frame error", t);
        } finally {
            proxy.close();
        }
    }

    /**
     * Converts an RGBA_8888 ImageProxy (the configured output format) into a
     * Bitmap using only the public planes API — deliberately avoids the
     * experimental ImageProxy.getImage().
     */
    private Bitmap toBitmap(ImageProxy proxy) {
        int w = proxy.getWidth();
        int h = proxy.getHeight();
        ImageProxy.PlaneProxy[] planes = proxy.getPlanes();
        if (planes.length == 0) return null;
        java.nio.ByteBuffer buf = planes[0].getBuffer();
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (pixelStride == 4 && rowStride == w * 4) {
            // Tight RGBA — fast path.
            buf.rewind();
            bmp.copyPixelsFromBuffer(buf);
        } else {
            // Padded rows — copy row by row into a tight buffer.
            byte[] tight = new byte[w * h * 4];
            byte[] row = new byte[rowStride];
            buf.rewind();
            for (int y = 0; y < h; y++) {
                buf.get(row, 0, rowStride);
                if (pixelStride == 4) {
                    System.arraycopy(row, 0, tight, y * w * 4, w * 4);
                } else {
                    for (int x = 0; x < w; x++) {
                        int src = x * pixelStride;
                        int dst = (y * w + x) * 4;
                        tight[dst] = row[src];
                        tight[dst + 1] = row[src + 1];
                        tight[dst + 2] = row[src + 2];
                        tight[dst + 3] = row[src + 3];
                    }
                }
            }
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(tight));
        }
        return bmp;
    }

    /** Crop (if set) + downscale + JPEG encode. */
    private byte[] encodeJpeg(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap full = src;
        float[] crop = cropFraction;
        if (crop != null) {
            int cx = clampPx(Math.round(crop[0] * w), w);
            int cy = clampPx(Math.round(crop[1] * h), h);
            int cw = clampPx(Math.round(crop[2] * w), w);
            int ch = clampPx(Math.round(crop[3] * h), h);
            if (cx + cw > w) cw = w - cx;
            if (cy + ch > h) ch = h - cy;
            if (cw >= 8 && ch >= 8) {
                Bitmap region = Bitmap.createBitmap(src, cx, cy, cw, ch);
                Bitmap owned = region.copy(Bitmap.Config.ARGB_8888, false);
                full = (owned != null) ? owned : region;
                w = cw;
                h = ch;
            }
        }
        int maxDim = Math.max(w, h);
        Bitmap scaled = full;
        if (maxDim > MAX_DIM) {
            float scale = (float) MAX_DIM / maxDim;
            scaled = Bitmap.createScaledBitmap(full,
                    Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        if (scaled != full) scaled.recycle();
        if (full != src) full.recycle();
        return out.toByteArray();
    }

    private static int clampPx(int v, int max) {
        return Math.max(0, Math.min(max - 1, v));
    }

    // ------------------------------------------------------------------
    // Upload wiring (same pattern as ScreenCaptureService)
    // ------------------------------------------------------------------

    public void setUploadMode(boolean enabled, CaptureConfig cfg) {
        uploadMode = enabled;
        cropFraction = (cfg != null && cfg.hasCrop())
                ? new float[]{cfg.getCropX(), cfg.getCropY(), cfg.getCropW(), cfg.getCropH()}
                : null;
        minFrameGapMs = Math.max(200, cfg != null ? cfg.getMinIntervalMs() : 1000);
        if (enabled) {
            if (uploader == null) {
                uploader = new FrameUploader(new FrameUploader.Listener() {
                    @Override
                    public void onCaption(String text, String sid) {
                        // Camera path has no WebView of its own — the
                        // Activity relays via its own bridge if it has one.
                        // Notify MainActivity through the shared callback.
                        CaptionSink sink = captionSink;
                        if (sink != null) sink.onCaption(text, sid);
                    }
                    @Override
                    public void onStateChanged(FrameUploader.State s) {
                        android.util.Log.i("CameraCaptureService", "uploader state: " + s);
                    }
                });
            }
            String ws = CaptureConfig.toWsInferenceUrl(cfg.getServerUrl());
            uploader.start(ws, cfg.getInstruction(), cfg.getSystemPrompt(),
                    cfg.getStreamId(), cfg.getMinIntervalMs());
        } else if (uploader != null) {
            uploader.stop();
        }
    }

    /** Where camera captions go (MainActivity registers its bridge relay). */
    public interface CaptionSink {
        void onCaption(String text, String streamId);
    }
    private volatile CaptionSink captionSink;

    public void setCaptionSink(CaptionSink sink) {
        captionSink = sink;
    }

    public boolean isCapturing() {
        return capturing;
    }

    public void stopCapture() {
        capturing = false;
        try {
            if (cameraProvider != null) cameraProvider.unbindAll();
        } catch (Throwable ignored) {}
        if (uploader != null) uploader.stop();
        lifecycle.setCurrentState(Lifecycle.State.CREATED);
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private void startForegroundWithNotification() {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Camera capture for AI analysis")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Camera capture", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Camera capture for AI analysis");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
