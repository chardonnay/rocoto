package io.rocoto.security.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class OcspResponseInfoTest {

    private static byte[] der(int tag, byte[]... contents) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] c : contents) {
            body.writeBytes(c);
        }
        byte[] content = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (content.length < 0x80) {
            out.write(content.length);
        } else {
            out.write(0x82);
            out.write(content.length >> 8);
            out.write(content.length & 0xff);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static byte[] time(String generalizedTime) {
        return der(0x18, generalizedTime.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] response(byte[] certStatus) {
        byte[] certId = der(0x30, der(0x30, der(0x06, new byte[] {0x2b, 0x0e, 0x03, 0x02, 0x1a})),
                der(0x04, new byte[20]), der(0x04, new byte[20]), der(0x02, new byte[] {0x01}));
        byte[] single = der(0x30, certId, certStatus, time("20260918120000Z"),
                der(0xA0, time("20260925120000Z")));
        byte[] tbs = der(0x30, der(0xA2, der(0x04, new byte[20])), time("20260918120500Z"), der(0x30, single));
        byte[] basic = der(0x30, tbs, der(0x30, der(0x06, new byte[] {0x2a})), der(0x03, new byte[] {0x00, 0x01}));
        byte[] responseBytes = der(0xA0, der(0x30,
                der(0x06, new byte[] {0x2b, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x01, 0x01}), der(0x04, basic)));
        return der(0x30, der(0x0A, new byte[] {0x00}), responseBytes);
    }

    @Test
    void parsesGoodResponse() {
        OcspResponseInfo info = OcspResponseInfo.parse(response(der(0x80)));
        assertEquals("successful", info.responseStatus());
        assertEquals("good", info.certStatus());
        assertEquals(Instant.parse("2026-09-18T12:00:00Z"), info.thisUpdate());
        assertEquals(Instant.parse("2026-09-25T12:00:00Z"), info.nextUpdate());
        assertEquals(Instant.parse("2026-09-18T12:05:00Z"), info.producedAt());
    }

    @Test
    void parsesRevokedResponse() {
        OcspResponseInfo info = OcspResponseInfo.parse(response(der(0xA1, time("20260901000000Z"))));
        assertEquals("revoked", info.certStatus());
    }

    @Test
    void reportsNonSuccessfulAndGarbageResponses() {
        OcspResponseInfo tryLater = OcspResponseInfo.parse(der(0x30, der(0x0A, new byte[] {0x03})));
        assertEquals("tryLater", tryLater.responseStatus());
        assertNull(tryLater.certStatus());
        assertEquals("unparseable", OcspResponseInfo.parse(new byte[] {1, 2, 3}).responseStatus());
    }
}
