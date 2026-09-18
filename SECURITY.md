# Security Policy

Rocoto exposes management functionality of a Java application server over HTTP.
We treat every vulnerability in it as serious.

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Use GitHub's private vulnerability reporting
(**Security → Report a vulnerability** on this repository). Include:

- the affected version / commit,
- the application server and its version,
- your `rocoto.yaml` (with secrets removed),
- steps to reproduce and the impact you observed.

You will get an acknowledgement within 5 working days. Fixes for confirmed
issues are released as soon as possible, followed by a published advisory.

## Supported versions

Until 1.0 only the latest release receives security fixes.

## Design guarantees we consider security-relevant

A report is in scope whenever one of these guarantees can be violated:

- No request is processed without an authenticated identity.
- `write` and `exec` are denied unless explicitly allowed by the policy.
- MBeans on the built-in deny list can never be written to or executed.
- Masked attribute values never leave the server.
- Rocoto never opens connections to JMX targets supplied by a client (no proxy mode).
- Every `write`, `exec`, denial and failed login produces an audit record.
