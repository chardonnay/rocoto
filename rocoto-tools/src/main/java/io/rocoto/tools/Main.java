package io.rocoto.tools;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.rocoto.security.auth.ApiTokens;
import io.rocoto.security.auth.PasswordHasher;
import io.rocoto.security.tls.OcspResponseInfo;
import io.rocoto.security.tls.TlsProbe;

/** {@code java -jar rocoto-tools.jar <command>} */
public final class Main {

    private static final String USAGE = """
            Rocoto tools

              user add <name> --roles <r1,r2> [--file rocoto-users.yaml] [--password-stdin]
              user remove <name> [--file rocoto-users.yaml]
              token create <id> --roles <r1,r2> [--days <n> | --expires <ISO-8601>]
                                [--mbeans "<pattern>;<pattern>"] [--file rocoto-users.yaml]
              token revoke <id> [--file rocoto-users.yaml]
              hash [--password-stdin]            print a PBKDF2 hash for a password
              policy convert <jolokia-access.xml>  print a Rocoto policy for a Jolokia policy
              tls check <host:port>              show certificate chain and OCSP stapling status

            Passwords are read from the terminal (or from stdin with --password-stdin),
            never from the command line. Standard roles: rocoto-reader, rocoto-operator, rocoto-admin.
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            Args a = new Args(args);
            String command = a.positional(0) + " " + (a.positional(1) == null ? "" : a.positional(1));
            switch (command.trim()) {
                case "user add" -> userAdd(a, out);
                case "user remove" -> {
                    new UserFile(a.file()).removeUser(a.required(2, "user name"));
                    out.println("Removed user " + a.positional(2));
                }
                case "token create" -> tokenCreate(a, out);
                case "token revoke" -> {
                    new UserFile(a.file()).removeToken(a.required(2, "token id"));
                    out.println("Revoked token " + a.positional(2));
                }
                case "hash" -> out.println(PasswordHasher.hash(readPassword(a)));
                case "policy convert" -> out.print(PolicyConverter.convert(Path.of(a.required(2, "policy file"))));
                case "tls check" -> tlsCheck(a.required(2, "host:port"), out);
                default -> {
                    err.print(USAGE);
                    return args.length == 0 || a.positional(0).equals("help") ? 0 : 2;
                }
            }
            return 0;
        } catch (IllegalArgumentException | IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static void userAdd(Args a, PrintStream out) throws IOException {
        String name = a.required(2, "user name");
        if (!name.matches("[A-Za-z0-9._@-]{1,128}") || name.startsWith("token:")) {
            throw new IllegalArgumentException("user names may contain letters, digits and . _ @ -");
        }
        List<String> roles = a.list("roles", ",");
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("--roles is required, e.g. --roles rocoto-reader");
        }
        char[] password = readPassword(a);
        if (password.length < 12) {
            throw new IllegalArgumentException("passwords must have at least 12 characters");
        }
        new UserFile(a.file()).putUser(name, PasswordHasher.hash(password), roles);
        Arrays.fill(password, '\0');
        out.println("User " + name + " written to " + a.file());
    }

    private static void tokenCreate(Args a, PrintStream out) throws IOException {
        String id = a.required(2, "token id");
        List<String> roles = a.list("roles", ",");
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("--roles is required, e.g. --roles rocoto-reader");
        }
        Instant expires = null;
        if (a.option("days") != null) {
            expires = Instant.now().plus(Duration.ofDays(Long.parseLong(a.option("days")))).truncatedTo(ChronoUnit.SECONDS);
        } else if (a.option("expires") != null) {
            expires = Instant.parse(a.option("expires"));
        }
        ApiTokens.Generated generated = ApiTokens.generate(id);
        new UserFile(a.file()).putToken(id, generated.storedHash(), roles, expires, a.list("mbeans", ";"));
        out.println("Token " + id + " written to " + a.file() + (expires == null ? "" : " (expires " + expires + ")"));
        out.println("The token is shown only once - store it now:");
        out.println();
        out.println("  " + generated.token());
        out.println();
        out.println("Use it as:  Authorization: Bearer " + generated.token().substring(0, 12) + "...");
    }

    private static void tlsCheck(String target, PrintStream out) throws IOException {
        int colon = target.lastIndexOf(':');
        String host = colon < 0 ? target : target.substring(0, colon);
        int port = colon < 0 ? 443 : Integer.parseInt(target.substring(colon + 1));
        TlsProbe.Result r = TlsProbe.probe(host, port, 10_000);
        out.println("Connected to " + host + ":" + port + " using " + r.protocol() + " / " + r.cipherSuite());
        out.println();
        out.println("Certificate chain:");
        int i = 0;
        for (TlsProbe.CertificateInfo c : r.chain()) {
            long days = Duration.between(Instant.now(), c.notAfter()).toDays();
            out.println("  [" + i++ + "] " + c.subject());
            out.println("      issuer:  " + c.issuer());
            out.println("      expires: " + c.notAfter() + (days < 0 ? "  ** EXPIRED **" : "  (" + days + " days)"));
        }
        out.println();
        if (!r.stapled()) {
            out.println("OCSP stapling: NO - the server did not staple an OCSP response.");
            out.println("  JSSE based connectors (WildFly/Undertow, Tomcat NIO): start the JVM with");
            out.println("    -Djdk.tls.server.enableStatusRequestExtension=true");
            out.println("  and make sure the certificate carries an OCSP responder URL (AIA extension).");
            return;
        }
        OcspResponseInfo ocsp = r.ocsp();
        out.println("OCSP stapling: YES");
        out.println("  response status:    " + ocsp.responseStatus());
        out.println("  certificate status: " + ocsp.certStatus());
        out.println("  produced at:        " + ocsp.producedAt());
        out.println("  this update:        " + ocsp.thisUpdate());
        out.println("  next update:        " + ocsp.nextUpdate());
    }

    private static char[] readPassword(Args a) throws IOException {
        if (a.flag("password-stdin")) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || line.isEmpty()) {
                throw new IllegalArgumentException("no password on stdin");
            }
            return line.toCharArray();
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalArgumentException("no terminal available - use --password-stdin");
        }
        char[] first = console.readPassword("Password: ");
        char[] second = console.readPassword("Repeat password: ");
        if (first == null || !Arrays.equals(first, second)) {
            throw new IllegalArgumentException("passwords do not match");
        }
        return first;
    }

    /** Minimal argument parser: positionals, {@code --key value} options and {@code --flag}s. */
    static final class Args {
        private final List<String> positionals = new ArrayList<>();
        private final Map<String, String> options = new LinkedHashMap<>();

        Args(String[] args) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("--")) {
                    String key = arg.substring(2);
                    boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--")
                            && !key.equals("password-stdin");
                    options.put(key, hasValue ? args[++i] : "true");
                } else {
                    positionals.add(arg);
                }
            }
        }

        String positional(int index) {
            return index < positionals.size() ? positionals.get(index) : null;
        }

        String required(int index, String what) {
            String v = positional(index);
            if (v == null) {
                throw new IllegalArgumentException("missing " + what);
            }
            return v;
        }

        String option(String key) {
            return options.get(key);
        }

        boolean flag(String key) {
            return options.containsKey(key);
        }

        List<String> list(String key, String separator) {
            String v = options.get(key);
            List<String> out = new ArrayList<>();
            if (v != null) {
                for (String part : v.split(separator)) {
                    if (!part.isBlank()) {
                        out.add(part.trim());
                    }
                }
            }
            return out;
        }

        Path file() {
            return Path.of(options.getOrDefault("file", "rocoto-users.yaml"));
        }
    }
}
