package io.rocoto.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.rocoto.core.config.ConfigLoader;
import io.rocoto.security.auth.ApiTokens;
import io.rocoto.security.auth.PasswordHasher;
import io.rocoto.security.auth.UserStore;
import io.rocoto.security.policy.AccessPolicy;

class ToolsTest {

    private static String run(String stdin, String... args) {
        InputStream original = System.in;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
            int code = Main.run(args, ps, ps);
            return code + "\n" + out.toString(StandardCharsets.UTF_8);
        } finally {
            System.setIn(original);
        }
    }

    @Test
    void usersAndTokensCreatedByTheToolAreAcceptedByTheServer(@TempDir Path dir) throws Exception {
        String file = dir.resolve("rocoto-users.yaml").toString();
        assertTrue(run("a long enough passphrase\n", "user", "add", "ada", "--roles", "rocoto-admin,rocoto-reader",
                "--file", file, "--password-stdin").startsWith("0"));
        String tokenOutput = run("", "token", "create", "prometheus", "--roles", "rocoto-reader", "--days", "30",
                "--mbeans", "java.lang:*;Catalina:*", "--file", file);
        assertTrue(tokenOutput.startsWith("0"), tokenOutput);
        Matcher m = Pattern.compile("(rct_prometheus_\\S+)").matcher(tokenOutput);
        assertTrue(m.find());

        UserStore store = new UserStore(Path.of(file));
        assertTrue(PasswordHasher.verify("a long enough passphrase".toCharArray(), store.user("ada").passwordHash()));
        assertEquals(2, store.user("ada").roles().size());
        UserStore.Token token = store.token("prometheus");
        assertTrue(ApiTokens.verify(ApiTokens.parse(m.group(1)).secret(), token.hash()));
        assertEquals(2, token.mbeans().size());
        assertNotNull(token.expires());

        String content = Files.readString(Path.of(file));
        assertFalse(content.contains("a long enough passphrase"));
        assertFalse(content.contains(ApiTokens.parse(m.group(1)).secret()));

        assertTrue(run("", "token", "revoke", "prometheus", "--file", file).startsWith("0"));
        assertTrue(run("", "user", "remove", "ada", "--file", file).startsWith("0"));
        UserStore reloaded = new UserStore(Path.of(file));
        assertNull(reloaded.user("ada"));
        assertNull(reloaded.token("prometheus"));
    }

    @Test
    void weakPasswordsAndMissingRolesAreRejected(@TempDir Path dir) {
        String file = dir.resolve("u.yaml").toString();
        assertTrue(run("short\n", "user", "add", "bob", "--roles", "rocoto-reader", "--file", file,
                "--password-stdin").startsWith("1"));
        assertTrue(run("a long enough passphrase\n", "user", "add", "bob", "--file", file, "--password-stdin")
                .startsWith("1"));
    }

    @Test
    void convertsJolokiaPolicy(@TempDir Path dir) throws Exception {
        Path xml = dir.resolve("jolokia-access.xml");
        Files.writeString(xml, """
                <restrict>
                  <remote><host>127.0.0.1</host></remote>
                  <commands><command>read</command><command>exec</command></commands>
                  <allow>
                    <mbean>
                      <name>java.lang:type=Memory</name>
                      <attribute mode="read">HeapMemoryUsage</attribute>
                      <attribute>Verbose</attribute>
                      <operation>gc</operation>
                    </mbean>
                  </allow>
                  <deny>
                    <mbean>
                      <name>com.example:type=Secrets,*</name>
                      <attribute>*</attribute>
                    </mbean>
                  </deny>
                </restrict>
                """);
        String output = run("", "policy", "convert", xml.toString());
        assertTrue(output.startsWith("0"), output);
        String yaml = output.substring(2);
        // the result must be a policy Rocoto accepts
        AccessPolicy.fromConfig(ConfigLoader.parse(yaml).sub("policy"));
        assertTrue(yaml.contains("allow: [exec]"));
        assertFalse(yaml.contains("allow: [write]"), "write was not among the Jolokia commands");
        assertTrue(yaml.contains("deny: [read, write]"));
        assertTrue(yaml.contains("<remote>"));
    }

    @Test
    void policyConversionRefusesDoctypes(@TempDir Path dir) throws Exception {
        Path xml = dir.resolve("evil.xml");
        Files.writeString(xml, "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><restrict>&x;</restrict>");
        assertTrue(run("", "policy", "convert", xml.toString()).startsWith("1"));
    }
}
