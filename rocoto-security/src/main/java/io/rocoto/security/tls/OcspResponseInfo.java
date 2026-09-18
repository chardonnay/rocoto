package io.rocoto.security.tls;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The few facts Rocoto reports about a stapled OCSP response: response status, certificate status
 * and validity window. Parsed with a minimal DER reader because the JDK has no public OCSP API.
 * This class only <em>describes</em> a response; validating it is the job of the PKIX revocation
 * checker during the handshake.
 */
public record OcspResponseInfo(String responseStatus, String certStatus, Instant producedAt, Instant thisUpdate,
        Instant nextUpdate) {

    private static final String[] RESPONSE_STATUS = {"successful", "malformedRequest", "internalError", "tryLater",
        "unused", "sigRequired", "unauthorized"};

    public static OcspResponseInfo parse(byte[] der) {
        try {
            Der response = Der.read(der, 0).expect(0x30);
            Der status = response.child(0).expect(0x0A);
            int code = der[status.contentStart] & 0xff;
            String responseStatus = code < RESPONSE_STATUS.length ? RESPONSE_STATUS[code] : "unknown(" + code + ")";
            if (code != 0 || response.childCount() < 2) {
                return new OcspResponseInfo(responseStatus, null, null, null, null);
            }
            Der responseBytes = response.child(1).expect(0xA0).child(0).expect(0x30);
            Der octets = responseBytes.child(1).expect(0x04);
            Der basic = Der.read(der, octets.contentStart).expect(0x30);
            Der tbs = basic.child(0).expect(0x30);
            int i = 0;
            if (tbs.child(i).tag == 0xA0) {
                i++; // explicit version
            }
            i++; // responderID
            Instant producedAt = time(der, tbs.child(i++).expect(0x18));
            Der single = tbs.child(i).expect(0x30).child(0).expect(0x30);
            int certStatusTag = single.child(1).tag;
            String certStatus = switch (certStatusTag) {
                case 0x80 -> "good";
                case 0xA1, 0x81 -> "revoked";
                case 0x82 -> "unknown";
                default -> "unrecognised";
            };
            Instant thisUpdate = time(der, single.child(2).expect(0x18));
            Instant nextUpdate = null;
            if (single.childCount() > 3 && single.child(3).tag == 0xA0) {
                nextUpdate = time(der, single.child(3).child(0).expect(0x18));
            }
            return new OcspResponseInfo(responseStatus, certStatus, producedAt, thisUpdate, nextUpdate);
        } catch (RuntimeException e) {
            return new OcspResponseInfo("unparseable", null, null, null, null);
        }
    }

    private static Instant time(byte[] der, Der node) {
        String text = new String(der, node.contentStart, node.length, StandardCharsets.US_ASCII);
        String digits = text.substring(0, 14);
        return LocalDateTime.parse(digits, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toInstant(ZoneOffset.UTC);
    }

    /** One DER element: tag, content offset and content length within the backing array. */
    private static final class Der {
        final byte[] data;
        final int tag;
        final int contentStart;
        final int length;

        private Der(byte[] data, int tag, int contentStart, int length) {
            this.data = data;
            this.tag = tag;
            this.contentStart = contentStart;
            this.length = length;
        }

        static Der read(byte[] data, int offset) {
            int tag = data[offset] & 0xff;
            int first = data[offset + 1] & 0xff;
            int pos = offset + 2;
            int length;
            if (first < 0x80) {
                length = first;
            } else {
                int bytes = first & 0x7f;
                if (bytes == 0 || bytes > 4) {
                    throw new IllegalArgumentException("unsupported DER length");
                }
                length = 0;
                for (int i = 0; i < bytes; i++) {
                    length = (length << 8) | (data[pos++] & 0xff);
                }
            }
            if (length < 0 || pos + length > data.length) {
                throw new IllegalArgumentException("DER element exceeds input");
            }
            return new Der(data, tag, pos, length);
        }

        Der expect(int expectedTag) {
            if (tag != expectedTag) {
                throw new IllegalArgumentException("unexpected DER tag " + Integer.toHexString(tag));
            }
            return this;
        }

        int end() {
            return contentStart + length;
        }

        Der child(int index) {
            int pos = contentStart;
            for (int i = 0; pos < end(); i++) {
                Der d = read(data, pos);
                if (i == index) {
                    return d;
                }
                pos = d.end();
            }
            throw new IllegalArgumentException("missing DER child " + index);
        }

        int childCount() {
            int pos = contentStart;
            int count = 0;
            while (pos < end()) {
                pos = read(data, pos).end();
                count++;
            }
            return count;
        }
    }
}
