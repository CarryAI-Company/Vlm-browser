package co.carryai.chromeclone;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Uploads captured JPEG frames to the CarryAI VLM service over the
 * /ws/inference WebSocket and receives caption results on the SAME socket.
 *
 * Wire protocol (matches visual_llm_complete server/core/fastapi_app.py):
 *   request : [4-byte big-endian JSON header length][JSON header][JPEG bytes]
 *   response: JSON text, e.g. {"status":"success","result":"<caption>",...}
 *
 * Pacing model — the VLM inference time is the natural rate limiter:
 *   - a new frame is sent only after the previous one got a response
 *     (or errored out) AND the configured minimum interval has passed;
 *   - frames submitted while busy are held in a single "latest wins" slot,
 *     so we never build a queue of stale screenshots.
 *
 * Everything network-related runs on one dedicated worker thread; callers
 * (the capture thread) only ever touch volatile fields, so submitFrame is
 * cheap and never blocks capture.
 */
public class FrameUploader {

    /** Connection state, surfaced to the UI/notification. */
    public enum State { DISABLED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    /** Callbacks delivered on the main thread. */
    public interface Listener {
        /** A caption (or intermediate result text) came back from the VLM. */
        void onCaption(String text, String streamId);
        /** Connection state changed. */
        void onStateChanged(State state);
    }

    private final OkHttpClient client;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FrameUploader");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    // Settings, read fresh at connect time (user may edit them live).
    private volatile String wsUrl;
    private volatile String instruction;
    private volatile String systemPrompt;
    private volatile String streamId;
    private volatile long minIntervalMs;

    private volatile WebSocket socket;
    private volatile State state = State.DISABLED;

    // Latest-wins slot: the most recent frame waiting for a send window.
    private final Object slotLock = new Object();
    private byte[] pendingJpeg;

    // Pump state (worker thread only).
    private boolean inflight = false;      // waiting for a response
    private long lastSendAt = 0L;
    private long backoffMs = 1000L;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean shutdown = false;

    public FrameUploader(Listener listener) {
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                // Server-side inference can take a while; never ping-timeout
                // a connection that is simply thinking.
                .pingInterval(java.time.Duration.ofSeconds(20))
                .build();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Starts (or re-points) the uploader with the current settings. */
    public void start(String wsUrl, String instruction, String systemPrompt,
                      String streamId, long minIntervalMs) {
        this.wsUrl = wsUrl;
        this.instruction = instruction;
        this.systemPrompt = systemPrompt;
        this.streamId = streamId;
        this.minIntervalMs = Math.max(200L, minIntervalMs);
        shutdown = false;
        if (started.compareAndSet(false, true)) {
            setState(State.CONNECTING);
            worker.execute(this::connect);
        }
    }

    /** Stops the uploader and closes the socket. Pending frames are dropped. */
    public void stop() {
        shutdown = true;
        started.set(false);
        synchronized (slotLock) {
            pendingJpeg = null;
        }
        WebSocket s = socket;
        if (s != null) {
            try { s.close(1000, "capture stopped"); } catch (Throwable ignored) {}
            socket = null;
        }
        setState(State.DISABLED);
    }

    /** Releases the OkHttp client and worker. Call from service onDestroy. */
    public void destroy() {
        stop();
        worker.execute(() -> {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        });
        worker.shutdown();
    }

    // ------------------------------------------------------------------
    // Frame submission (called from the capture thread)
    // ------------------------------------------------------------------

    /**
     * Hands a JPEG to the uploader. Never blocks; if a frame is already
     * waiting it is REPLACED (latest wins — an old screenshot is worthless).
     */
    public void submitFrame(byte[] jpeg) {
        if (shutdown || jpeg == null || jpeg.length == 0) return;
        synchronized (slotLock) {
            pendingJpeg = jpeg;
        }
        worker.execute(this::pump);
    }

    // ------------------------------------------------------------------
    // Worker-thread logic
    // ------------------------------------------------------------------

    private void connect() {
        if (shutdown) return;
        String url = wsUrl;
        if (url == null || url.isEmpty()) {
            setState(State.FAILED);
            return;
        }
        setState(State.CONNECTING);
        Request request = new Request.Builder().url(url).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                backoffMs = 1000L;
                setState(State.CONNECTED);
                worker.execute(FrameUploader.this::pump);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleResponse(text);
                // Response arrived -> the send window opens again.
                inflight = false;
                worker.execute(FrameUploader.this::pump);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                try { ws.close(1000, null); } catch (Throwable ignored) {}
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                if (socket == ws) socket = null;
                inflight = false;
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (socket == ws) socket = null;
                inflight = false;
                scheduleReconnect();
            }
        });
    }

    /** Reconnect with capped exponential backoff. */
    private void scheduleReconnect() {
        if (shutdown || !started.get()) return;
        setState(State.RECONNECTING);
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, 15000L);
        worker.execute(() -> {
            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
            connect();
        });
    }

    /**
     * Send the pending frame when the window is open. Runs on the worker
     * thread; self-reschedules while a frame waits and the socket is busy.
     */
    private void pump() {
        if (shutdown || !started.get()) return;
        WebSocket s = socket;
        if (s == null) return; // (Re)connect will re-pump on open.

        byte[] jpeg;
        synchronized (slotLock) {
            jpeg = pendingJpeg;
        }
        if (jpeg == null) return;

        long now = System.currentTimeMillis();
        if (inflight || now - lastSendAt < minIntervalMs) {
            // Not yet — check again soon (a response or the interval may free us).
            worker.execute(() -> {
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                pump();
            });
            return;
        }

        byte[] packet;
        try {
            packet = encodeFrame(jpeg, instruction, systemPrompt, streamId);
        } catch (JSONException e) {
            return; // Config corrupt — drop the frame rather than crash.
        }

        inflight = true;
        lastSendAt = now;
        synchronized (slotLock) {
            pendingJpeg = null; // Consumed (latest-wins slot).
        }
        boolean sent = s.send(ByteString.of(packet));
        if (!sent) {
            inflight = false;
            synchronized (slotLock) {
                if (pendingJpeg == null) pendingJpeg = jpeg; // Put it back.
            }
            scheduleReconnect();
        }
    }

    /** Parses the server's JSON response and forwards the caption. */
    private void handleResponse(String text) {
        try {
            JSONObject json = new JSONObject(text);
            String status = json.optString("status", "");
            if ("success".equals(status)) {
                String result = json.optString("result", "");
                if (!result.isEmpty()) {
                    String sid = json.optString("stream_id", streamId);
                    main.post(() -> listener.onCaption(result, sid));
                }
            } else if ("error".equals(status)) {
                android.util.Log.w("FrameUploader",
                        "server error: " + json.optString("error", "?"));
            }
        } catch (JSONException e) {
            android.util.Log.w("FrameUploader", "unparseable response: " + text);
        }
    }

    private void setState(State next) {
        if (state == next) return;
        state = next;
        main.post(() -> listener.onStateChanged(next));
    }

    public State getState() {
        return state;
    }

    // ------------------------------------------------------------------
    // Protocol encoding (static — unit-testable).
    // ------------------------------------------------------------------

    /**
     * Builds the wire packet:
     *   [4-byte BE header length][UTF-8 JSON header][jpeg]
     * Header: {"instruction","system_prompt","stream_id","num_images":1}.
     */
    static byte[] encodeFrame(byte[] jpeg, String instruction,
                              String systemPrompt, String streamId) throws JSONException {
        JSONObject header = new JSONObject();
        header.put("instruction", instruction == null ? "" : instruction);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            header.put("system_prompt", systemPrompt);
        }
        header.put("stream_id", streamId == null ? "chromeclone-android" : streamId);
        header.put("num_images", 1);
        byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);

        byte[] packet = new byte[4 + headerBytes.length + jpeg.length];
        // Big-endian 4-byte length.
        int n = headerBytes.length;
        packet[0] = (byte) ((n >>> 24) & 0xFF);
        packet[1] = (byte) ((n >>> 16) & 0xFF);
        packet[2] = (byte) ((n >>> 8) & 0xFF);
        packet[3] = (byte) (n & 0xFF);
        System.arraycopy(headerBytes, 0, packet, 4, headerBytes.length);
        System.arraycopy(jpeg, 0, packet, 4 + headerBytes.length, jpeg.length);
        return packet;
    }
}
