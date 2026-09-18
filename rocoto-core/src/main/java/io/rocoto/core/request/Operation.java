package io.rocoto.core.request;

/** What a request wants to do. Policy rules and audit records are expressed in these terms. */
public enum Operation {
    READ, WRITE, EXEC, LIST, SEARCH;

    /** Operations that change server state and are therefore denied unless explicitly allowed. */
    public boolean isMutating() {
        return this == WRITE || this == EXEC;
    }
}
