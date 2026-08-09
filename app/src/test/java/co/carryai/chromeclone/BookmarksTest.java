package co.carryai.chromeclone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;

public class BookmarksTest {

    @Test
    public void parseHandlesNullAndEmpty() {
        assertEquals(0, Bookmarks.parse(null).length());
        assertEquals(0, Bookmarks.parse("").length());
        assertEquals(0, Bookmarks.parse("not json {").length());
    }

    @Test
    public void parseRoundTrip() {
        JSONArray arr = new JSONArray();
        Bookmarks.add(arr, "https://a.com", "A");
        Bookmarks.add(arr, "https://b.com", "B");
        JSONArray parsed = Bookmarks.parse(Bookmarks.serialize(arr));
        assertEquals(2, parsed.length());
        assertEquals("A", Bookmarks.titleAt(parsed, 0));
        assertEquals("https://b.com", Bookmarks.urlAt(parsed, 1));
    }

    @Test
    public void addRejectsDuplicateUrls() {
        JSONArray arr = new JSONArray();
        assertTrue(Bookmarks.add(arr, "https://a.com", "A"));
        assertFalse(Bookmarks.add(arr, "https://a.com", "A2"));
        assertEquals(1, arr.length());
        assertEquals("A", Bookmarks.titleAt(arr, 0)); // First wins.
    }

    @Test
    public void addRejectsNullAndEmptyUrls() {
        JSONArray arr = new JSONArray();
        assertFalse(Bookmarks.add(arr, null, "x"));
        assertFalse(Bookmarks.add(arr, "", "x"));
        assertEquals(0, arr.length());
    }

    @Test
    public void titleFallsBackToUrl() {
        JSONArray arr = new JSONArray();
        Bookmarks.add(arr, "https://a.com", "");
        assertEquals("https://a.com", Bookmarks.titleAt(arr, 0));
    }

    @Test
    public void containsIsUrlBased() {
        JSONArray arr = new JSONArray();
        Bookmarks.add(arr, "https://a.com", "A");
        assertTrue(Bookmarks.contains(arr, "https://a.com"));
        assertFalse(Bookmarks.contains(arr, "https://b.com"));
        assertFalse(Bookmarks.contains(arr, null));
    }

    @Test
    public void removeByUrl() {
        JSONArray arr = new JSONArray();
        Bookmarks.add(arr, "https://a.com", "A");
        Bookmarks.add(arr, "https://b.com", "B");
        assertTrue(Bookmarks.removeByUrl(arr, "https://a.com"));
        assertEquals(1, arr.length());
        assertEquals("https://b.com", Bookmarks.urlAt(arr, 0));
        assertFalse(Bookmarks.removeByUrl(arr, "https://a.com")); // Already gone.
        assertFalse(Bookmarks.removeByUrl(arr, null));
    }

    @Test
    public void removeAtBounds() {
        JSONArray arr = new JSONArray();
        Bookmarks.add(arr, "https://a.com", "A");
        assertTrue(Bookmarks.removeAt(arr, 0));
        assertFalse(Bookmarks.removeAt(arr, 0)); // Empty now.
        assertFalse(Bookmarks.removeAt(arr, -1));
    }

    @Test
    public void outOfRangeAccessReturnsSafeDefaults() {
        JSONArray arr = new JSONArray();
        assertEquals("(invalid)", Bookmarks.titleAt(arr, 0));
        assertEquals("", Bookmarks.urlAt(arr, 5));
    }

    @Test
    public void defaultBookmarkListSeedsWithPcfDemo() {
        JSONArray arr = new JSONArray();
        Bookmarks.add(arr, "https://2026-pcf-demo.carryai.co/live-caption", "PCF Demo · Live Caption");
        assertEquals(1, arr.length());
        assertTrue(Bookmarks.contains(arr, "https://2026-pcf-demo.carryai.co/live-caption"));
    }
}
