package co.carryai.chromeclone;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the Phase-2 native upload pipeline primitives:
 *   - CaptureConfig.toWsInferenceUrl (server URL normalization)
 *   - FrameUploader.encodeFrame     (wire-protocol packet layout)
 */
public class FrameUploaderTest {

    // ------------------------------------------------------------------
    // Server URL normalization
    // ------------------------------------------------------------------

    @Test
    public void url_plainWsPassesThrough() {
        assertEquals("ws://192.168.1.3:5050/ws/inference",
                CaptureConfig.toWsInferenceUrl("ws://192.168.1.3:5050"));
    }

    @Test
    public void url_wssPassesThrough() {
        assertEquals("wss://vlm.example.com/ws/inference",
                CaptureConfig.toWsInferenceUrl("wss://vlm.example.com"));
    }

    @Test
    public void url_httpBecomesWs() {
        assertEquals("ws://192.168.1.3:5050/ws/inference",
                CaptureConfig.toWsInferenceUrl("http://192.168.1.3:5050"));
    }

    @Test
    public void url_httpsBecomesWss() {
        assertEquals("wss://vlm.example.com/ws/inference",
                CaptureConfig.toWsInferenceUrl("https://vlm.example.com"));
    }

    @Test
    public void url_bareHostPortAssumesWs() {
        assertEquals("ws://10.0.0.5:5050/ws/inference",
                CaptureConfig.toWsInferenceUrl("10.0.0.5:5050"));
    }

    @Test
    public void url_stripsTrailingSlashAndPath() {
        assertEquals("ws://h:1/ws/inference",
                CaptureConfig.toWsInferenceUrl("ws://h:1/anything/else/"));
    }

    @Test
    public void url_trimsWhitespace() {
        assertEquals("ws://h:1/ws/inference",
                CaptureConfig.toWsInferenceUrl("  ws://h:1  "));
    }

    @Test
    public void url_nullAndBlankReturnNull() {
        assertNull(CaptureConfig.toWsInferenceUrl(null));
        assertNull(CaptureConfig.toWsInferenceUrl(""));
        assertNull(CaptureConfig.toWsInferenceUrl("   "));
        assertNull(CaptureConfig.toWsInferenceUrl("ws://"));
    }

    // ------------------------------------------------------------------
    // Wire protocol: [4-byte BE header len][JSON header][jpeg]
    // ------------------------------------------------------------------

    @Test
    public void encode_layoutAndHeaderRoundTrip() throws JSONException {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xD9};
        byte[] packet = FrameUploader.encodeFrame(jpeg, "Describe", null, "cam-1");

        int headerLen = ((packet[0] & 0xFF) << 24) | ((packet[1] & 0xFF) << 16)
                | ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        assertTrue(headerLen > 0);
        assertTrue(packet.length == 4 + headerLen + jpeg.length);

        String headerJson = new String(packet, 4, headerLen, StandardCharsets.UTF_8);
        JSONObject header = new JSONObject(headerJson);
        assertEquals("Describe", header.getString("instruction"));
        assertEquals("cam-1", header.getString("stream_id"));
        assertEquals(1, header.getInt("num_images"));
        // system_prompt omitted when null.
        assertTrue(header.isNull("system_prompt") || !header.has("system_prompt"));

        byte[] trailing = new byte[jpeg.length];
        System.arraycopy(packet, 4 + headerLen, trailing, 0, jpeg.length);
        assertArrayEquals(jpeg, trailing);
    }

    @Test
    public void encode_includesSystemPromptWhenPresent() throws JSONException {
        byte[] packet = FrameUploader.encodeFrame(new byte[]{1}, "i", "be brief", "s");
        int headerLen = ((packet[0] & 0xFF) << 24) | ((packet[1] & 0xFF) << 16)
                | ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        JSONObject header = new JSONObject(
                new String(packet, 4, headerLen, StandardCharsets.UTF_8));
        assertEquals("be brief", header.getString("system_prompt"));
    }

    @Test
    public void encode_defaultsStreamIdAndInstruction() throws JSONException {
        byte[] packet = FrameUploader.encodeFrame(new byte[]{1}, null, null, null);
        int headerLen = ((packet[0] & 0xFF) << 24) | ((packet[1] & 0xFF) << 16)
                | ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        JSONObject header = new JSONObject(
                new String(packet, 4, headerLen, StandardCharsets.UTF_8));
        assertEquals("", header.getString("instruction"));
        assertEquals("chromeclone-android", header.getString("stream_id"));
    }
}
