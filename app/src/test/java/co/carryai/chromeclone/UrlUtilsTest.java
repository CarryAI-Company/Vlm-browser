package co.carryai.chromeclone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM unit tests for {@link UrlUtils}. */
public class UrlUtilsTest {

    @Test
    public void normalizeUrl_bareHost_getsHttps() {
        assertEquals("https://example.com", UrlUtils.normalizeUrl("example.com"));
    }

    @Test
    public void normalizeUrl_bareHostWithPath_getsHttps() {
        assertEquals("https://example.com/path/page.html",
                UrlUtils.normalizeUrl("example.com/path/page.html"));
    }

    @Test
    public void normalizeUrl_existingHttpScheme_unchanged() {
        assertEquals("http://example.com", UrlUtils.normalizeUrl("http://example.com"));
    }

    @Test
    public void normalizeUrl_existingHttpsScheme_unchanged() {
        assertEquals("https://example.com", UrlUtils.normalizeUrl("https://example.com"));
    }

    @Test
    public void normalizeUrl_fileScheme_unchanged() {
        assertEquals("file:///android_asset/test.html",
                UrlUtils.normalizeUrl("file:///android_asset/test.html"));
    }

    @Test
    public void normalizeUrl_aboutScheme_unchanged() {
        assertEquals("about:blank", UrlUtils.normalizeUrl("about:blank"));
    }

    @Test
    public void normalizeUrl_emptyAndNull_fallToAboutBlank() {
        assertEquals("about:blank", UrlUtils.normalizeUrl(""));
        assertEquals("about:blank", UrlUtils.normalizeUrl("   "));
        assertEquals("about:blank", UrlUtils.normalizeUrl(null));
    }

    @Test
    public void normalizeUrl_searchQuery_goesToGoogle() {
        String url = UrlUtils.normalizeUrl("hello world");
        assertTrue("query should become a google search URL, got: " + url,
                url.startsWith("https://www.google.com/search?q="));
        assertTrue(url.contains("hello+world"));
    }

    @Test
    public void normalizeUrl_singleWord_treatedAsSearch() {
        String url = UrlUtils.normalizeUrl("chromecast");
        assertTrue(url.startsWith("https://www.google.com/search?q="));
    }

    @Test
    public void normalizeUrl_trimsWhitespace() {
        assertEquals("https://example.com", UrlUtils.normalizeUrl("  example.com  "));
    }

    @Test
    public void hasScheme_detectsSchemes() {
        assertTrue(UrlUtils.hasScheme("https://x.com"));
        assertTrue(UrlUtils.hasScheme("file:///android_asset/test.html"));
        assertTrue(UrlUtils.hasScheme("about:blank"));
        assertFalse(UrlUtils.hasScheme("example.com"));
        assertFalse(UrlUtils.hasScheme(null));
    }

    @Test
    public void urlEncode_encodesReservedCharacters() {
        assertEquals("a+b", UrlUtils.urlEncode("a b"));
        assertEquals("a%26b", UrlUtils.urlEncode("a&b"));
        assertEquals("plain-ok_1.2~3", UrlUtils.urlEncode("plain-ok_1.2~3"));
    }
}
