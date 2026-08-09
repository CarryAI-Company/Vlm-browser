package co.carryai.chromeclone;

/**
 * Pure-Java Base64 encoder used by the screen-capture frame pipeline.
 * Kept dependency-free so it is unit-testable on the JVM (android.util.Base64
 * is not available in local unit tests).
 */
public final class Base64Utils {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private Base64Utils() {}

    /** Standard RFC 4648 Base64 encoding with padding. */
    public static String encode(byte[] data) {
        if (data == null) return "";
        StringBuilder out = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        int n = data.length;
        while (i + 3 <= n) {
            int chunk = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            out.append(ALPHABET[(chunk >> 18) & 0x3F]);
            out.append(ALPHABET[(chunk >> 12) & 0x3F]);
            out.append(ALPHABET[(chunk >> 6) & 0x3F]);
            out.append(ALPHABET[chunk & 0x3F]);
            i += 3;
        }
        int remaining = n - i;
        if (remaining == 1) {
            int chunk = (data[i] & 0xFF) << 16;
            out.append(ALPHABET[(chunk >> 18) & 0x3F]);
            out.append(ALPHABET[(chunk >> 12) & 0x3F]);
            out.append('=');
            out.append('=');
        } else if (remaining == 2) {
            int chunk = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            out.append(ALPHABET[(chunk >> 18) & 0x3F]);
            out.append(ALPHABET[(chunk >> 12) & 0x3F]);
            out.append(ALPHABET[(chunk >> 6) & 0x3F]);
            out.append('=');
        }
        return out.toString();
    }

    /**
     * Wraps a raw JPEG byte array as a data: URL string suitable for
     * assigning to an HTMLImageElement's src.
     */
    public static String toJpegDataUrl(byte[] jpegBytes) {
        return "data:image/jpeg;base64," + encode(jpegBytes);
    }
}
