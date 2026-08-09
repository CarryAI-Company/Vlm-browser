package co.carryai.chromeclone;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User-configurable capture/upload settings, persisted in SharedPreferences.
 *
 * The Phase-2 capture pipeline (native frame upload to the VLM service) is
 * driven entirely by these values; the legacy JS-bridge frame path remains
 * the default until upload mode is switched on.
 */
public final class CaptureConfig {

    private static final String PREFS = "chromeclone_capture_config";

    private static final String KEY_UPLOAD_ENABLED = "upload_enabled";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_INSTRUCTION = "instruction";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt";
    private static final String KEY_MIN_INTERVAL_MS = "min_interval_ms";
    private static final String KEY_STREAM_ID = "stream_id";
    private static final String KEY_CROP_X = "crop_x";
    private static final String KEY_CROP_Y = "crop_y";
    private static final String KEY_CROP_W = "crop_w";
    private static final String KEY_CROP_H = "crop_h";

    /** Default VLM API server (LAN-reachable; the service fronts /ws/inference). */
    public static final String DEFAULT_SERVER_URL = "ws://192.168.1.3:5050";
    /** Default analysis instruction for live captioning. */
    public static final String DEFAULT_INSTRUCTION =
            "Describe what is happening on this screen in one or two short sentences.";
    /** Minimum time between uploaded frames (VLM inference is the pace-setter). */
    public static final long DEFAULT_MIN_INTERVAL_MS = 1000L;

    /** Crop disabled = full frame. Rect stored as fractions of the frame (0..1). */
    public static final float CROP_FULL = 0f;

    private final SharedPreferences prefs;

    public CaptureConfig(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isUploadEnabled() {
        return prefs.getBoolean(KEY_UPLOAD_ENABLED, false);
    }

    public void setUploadEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_UPLOAD_ENABLED, enabled).apply();
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    public String getInstruction() {
        return prefs.getString(KEY_INSTRUCTION, DEFAULT_INSTRUCTION);
    }

    public void setInstruction(String instruction) {
        prefs.edit().putString(KEY_INSTRUCTION, instruction).apply();
    }

    public String getSystemPrompt() {
        return prefs.getString(KEY_SYSTEM_PROMPT, "");
    }

    public void setSystemPrompt(String prompt) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply();
    }

    public long getMinIntervalMs() {
        return prefs.getLong(KEY_MIN_INTERVAL_MS, DEFAULT_MIN_INTERVAL_MS);
    }

    public void setMinIntervalMs(long ms) {
        prefs.edit().putLong(KEY_MIN_INTERVAL_MS, Math.max(200L, ms)).apply();
    }

    public String getStreamId() {
        return prefs.getString(KEY_STREAM_ID, "chromeclone-android");
    }

    public void setStreamId(String id) {
        prefs.edit().putString(KEY_STREAM_ID, id).apply();
    }

    // ------------------------------------------------------------------
    // Crop region — stored as FRACTIONS of the frame (0..1) so the same
    // selection survives rotation and resolution changes.
    // ------------------------------------------------------------------

    /** True when a partial crop region is active (not the full frame). */
    public boolean hasCrop() {
        float w = getCropW();
        float h = getCropH();
        // Full-frame: origin at (0,0) covering everything.
        return w > 0.01f && h > 0.01f && !(w >= 0.999f && h >= 0.999f
                && getCropX() < 0.001f && getCropY() < 0.001f);
    }

    public float getCropX() {
        return prefs.getFloat(KEY_CROP_X, 0f);
    }

    public float getCropY() {
        return prefs.getFloat(KEY_CROP_Y, 0f);
    }

    public float getCropW() {
        return prefs.getFloat(KEY_CROP_W, 1f);
    }

    public float getCropH() {
        return prefs.getFloat(KEY_CROP_H, 1f);
    }

    /** Stores a crop rect; values are clamped to fractions within [0,1]. */
    public void setCrop(float x, float y, float w, float h) {
        x = clamp01(x);
        y = clamp01(y);
        w = clamp01(w);
        h = clamp01(h);
        // Keep the rect inside the frame.
        if (x + w > 1f) w = 1f - x;
        if (y + h > 1f) h = 1f - y;
        prefs.edit()
                .putFloat(KEY_CROP_X, x)
                .putFloat(KEY_CROP_Y, y)
                .putFloat(KEY_CROP_W, w)
                .putFloat(KEY_CROP_H, h)
                .apply();
    }

    /** Clears the crop region (upload the full frame again). */
    public void clearCrop() {
        prefs.edit()
                .putFloat(KEY_CROP_X, 0f)
                .putFloat(KEY_CROP_Y, 0f)
                .putFloat(KEY_CROP_W, 1f)
                .putFloat(KEY_CROP_H, 1f)
                .apply();
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ------------------------------------------------------------------
    // Server URL helpers (pure, unit-testable).
    // ------------------------------------------------------------------

    /**
     * Normalizes a user-supplied server base URL into the /ws/inference
     * WebSocket URL. Accepts "ws://host:port", "wss://host:port",
     * "http://host:port" or a bare "host:port"; strips trailing slashes and
     * any existing path, then appends the fixed inference endpoint.
     * Returns null for empty/blank input.
     */
    public static String toWsInferenceUrl(String serverUrl) {
        if (serverUrl == null) return null;
        String s = serverUrl.trim();
        if (s.isEmpty()) return null;
        // Strip scheme.
        String rest;
        String scheme;
        if (s.startsWith("wss://")) {
            scheme = "wss";
            rest = s.substring(6);
        } else if (s.startsWith("ws://")) {
            scheme = "ws";
            rest = s.substring(5);
        } else if (s.startsWith("https://")) {
            scheme = "wss"; // TLS HTTP -> TLS WS.
            rest = s.substring(8);
        } else if (s.startsWith("http://")) {
            scheme = "ws";
            rest = s.substring(7);
        } else {
            scheme = "ws";
            rest = s; // Bare host:port.
        }
        // Drop any path component and trailing slashes.
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        rest = rest.trim();
        if (rest.isEmpty()) return null;
        return scheme + "://" + rest + "/ws/inference";
    }
}
