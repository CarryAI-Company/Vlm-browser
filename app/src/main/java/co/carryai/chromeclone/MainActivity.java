package co.carryai.chromeclone;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ChromeClone main browser activity.
 *
 * - Hosts a full-screen WebView with a URL bar and bottom nav bar.
 * - Grants WebView PermissionRequests (camera/mic) once runtime permissions are held.
 * - Bridges getDisplayMedia() to MediaProjection through the {@link NativeBridge}
 *   JavascriptInterface + {@link ScreenCaptureService}.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_RUNTIME_PERMISSIONS = 1001;
    private static final int REQ_MEDIA_PROJECTION = 1002;
    /** MediaProjection consent re-requested by the capture-restart ladder. */
    private static final int REQ_MEDIA_PROJECTION_RESTART = 1003;

    private static final String HOME_URL = "https://2026-pcf-demo.carryai.co/live-caption";
    private static final String TEST_URL = "file:///android_asset/test.html";

    // Bookmarks persistence
    private static final String PREFS_NAME = "chromeclone_prefs";
    private static final String KEY_BOOKMARKS = "bookmarks";
    /** Seeded on first launch. */
    private static final String[] DEFAULT_BOOKMARK_URLS = {
            "https://2026-pcf-demo.carryai.co/live-caption"
    };
    private static final String[] DEFAULT_BOOKMARK_TITLES = {
            "PCF Demo · Live Caption"
    };

    /**
     * Hosts allowed to use the ChromeCloneNative bridge and receive bridge.js
     * injection. Subdomains of carryai.co are allowed too (the PCF live-caption
     * demo). The bundled android_asset test page is allowed via the file:///
     * android_asset/ prefix check in isBridgeOriginAllowed. Everything else is
     * denied: the bridge can trigger a system MediaProjection prompt, so it
     * must never be reachable from arbitrary websites.
     */
    private static final Set<String> ALLOWED_BRIDGE_HOSTS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "localhost",
                    "127.0.0.1",
                    "carryai.co" // + subdomains, see isAllowedBridgeHost
            )));

    /** True when host is allowlisted or a subdomain of an allowlisted domain. */
    private static boolean isAllowedBridgeHost(String host) {
        if (host == null) return false;
        if (ALLOWED_BRIDGE_HOSTS.contains(host)) return true;
        // Allow any subdomain of an allowlisted registrable domain (carryai.co).
        for (String allowed : ALLOWED_BRIDGE_HOSTS) {
            if (!allowed.isEmpty() && allowed.indexOf('.') > 0
                    && host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private WebView webView;
    private EditText urlInput;
    private ProgressBar progressBar;
    private ImageButton btnBack, btnForward, btnReload, btnGo, btnTest, btnBookmark, btnSwitchCamera;

    /**
     * URL of the page currently displayed, cached on the UI thread.
     * The JS bridge (@JavascriptInterface) runs on a WebView background thread,
     * where calling webView.getUrl() throws CalledFromWrongThreadException —
     * so origin checks read this cached value instead.
     */
    private volatile String currentPageUrl = "";

    /** True while the page holds an active camera stream (reported by bridge.js). */
    private volatile boolean cameraActive = false;
    /**
     * True while a capture-restart consent prompt is showing. Lets
     * onActivityResult tell a restart consent apart from a user-initiated
     * Share Screen, so the right JS signal is sent if the user declines.
     */
    private volatile boolean restartInFlight = false;

    private String bridgeJs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bridgeJs = loadAssetText("bridge.js");

        webView = findViewById(R.id.webView);
        urlInput = findViewById(R.id.urlInput);
        progressBar = findViewById(R.id.progressBar);
        btnBack = findViewById(R.id.btnBack);
        btnForward = findViewById(R.id.btnForward);
        btnReload = findViewById(R.id.btnReload);
        btnGo = findViewById(R.id.btnGo);
        btnTest = findViewById(R.id.btnTest);
        btnBookmark = findViewById(R.id.btnBookmark);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);

        seedDefaultBookmarks();
        requestBatteryOptimizationExemptionIfNeeded();
        // Keep-alive is dynamic: only while camera/screen-capture is active.
        updateKeepAlive();

        setupWebView();
        setupUi();

        ensureRuntimePermissions();

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    /**
     * Returns true when the given URL belongs to an origin allowed to use the
     * ChromeCloneNative bridge: our bundled android_asset pages (file scheme)
     * or a host in ALLOWED_BRIDGE_HOSTS.
     */
    static boolean isBridgeOriginAllowed(String url) {
        if (url == null || url.isEmpty()) return false;
        if (url.startsWith("file:///android_asset/")) return true;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            scheme = scheme.toLowerCase(Locale.US);
            // file:// is allowed ONLY for our bundled android_asset pages (the
            // prefix check above covers the only valid path). Any other file://
            // URL — e.g. file:///etc/passwd — must be denied.
            if (scheme.equals("file")) return false;
            // Only http(s) origins are considered beyond this point.
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) return false;
            return isAllowedBridgeHost(host.toLowerCase(Locale.US));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true for schemes the WebView may navigate to. Unknown schemes
     * (intent://, javascript:, tel:, market:, ...) are blocked by the caller.
     */
    static boolean isNavigableScheme(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("file://") || lower.startsWith("about:")
                || lower.startsWith("data:");
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        s.setUserAgentString(s.getUserAgentString() + " ChromeClone/1.0");

        // The interface object is always registered (removing it per-page is racy),
        // but every method re-checks the current page origin and no-ops when the
        // page is not on the bridge allowlist — so it is harmless on other sites.
        webView.addJavascriptInterface(new NativeBridge(new WeakReference<>(webView)),
                "ChromeCloneNative");

        // Pre-parse bridge injection (document-start). Unlike evaluateJavascript
        // in onPageStarted, this runs BEFORE the page's own scripts, so sites that
        // feature-detect navigator.mediaDevices.getDisplayMedia at parse time (like
        // the PCF demo) see the bridged implementation immediately. Only allowed
        // origins are injected. Fallback evaluateJavascript injection stays in
        // place (injectBridge) for WebViews without document-start support.
        installDocumentStartBridge();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isNavigableScheme(url)) {
                    return false; // Let the WebView handle http/https/file/about/data.
                }
                // Block everything else (intent://, javascript:, tel:, market:, ...).
                // Never navigate to javascript: URLs from here.
                android.util.Log.w("MainActivity", "Blocked navigation to disallowed scheme: " + url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                currentPageUrl = url; // Cache on UI thread for the JS bridge.
                // Inject the bridge shim before page scripts run wherever possible,
                // but only for origins allowed to use the native bridge.
                injectBridge(view, url);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                currentPageUrl = url;
                // Re-inject as a safety net: onPageStarted can race with parsing.
                // bridge.js is idempotent (window.__chromeCloneShimInstalled guard)
                // and re-applies the getDisplayMedia patch via __chromeClonePatch().
                injectBridge(view, url);
                progressBar.setVisibility(View.GONE);
                urlInput.setText(url);
                updateNavButtons();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The renderer was killed while the app was in the background
                // (typical after the 2nd app switch on low-RAM devices): the
                // whole page state — including any camera/screen-share stream —
                // is gone, so a reload is the only way to recover a non-black UI.
                android.util.Log.w("MainActivity",
                        "Render process gone (didCrash=" + detail.didCrash() + "), reloading");
                progressBar.setVisibility(View.VISIBLE);
                // Re-create a live WebView by loading the last page.
                String url = currentPageUrl;
                if (url == null || url.isEmpty()) url = HOME_URL;
                view.loadUrl(url);
                return true; // We handled it; keep the WebView instance.
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                // Route JS console output to logcat so bridge.js diagnostics are
                // visible via `adb logcat -s ChromeClone`.
                String msg = cm.message() + " [" + cm.lineNumber() + ":" + cm.sourceId() + "]";
                switch (cm.messageLevel()) {
                    case ERROR:
                        android.util.Log.e("ChromeClone", msg);
                        break;
                    case WARNING:
                        android.util.Log.w("ChromeClone", msg);
                        break;
                    default:
                        android.util.Log.i("ChromeClone", msg);
                        break;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // Grant WebView resource requests (camera/mic) once runtime
                // permissions are held. getDisplayMedia is handled by the bridge.
                runOnUiThread(() -> {
                    if (hasMediaRuntimePermissions()) {
                        request.grant(request.getResources());
                    } else {
                        // Ask for runtime permissions first; grant what we can.
                        ensureRuntimePermissions();
                        // Grant anyway: WebView will report device errors if the
                        // user ultimately denies, which pages handle gracefully.
                        request.grant(request.getResources());
                    }
                });
            }
        });

        // Attach WebView to an already-running capture service (e.g. after rotation).
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc != null) {
            svc.attachWebView(webView);
        }
        // The capture service escalates to a full restart (fresh MediaProjection
        // consent) when the VirtualDisplay mirror dies and nothing else can
        // revive it. Register how the Activity fulfils that request. The
        // service is long-lived but this handler is re-registered in onResume
        // too, so it always points at the current Activity/WebView.
        if (svc != null) {
            svc.setRestartHandler(restartHandler);
        }
        if (svc != null) {
            svc.setDeadBridgeHandler(deadBridgeHandler);
        }
    }

    /**
     * Invoked (via the capture service's watchdog) when a stalled pipeline
     * cannot be revived by surface-swap or a pipeline refresh and needs a
     * brand-new MediaProjection session. Runs on the main thread.
     */
    private final ScreenCaptureService.RestartHandler restartHandler = () -> {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            android.util.Log.w("ChromeClone", "capture restart requested — re-showing consent");
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                webView.evaluateJavascript(
                        "window.__onScreenError && window.__onScreenError('Restart failed: MediaProjectionManager unavailable');",
                        null);
                return;
            }
            restartInFlight = true;
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION_RESTART);
        });
    };

    /**
     * Invoked when the heartbeat shows native is pushing frames but the JS
     * side receives none — the WebView renderer was killed or frozen in the
     * background while the app process survived. evaluateJavascript is
     * silently failing, so the canvas stays black even though capture looks
     * healthy. The only fix is to reload the page, which restarts the
     * renderer. The capture service keeps running across the reload, so once
     * the page re-injects bridge.js the frames resume automatically. Runs on
     * the main thread.
     */
    private final ScreenCaptureService.DeadBridgeHandler deadBridgeHandler = () -> {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (webView == null) return;
            android.util.Log.w("ChromeClone",
                    "dead bridge — reloading page to revive the renderer");
            progressBar.setVisibility(View.VISIBLE);
            String url = currentPageUrl;
            if (url == null || url.isEmpty()) url = webView.getUrl();
            if (url == null || url.isEmpty()) url = HOME_URL;
            webView.loadUrl(url);
            // After reload the service's frames flow into the fresh page; the
            // JS shim re-acks and the detector re-arms naturally.
        });
    };

    private void injectBridge(WebView view, String url) {
        if (bridgeJs == null || bridgeJs.isEmpty()) return;
        if (!isBridgeOriginAllowed(url != null ? url : view.getUrl())) {
            return; // Do not expose the bridge shim to disallowed origins.
        }
        view.evaluateJavascript(bridgeJs, null);
    }

    /** True for IPv4/IPv6 literal hosts (e.g. 127.0.0.1) — they get no wildcard rules. */
    private static boolean isIpLiteral(String host) {
        // IPv6 literals contain colons; IPv4 literals are all digits and dots.
        if (host.indexOf(':') >= 0) return true;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) return false;
        }
        return true;
    }

    /**
     * Builds the allowedOriginRules set for WebViewCompat.addDocumentStartJavaScript.
     *
     * CRITICAL: the rules must be bare ORIGINS (scheme://host, e.g.
     * "https://localhost" or "https://*.carryai.co") — the API throws
     * IllegalArgumentException for any rule containing a path ("https://localhost/*"),
     * and because the whole call fails atomically, one bad rule silently disables
     * document-start injection entirely (the page then only gets the slower
     * onPageStarted/onPageFinished fallback, which runs AFTER page scripts and
     * breaks parse-time feature detection like the PCF demo's).
     *
     * Extracted as a static method so unit tests can guard the rule format.
     */
    static Set<String> buildDocumentStartRules() {
        Set<String> rules = new HashSet<>();
        // NOTE: the bundled file:///android_asset/ test page is deliberately NOT
        // covered here — the API only accepts bare origins (scheme://host) for
        // http(s) schemes, and file: URLs have no host, so any file: rule is
        // invalid and would break the whole registration. file: pages rely on
        // the evaluateJavascript fallback in injectBridge() (verified working).
        for (String host : ALLOWED_BRIDGE_HOSTS) {
            if (host.isEmpty()) continue;
            // Bare origins ONLY — never append "/*" or any path component.
            rules.add("https://" + host);
            rules.add("http://" + host);
            if (host.indexOf('.') > 0 && !isIpLiteral(host)) {
                // Subdomain origins for real domains only, e.g.
                // https://*.carryai.co for 2026-pcf-demo.carryai.co
                // (still no path component). IP literals like 127.0.0.1 get
                // no wildcard rules — "https://*.127.0.0.1" is meaningless.
                rules.add("https://*." + host);
                rules.add("http://*." + host);
            }
        }
        return rules;
    }

    /**
     * Registers the bridge shim as a document-start script so it executes before
     * any page script. Origin rules restrict it to the allowed hosts (plus the
     * bundled android_asset test page); on WebViews without document-start
     * support this is skipped and the evaluateJavascript injection in
     * injectBridge() handles it instead.
     */
    private void installDocumentStartBridge() {
        if (bridgeJs == null || bridgeJs.isEmpty()) return;
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                return;
            }
            WebViewCompat.addDocumentStartJavaScript(webView, bridgeJs,
                    buildDocumentStartRules());
        } catch (Throwable t) {
            // If document-start injection is unavailable, fall back to the
            // evaluateJavascript path in injectBridge() — no fatal error here.
            android.util.Log.w("MainActivity", "addDocumentStartJavaScript unavailable: " + t);
        }
    }

    private void setupUi() {
        btnGo.setOnClickListener(v -> navigateTo(urlInput.getText().toString()));
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigateTo(urlInput.getText().toString());
                return true;
            }
            return false;
        });
        btnBack.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        btnForward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        btnReload.setOnClickListener(v -> webView.reload());
        btnTest.setOnClickListener(v -> webView.loadUrl(TEST_URL));
        btnBookmark.setOnClickListener(v -> showBookmarksDialog());
        btnBookmark.setOnLongClickListener(v -> {
            toggleBookmarkCurrentPage();
            return true;
        });
        btnSwitchCamera.setOnClickListener(v -> switchCameraFromUi());
        updateNavButtons();
    }

    /**
     * Switch Camera button: asks the injected bridge (bridge.js) to cycle the
     * active camera stream between front/back via facingMode, replacing the
     * track on live WebRTC senders and local <video> previews. Works on any
     * page where the bridge is injected (test page + allowlisted origins).
     *
     * Implementation note: evaluateJavascript's callback receives the value of
     * the evaluated expression synchronously — a Promise never resolves inside
     * it, so the old code always fell through to "No active camera to switch"
     * even though the switch itself ran. We now kick off the async switch and
     * stash its result in window.__chromeCloneSwitchResult, then poll for it.
     */
    private void switchCameraFromUi() {
        if (webView == null) return;
        if (!isBridgeOriginAllowed(currentPageUrl)) {
            Toast.makeText(this, "Camera switching not available on this page",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // Kick: verify the bridge exists (synchronous) and start the switch.
        // The Promise result lands in window.__chromeCloneSwitchResult later.
        String kick = "window.__chromeCloneSwitchCamera"
                + " ? (window.__chromeCloneSwitchCamera().then(function(r){"
                + "     window.__chromeCloneSwitchResult = 'SWITCHED:' + (r ? r.facingMode : '?');"
                + "   }, function(e){"
                + "     window.__chromeCloneSwitchResult = 'ERROR:' + (e && e.message ? e.message : e);"
                + "   }), 'KICKED')"
                + " : 'NOBRIDGE'";
        webView.evaluateJavascript(kick, value -> {
            String v = (value == null) ? "" : value.replace("\"", "");
            if ("NOBRIDGE".equals(v)) {
                Toast.makeText(this, "No active camera to switch",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            // Poll for the async result (getUserMedia can take ~100-500ms).
            pollSwitchResult(0);
        });
    }

    /** Polls window.__chromeCloneSwitchResult until it resolves or times out. */
    private void pollSwitchResult(final int attempt) {
        if (webView == null) return;
        webView.evaluateJavascript("window.__chromeCloneSwitchResult || 'PENDING'", value -> {
            String v = (value == null) ? "" : value.replace("\"", "");
            if (v.startsWith("SWITCHED:")) {
                String[] parts = v.split(":");
                String facing = parts.length > 1 ? parts[1] : "?";
                Toast.makeText(this,
                        "Camera switched (" + ("environment".equals(facing) ? "back" : "front") + ")",
                        Toast.LENGTH_SHORT).show();
            } else if (v.startsWith("ERROR:")) {
                String msg = v.substring("ERROR:".length());
                Toast.makeText(this, "Switch failed: " + msg, Toast.LENGTH_LONG).show();
            } else if (attempt < 25) { // ~5s max
                webView.postDelayed(() -> pollSwitchResult(attempt + 1), 200);
            } else {
                Toast.makeText(this, "No active camera to switch",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ------------------------------------------------------------------
    // Background keep-alive
    // ------------------------------------------------------------------

    /**
     * Starts the foreground keep-alive service so the app survives backgrounding.
     * Should be called when camera or screen capture is active.
     */
    private void startKeepAliveService() {
        try {
            Intent keepAlive = new Intent(this, KeepAliveService.class);
            ContextCompat.startForegroundService(this, keepAlive);
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "KeepAliveService start failed: " + t);
        }
    }

    /** Stops the keep-alive service so the device can sleep and save power. */
    private void stopKeepAliveService() {
        try {
            Intent stop = new Intent(this, KeepAliveService.class);
            stop.setAction(KeepAliveService.ACTION_STOP);
            startService(stop);
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "KeepAliveService stop failed: " + t);
        }
    }

    /**
     * Reconciles keep-alive state with what's actually running: keep the app
     * alive (foreground service + notification) while a camera stream or
     * screen capture is active; otherwise stop it so the device can sleep.
     * Called from onPause/onStop (app backgrounding) and from the JS bridge
     * when camera state changes.
     */
    private void updateKeepAlive() {
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        boolean capturing = svc != null && svc.isCapturing();
        boolean shouldKeepAlive = cameraActive || capturing;
        if (shouldKeepAlive) {
            startKeepAliveService();
        } else {
            stopKeepAliveService();
        }
    }

    /**
     * Asks the user (once) to exempt ChromeClone from battery optimization.
     * Without this, even a foreground service gets frozen/suspended on many
     * devices once the screen is off or the app is backgrounded. The request
     * fires only when battery optimization is actually active for us.
     */
    private void requestBatteryOptimizationExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;

        SharedPreferences sp = prefs();
        if (sp.getBoolean("battery_exemption_asked", false)) return;
        sp.edit().putBoolean("battery_exemption_asked", true).apply();

        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "Battery exemption request failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // Bookmarks
    // ------------------------------------------------------------------

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** On first launch, populate the default bookmarks. */
    private void seedDefaultBookmarks() {
        SharedPreferences sp = prefs();
        if (!sp.contains(KEY_BOOKMARKS)) {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < DEFAULT_BOOKMARK_URLS.length; i++) {
                Bookmarks.add(arr, DEFAULT_BOOKMARK_URLS[i], DEFAULT_BOOKMARK_TITLES[i]);
            }
            sp.edit().putString(KEY_BOOKMARKS, Bookmarks.serialize(arr)).apply();
        }
    }

    /** Returns bookmark entries as [{url, title}, ...]; never null. */
    private JSONArray loadBookmarks() {
        return Bookmarks.parse(prefs().getString(KEY_BOOKMARKS, null));
    }

    private void saveBookmarks(JSONArray arr) {
        prefs().edit().putString(KEY_BOOKMARKS, Bookmarks.serialize(arr)).apply();
    }

    private boolean isBookmarked(String url) {
        return Bookmarks.contains(loadBookmarks(), url);
    }

    /** Toggle bookmark for the current page. Returns true when now bookmarked. */
    private boolean toggleBookmarkCurrentPage() {
        String url = currentPageUrl;
        if (url == null || url.isEmpty() || url.startsWith("file://")) {
            Toast.makeText(this, "Nothing to bookmark here", Toast.LENGTH_SHORT).show();
            return false;
        }
        JSONArray arr = loadBookmarks();
        if (Bookmarks.removeByUrl(arr, url)) {
            saveBookmarks(arr);
            Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show();
            return false;
        }
        String title = webView != null && webView.getTitle() != null
                ? webView.getTitle() : url;
        Bookmarks.add(arr, url, title);
        saveBookmarks(arr);
        Toast.makeText(this, "Bookmarked", Toast.LENGTH_SHORT).show();
        return true;
    }

    /** Shows the bookmarks panel: tap to open, long-press to delete. */
    private void showBookmarksDialog() {
        final JSONArray arr = loadBookmarks();
        final int n = arr.length();
        final String[] titles = new String[n];
        final String[] urls = new String[n];
        for (int i = 0; i < n; i++) {
            titles[i] = Bookmarks.titleAt(arr, i);
            urls[i] = Bookmarks.urlAt(arr, i);
        }

        // Header action: bookmark/unbookmark the current page.
        String currentUrl = currentPageUrl;
        String header = (currentUrl != null && isBookmarked(currentUrl))
                ? "★ " + getString(R.string.bookmarks_title) + " — remove current page"
                : "☆ " + getString(R.string.bookmarks_title) + " — bookmark current page";

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(header);
        b.setItems(titles, null); // Real click handling below (after show).
        b.setNeutralButton("Toggle current", (d, w) -> toggleBookmarkCurrentPage());
        b.setNegativeButton("Cancel", null);
        AlertDialog dialog = b.create();
        dialog.show();

        // Tap a bookmark to open it.
        dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < urls.length
                    && urls[position] != null && !urls[position].isEmpty()) {
                webView.loadUrl(urls[position]);
                dialog.dismiss();
            }
        });

        // Long-press a bookmark to delete it.
        dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= arr.length()) return true;
            arr.remove(position);
            saveBookmarks(arr);
            Toast.makeText(this, "Bookmark deleted", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            showBookmarksDialog(); // Refresh the list.
            return true;
        });
    }

    private void updateNavButtons() {
        btnBack.setAlpha(webView != null && webView.canGoBack() ? 1.0f : 0.35f);
        btnForward.setAlpha(webView != null && webView.canGoForward() ? 1.0f : 0.35f);
    }

    private void navigateTo(String input) {
        String url = UrlUtils.normalizeUrl(input);
        webView.loadUrl(url);
    }

    // ------------------------------------------------------------------
    // Runtime permissions
    // ------------------------------------------------------------------

    private boolean hasMediaRuntimePermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureRuntimePermissions() {
        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), REQ_RUNTIME_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RUNTIME_PERMISSIONS) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
            if (!allGranted) {
                Toast.makeText(this,
                        "Camera/mic denied: getUserMedia will not work",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------
    // MediaProjection flow (screen share bridge)
    // ------------------------------------------------------------------

    /** Called from JS via the NativeBridge. */
    void requestScreenCaptureFromJs() {
        runOnUiThread(() -> {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                webView.evaluateJavascript(
                        "window.__onScreenError && window.__onScreenError('MediaProjectionManager unavailable');",
                        null);
                return;
            }
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
        });
    }

    /** Called from JS via the NativeBridge. */
    void stopScreenCaptureFromJs() {
        runOnUiThread(() -> {
            Intent stop = new Intent(this, ScreenCaptureService.class);
            stop.setAction(ScreenCaptureService.ACTION_STOP);
            startService(stop);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION || requestCode == REQ_MEDIA_PROJECTION_RESTART) {
            boolean isRestart = requestCode == REQ_MEDIA_PROJECTION_RESTART;
            restartInFlight = false;
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent start = new Intent(this, ScreenCaptureService.class);
                start.setAction(ScreenCaptureService.ACTION_START);
                start.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
                start.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
                ContextCompat.startForegroundService(this, start);
                // Attach the WebView as soon as the service instance exists.
                webView.postDelayed(() -> {
                    ScreenCaptureService svc = ScreenCaptureService.getInstance();
                    if (svc != null) {
                        svc.attachWebView(webView);
                        svc.setRestartHandler(restartHandler);
                    }
                }, 300);
            } else {
                // Consent declined. For a user-initiated Share Screen the page
                // never had a stream, so __onScreenError is the right signal.
                // For an automatic RESTART the page already holds a (dead)
                // stream — sending __onScreenError would reject a pending
                // getDisplayMedia that no longer exists; instead notify the
                // shim that the restart was declined so it can surface a
                // user-visible message and keep the page state consistent.
                if (isRestart) {
                    webView.evaluateJavascript(
                            "window.__onScreenRestartDeclined && window.__onScreenRestartDeclined();",
                            null);
                } else {
                    webView.evaluateJavascript(
                            "window.__onScreenError && window.__onScreenError('Screen capture permission denied');",
                            null);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // JS bridge
    // ------------------------------------------------------------------

    /**
     * JS bridge exposed as window.ChromeCloneNative. Every entry point verifies
     * that the WebView's current page belongs to an allowed origin before doing
     * anything; on disallowed pages the calls log a warning and no-op, so the
     * interface object is inert on arbitrary websites.
     */
    public class NativeBridge {
        private final WeakReference<WebView> webViewRef;

        NativeBridge(WeakReference<WebView> webViewRef) {
            this.webViewRef = webViewRef;
        }

        /** True when the page currently loaded in the WebView may use the bridge. */
        private boolean currentOriginAllowed() {
            WebView wv = webViewRef.get();
            if (wv == null) return false;
            // Read the URL cached on the UI thread. Calling wv.getUrl() directly
            // from this (JavaBridge) thread throws CalledFromWrongThreadException,
            // which surfaces to JS as "Java exception was raised during method
            // invocation" — the exact bug that broke Share Screen.
            String url = currentPageUrl;
            boolean allowed = isBridgeOriginAllowed(url);
            if (!allowed) {
                android.util.Log.w("MainActivity",
                        "NativeBridge call rejected from disallowed origin: " + url);
            }
            return allowed;
        }

        @JavascriptInterface
        public void startScreenCapture() {
            if (!currentOriginAllowed()) return;
            requestScreenCaptureFromJs();
        }

        @JavascriptInterface
        public void stopScreenCapture() {
            if (!currentOriginAllowed()) return;
            stopScreenCaptureFromJs();
        }

        @JavascriptInterface
        public void requestFrame() {
            if (!currentOriginAllowed()) return;
            runOnUiThread(() -> {
                ScreenCaptureService svc = ScreenCaptureService.getInstance();
                if (svc != null) svc.requestFrame();
            });
        }

        /** Called by bridge.js when the page's camera stream starts/stops. */
        @JavascriptInterface
        public void setCameraActive(boolean active) {
            if (!currentOriginAllowed()) return;
            runOnUiThread(() -> {
                cameraActive = active;
                updateKeepAlive();
            });
        }

        /**
         * Heartbeat ack from bridge.js (every 25th frame received). Resets
         * the dead-bridge detector. No origin gate needed — an ack is harmless
         * telemetry; but keep the gate for consistency.
         */
        @JavascriptInterface
        public void ackFrame() {
            ScreenCaptureService svc = ScreenCaptureService.getInstance();
            if (svc != null) svc.ackFrame();
        }

        @JavascriptInterface
        public boolean isBridgeAvailable() {
            // Report availability honestly: pages on disallowed origins must not
            // treat the bridge as usable.
            return currentOriginAllowed();
        }
    }

    // ------------------------------------------------------------------
    // Asset loading / lifecycle
    // ------------------------------------------------------------------

    private String loadAssetText(String name) {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getAssets().open(name);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return "";
        }
        return sb.toString();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        // Pause the WebView renderer in the background (standard hygiene) —
        // the matching onResume() below unpauses it. Keep-alive is handled
        // separately via updateKeepAlive().
        if (webView != null) {
            webView.onPause();
        }
        // Background: the system legitimately pauses the VirtualDisplay
        // mirror, so the capture watchdog must not treat that as a stall.
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc != null) {
            svc.setActivityVisible(false);
        }
        // Going to background: keep alive only if we're actively capturing.
        updateKeepAlive();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.util.Log.i("ChromeClone", "onResume");
        if (webView != null) {
            webView.onResume();
        }
        // Returning from the background: the WebView renderer (and any camera
        // or capture-stream tracks the page holds) may have been paused, which
        // shows as a black preview. Re-attach the WebView to a running capture
        // service and ask the page's JS to restore its camera stream.
        if (webView != null) {
            ScreenCaptureService svc = ScreenCaptureService.getInstance();
            if (svc != null) {
                // Foreground again: re-arm the stall watchdog's visibility
                // gate before anything else touches recovery state.
                svc.setActivityVisible(true);
                svc.attachWebView(webView);
                // Keep the full-restart handler pointing at THIS Activity
                // (it is a long-lived service; the handler must survive
                // Activity recreation).
                svc.setRestartHandler(restartHandler);
                if (svc.isCapturing()) {
                    android.util.Log.i("ChromeClone", "onResume: capture active, requesting frame");
                    // NOTE: do NOT surface-swap here unconditionally. A swap
                    // right after capture starts breaks the freshly-created
                    // VirtualDisplay (black from the very first frame — the
                    // permission-prompt onResume fires immediately after
                    // startCapture). resizeIfNeeded() is safe (no-op when the
                    // size is unchanged); requestFrame() pumps one frame if
                    // the display is alive; wakeIfStalled() performs the swap
                    // ONLY when the pipeline is provably stalled AND has seen
                    // at least one frame, so it can never hit startup.
                    svc.resizeIfNeeded();
                    svc.requestFrame();
                    svc.wakeIfStalled();
                } else {
                    android.util.Log.i("ChromeClone", "onResume: not capturing");
                }
            }
            // Ask the injected bridge to restore any paused camera stream.
            // Use webView.getUrl() as fallback: after activity recreate the
            // cached currentPageUrl may still be empty.
            String url = currentPageUrl;
            if (url == null || url.isEmpty()) {
                url = webView.getUrl();
            }
            if (isBridgeOriginAllowed(url)) {
                webView.evaluateJavascript(
                        "window.__chromeCloneResume && window.__chromeCloneResume();", null);
            }
        }
    }

    @Override
    protected void onDestroy() {
        // Detach the WebView from the long-lived capture service so the service
        // never pins this Activity/WebView in memory after rotation or finish.
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc != null) {
            svc.detachWebView();
            // Same for the restart handler: it captures `this`, so leaving it
            // registered would pin a destroyed Activity.
            svc.setRestartHandler(null);
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
