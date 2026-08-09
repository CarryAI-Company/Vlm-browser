package co.carryai.chromeclone;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure-Java helpers for manipulating the bookmark list stored as a JSON array
 * of {"url": ..., "title": ...} objects. Kept free of Android framework calls
 * so the logic is unit-testable on the JVM.
 */
public final class Bookmarks {

    public static final String FIELD_URL = "url";
    public static final String FIELD_TITLE = "title";

    private Bookmarks() {
    }

    /** Parses a stored JSON string; returns an empty array on malformed input. */
    public static JSONArray parse(String raw) {
        if (raw == null || raw.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public static String serialize(JSONArray arr) {
        return arr.toString();
    }

    public static boolean contains(JSONArray arr, String url) {
        if (url == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && url.equals(o.optString(FIELD_URL))) return true;
        }
        return false;
    }

    /** Adds an entry; if the url already exists, keeps the existing one. */
    public static boolean add(JSONArray arr, String url, String title) {
        if (url == null || url.isEmpty() || contains(arr, url)) return false;
        try {
            JSONObject o = new JSONObject();
            o.put(FIELD_URL, url);
            o.put(FIELD_TITLE, title == null || title.isEmpty() ? url : title);
            arr.put(o);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /** Removes the entry with the given url. Returns true when something was removed. */
    public static boolean removeByUrl(JSONArray arr, String url) {
        if (url == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && url.equals(o.optString(FIELD_URL))) {
                arr.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Removes the entry at the given index. Returns true when in range. */
    public static boolean removeAt(JSONArray arr, int index) {
        if (index < 0 || index >= arr.length()) return false;
        arr.remove(index);
        return true;
    }

    public static String titleAt(JSONArray arr, int index) {
        JSONObject o = arr.optJSONObject(index);
        if (o == null) return "(invalid)";
        String t = o.optString(FIELD_TITLE);
        return t != null && !t.isEmpty() ? t : o.optString(FIELD_URL);
    }

    public static String urlAt(JSONArray arr, int index) {
        JSONObject o = arr.optJSONObject(index);
        return o != null ? o.optString(FIELD_URL) : "";
    }
}
