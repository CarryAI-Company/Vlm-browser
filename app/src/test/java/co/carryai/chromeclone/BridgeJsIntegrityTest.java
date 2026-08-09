package co.carryai.chromeclone;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * JVM unit test verifying the integrity of the injected JavaScript bridge shim
 * (app/src/main/assets/bridge.js). This guards against accidental regressions of
 * the getDisplayMedia override contract that pages rely on.
 */
public class BridgeJsIntegrityTest {

    private static String bridgeJs;

    @BeforeClass
    public static void loadBridgeJs() throws IOException {
        // Unit tests run with the module dir as working directory; resolve the
        // asset relative to it, with fallbacks for robustness.
        String[] candidates = {
                "src/main/assets/bridge.js",
                "app/src/main/assets/bridge.js",
                "../app/src/main/assets/bridge.js"
        };
        for (String candidate : candidates) {
            Path p = Paths.get(candidate);
            if (Files.exists(p)) {
                bridgeJs = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                break;
            }
        }
        if (bridgeJs == null) {
            fail("bridge.js asset not found from unit-test working directory");
        }
    }

    @Test
    public void shim_overridesGetDisplayMedia() {
        assertTrue("shim must assign mediaDevices.getDisplayMedia",
                bridgeJs.contains("getDisplayMedia = function"));
        assertTrue("shim must defineProperty-patch mediaDevices.getDisplayMedia",
                bridgeJs.contains("'getDisplayMedia'"));
    }

    @Test
    public void shim_definesOnScreenFrameHandler() {
        assertTrue("shim must define window.__onScreenFrame",
                bridgeJs.contains("window.__onScreenFrame"));
    }

    @Test
    public void shim_usesCanvasCaptureStream() {
        assertTrue("shim must build the stream with canvas.captureStream",
                bridgeJs.contains("captureStream("));
    }

    @Test
    public void shim_callsNativeBridgeStartAndStop() {
        assertTrue("shim must call ChromeCloneNative.startScreenCapture",
                bridgeJs.contains("ChromeCloneNative.startScreenCapture"));
        assertTrue("shim must call ChromeCloneNative.stopScreenCapture",
                bridgeJs.contains("ChromeCloneNative.stopScreenCapture"));
    }

    @Test
    public void shim_definesLifecycleCallbacks() {
        assertTrue(bridgeJs.contains("window.__onScreenStarted"));
        assertTrue(bridgeJs.contains("window.__onScreenEnded"));
        assertTrue(bridgeJs.contains("window.__onScreenError"));
    }

    @Test
    public void shim_wrapsEnumerateDevicesWithVirtualScreenDevice() {
        assertTrue("shim must wrap enumerateDevices",
                bridgeJs.contains("enumerateDevices = function"));
        assertTrue("shim must expose a virtual screen device id",
                bridgeJs.contains("chromeclone-screen"));
    }

    @Test
    public void shim_isIdempotent() {
        assertTrue("shim must guard against double-injection",
                bridgeJs.contains("__chromeCloneShimInstalled"));
    }

    @Test
    public void shim_exposesStandaloneRePatchFunction() {
        // onPageFinished re-injection relies on a separately-callable patch that
        // re-applies getDisplayMedia even when the installer guard already ran.
        assertTrue("shim must expose window.__chromeClonePatch",
                bridgeJs.contains("window.__chromeClonePatch"));
        assertTrue("shim must re-patch on DOMContentLoaded",
                bridgeJs.contains("DOMContentLoaded"));
        assertTrue("shim must persist state across injections",
                bridgeJs.contains("__chromeCloneState"));
    }

    @Test
    public void shim_marksBridgeVersion() {
        assertTrue(bridgeJs.contains("__chromeCloneBridge"));
        assertTrue(bridgeJs.contains("getDisplayMediaBridged: true"));
    }

    @Test
    public void shim_isNonTrivialAndBalanced() {
        assertTrue("shim should be a real implementation, not a stub",
                bridgeJs.length() > 2000);
        long opens = bridgeJs.chars().filter(c -> c == '{').count();
        long closes = bridgeJs.chars().filter(c -> c == '}').count();
        assertTrue("unbalanced braces in bridge.js: " + opens + " vs " + closes,
                opens == closes && opens > 10);
        assertFalse("bridge.js must not contain TODO stubs", bridgeJs.contains("TODO"));
    }
}
