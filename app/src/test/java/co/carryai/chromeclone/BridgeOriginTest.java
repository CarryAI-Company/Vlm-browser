package co.carryai.chromeclone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BridgeOriginTest {

    @Test
    public void allowsBundledTestPage() {
        assertTrue(MainActivity.isBridgeOriginAllowed("file:///android_asset/test.html"));
    }

    @Test
    public void allowsPcfDemoDomain() {
        assertTrue(MainActivity.isBridgeOriginAllowed("https://2026-pcf-demo.carryai.co/live-caption"));
        assertTrue(MainActivity.isBridgeOriginAllowed("https://carryai.co/live-caption"));
        assertTrue(MainActivity.isBridgeOriginAllowed("http://carryai.co/"));
    }

    @Test
    public void allowsLocalhost() {
        assertTrue(MainActivity.isBridgeOriginAllowed("https://localhost:8443/"));
        assertTrue(MainActivity.isBridgeOriginAllowed("http://127.0.0.1/"));
    }

    @Test
    public void deniesArbitrarySites() {
        assertFalse(MainActivity.isBridgeOriginAllowed("https://evil.com/"));
        assertFalse(MainActivity.isBridgeOriginAllowed("https://carryai.co.evil.com/"));
        assertFalse(MainActivity.isBridgeOriginAllowed("https://notcarryai.co/"));
        assertFalse(MainActivity.isBridgeOriginAllowed("https://carryai.com/"));
    }

    @Test
    public void deniesGarbageInput() {
        assertFalse(MainActivity.isBridgeOriginAllowed(null));
        assertFalse(MainActivity.isBridgeOriginAllowed(""));
        assertFalse(MainActivity.isBridgeOriginAllowed("::garbage::"));
        assertFalse(MainActivity.isBridgeOriginAllowed("javascript:alert(1)"));
        assertFalse(MainActivity.isBridgeOriginAllowed("file:///etc/passwd"));
    }
}
