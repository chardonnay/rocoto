package io.rocoto.security;

/**
 * The policy denied the request. The message is deliberately generic; the precise reason is
 * written to the audit log only, so that the policy cannot be probed from outside.
 */
public class AccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccessDeniedException() {
        super("Access denied by policy");
    }
}
