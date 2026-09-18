package io.rocoto.security.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One audit record. Rendered as a single JSON line. */
public record AuditEvent(Instant timestamp, String user, String authSource, String remoteAddress,
        String channel, String action, String mbean, String member, Outcome outcome, String reason,
        Object arguments) {

    public enum Outcome { ALLOWED, DENIED, FAILED, AUTH_FAILED, AUTH_LOCKED }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", timestamp.toString());
        m.put("user", user);
        m.put("auth", authSource);
        m.put("remote", remoteAddress);
        m.put("channel", channel);
        m.put("action", action);
        if (mbean != null) {
            m.put("mbean", mbean);
        }
        if (member != null) {
            m.put("member", member);
        }
        m.put("outcome", outcome.name());
        if (reason != null) {
            m.put("reason", reason);
        }
        if (arguments != null) {
            m.put("args", arguments);
        }
        return m;
    }
}
