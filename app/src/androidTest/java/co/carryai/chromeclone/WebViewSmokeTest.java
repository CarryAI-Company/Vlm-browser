package co.carryai.chromeclone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented smoke tests. Compiled (assembleAndroidTest) to prove the
 * androidTest source set builds; designed to also pass on a real device.
 */
@RunWith(AndroidJUnit4.class)
public class WebViewSmokeTest {

    @Test
    public void appContext_hasCorrectPackage() {
        Context context = ApplicationProvider.getApplicationContext();
        assertNotNull(context);
        assertTrue(context.getPackageName().startsWith("co.carryai.chromeclone"));
    }

    @Test
    public void webView_canBeConstructed_andConfiguredLikeApp() {
        Context context = ApplicationProvider.getApplicationContext();
        WebView webView = new WebView(context);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        assertTrue(s.getJavaScriptEnabled());
        assertTrue(s.getDomStorageEnabled());
        assertNotNull(s.getUserAgentString());
    }

    @Test
    public void screenCaptureService_intentActions_areDistinct() {
        Intent start = new Intent(ScreenCaptureService.ACTION_START);
        Intent stop = new Intent(ScreenCaptureService.ACTION_STOP);
        assertEquals("co.carryai.chromeclone.action.START_CAPTURE", start.getAction());
        assertEquals("co.carryai.chromeclone.action.STOP_CAPTURE", stop.getAction());
    }

    @Test
    public void urlUtils_worksOnDevice() {
        assertEquals("https://example.com", UrlUtils.normalizeUrl("example.com"));
        assertEquals("about:blank", UrlUtils.normalizeUrl(null));
    }
}
