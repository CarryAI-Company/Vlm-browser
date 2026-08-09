package co.carryai.chromeclone;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM unit tests for {@link Base64Utils}. */
public class Base64UtilsTest {

    @Test
    public void encode_emptyInput_returnsEmpty() {
        assertEquals("", Base64Utils.encode(new byte[0]));
    }

    @Test
    public void encode_nullInput_returnsEmpty() {
        assertEquals("", Base64Utils.encode(null));
    }

    @Test
    public void encode_knownVectors() {
        // RFC 4648 test vectors.
        assertEquals("", Base64Utils.encode("".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zg==", Base64Utils.encode("f".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zm8=", Base64Utils.encode("fo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zm9v", Base64Utils.encode("foo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zm9vYg==", Base64Utils.encode("foob".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zm9vYmE=", Base64Utils.encode("fooba".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zm9vYmFy", Base64Utils.encode("foobar".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void encode_matchesJavaUtilBase64() {
        // Fuzz against java.util.Base64 for a range of lengths.
        for (int len = 1; len <= 64; len++) {
            byte[] data = new byte[len];
            for (int i = 0; i < len; i++) data[i] = (byte) (i * 31 + len);
            String expected = java.util.Base64.getEncoder().encodeToString(data);
            assertEquals("mismatch at length " + len, expected, Base64Utils.encode(data));
        }
    }

    @Test
    public void encode_binaryData_noWhitespace() {
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) data[i] = (byte) i;
        String encoded = Base64Utils.encode(data);
        assertFalse(encoded.contains("\n"));
        assertFalse(encoded.contains("\r"));
        assertFalse(encoded.contains(" "));
        assertEquals(java.util.Base64.getEncoder().encodeToString(data), encoded);
    }

    @Test
    public void toJpegDataUrl_hasCorrectPrefix() {
        String url = Base64Utils.toJpegDataUrl("fakejpeg".getBytes(StandardCharsets.US_ASCII));
        assertTrue(url.startsWith("data:image/jpeg;base64,"));
        assertTrue(url.endsWith("ZmFrZWpwZWc="));
    }
}
