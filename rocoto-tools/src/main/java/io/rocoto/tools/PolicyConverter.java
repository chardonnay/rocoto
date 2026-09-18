package io.rocoto.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Converts a Jolokia {@code jolokia-access.xml} into the {@code policy} section of rocoto.yaml.
 * The two models differ (Jolokia has no users or roles), so the output is a reviewed starting
 * point: everything Jolokia allowed to anyone is granted to {@code rocoto-admin} only.
 */
final class PolicyConverter {

    private PolicyConverter() {
    }

    static String convert(Path file) throws IOException {
        Document doc = parse(file);
        List<String> notes = new ArrayList<>();
        StringBuilder rules = new StringBuilder();

        List<String> commands = new ArrayList<>();
        for (Element c : elements(doc.getDocumentElement(), "commands")) {
            for (Element cmd : elements(c, "command")) {
                commands.add(cmd.getTextContent().trim().toLowerCase(Locale.ROOT));
            }
        }
        boolean writeGloballyAllowed = commands.isEmpty() || commands.contains("write");
        boolean execGloballyAllowed = commands.isEmpty() || commands.contains("exec");
        if (writeGloballyAllowed || execGloballyAllowed) {
            notes.add("Jolokia allowed " + (writeGloballyAllowed ? "write " : "") + (execGloballyAllowed ? "exec " : "")
                    + "on every MBean that is not denied. Rocoto has no such blanket permission: only the "
                    + "MBeans listed in <allow> were converted. Add allow rules for anything else you really need.");
        }
        if (!elements(doc.getDocumentElement(), "remote").isEmpty()) {
            notes.add("<remote> host restrictions are not part of Rocoto's policy. Restrict network access in the "
                    + "application server or firewall; Rocoto authenticates every caller instead.");
        }
        if (!elements(doc.getDocumentElement(), "cors").isEmpty()) {
            notes.add("<cors> was not converted. List trusted browser origins under cors.allowedOrigins "
                    + "(wildcards are not supported).");
        }
        if (!elements(doc.getDocumentElement(), "http").isEmpty()) {
            notes.add("<http> method restrictions have no equivalent and are not needed.");
        }

        for (Element allow : elements(doc.getDocumentElement(), "allow")) {
            for (Element mbean : elements(allow, "mbean")) {
                String name = text(mbean, "name");
                List<String> writable = new ArrayList<>();
                for (Element a : elements(mbean, "attribute")) {
                    if (!"read".equalsIgnoreCase(a.getAttribute("mode"))) {
                        writable.add(a.getTextContent().trim());
                    }
                }
                List<String> operations = new ArrayList<>();
                elements(mbean, "operation").forEach(o -> operations.add(o.getTextContent().trim()));
                if (!writable.isEmpty() && writeGloballyAllowed) {
                    rule(rules, "allow", "write", name, "attributes", writable, true);
                }
                if (!operations.isEmpty() && execGloballyAllowed) {
                    rule(rules, "allow", "exec", name, "operations", operations, true);
                }
            }
        }
        for (Element deny : elements(doc.getDocumentElement(), "deny")) {
            for (Element mbean : elements(deny, "mbean")) {
                String name = text(mbean, "name");
                List<String> attributes = new ArrayList<>();
                elements(mbean, "attribute").forEach(a -> attributes.add(a.getTextContent().trim()));
                List<String> operations = new ArrayList<>();
                elements(mbean, "operation").forEach(o -> operations.add(o.getTextContent().trim()));
                if (!attributes.isEmpty()) {
                    rule(rules, "deny", "read, write", name, "attributes", attributes, false);
                }
                if (!operations.isEmpty()) {
                    rule(rules, "deny", "exec", name, "operations", operations, false);
                }
                if (attributes.isEmpty() && operations.isEmpty()) {
                    rule(rules, "deny", "all", name, null, List.of(), false);
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("# Converted from ").append(file.getFileName()).append(" - REVIEW BEFORE USE.\n");
        out.append("# Rocoto is read-only by default and its built-in deny list always applies.\n");
        for (String note : notes) {
            out.append("# NOTE: ").append(note).append('\n');
        }
        out.append("policy:\n");
        out.append(rules.length() == 0 ? "  rules: []\n" : "  rules:\n" + rules);
        return out.toString();
    }

    private static void rule(StringBuilder out, String effect, String operations, String mbean, String memberKey,
            List<String> members, boolean withRoles) {
        out.append("    - ");
        if (withRoles) {
            out.append("roles: [rocoto-admin]\n      ");
        }
        out.append(effect).append(": [").append(operations).append("]\n");
        out.append("      mbean: ").append(quote(mbean)).append('\n');
        if (memberKey != null && !members.isEmpty()) {
            out.append("      ").append(memberKey).append(": [");
            for (int i = 0; i < members.size(); i++) {
                out.append(i == 0 ? "" : ", ").append(quote(members.get(i)));
            }
            out.append("]\n");
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String text(Element parent, String tag) {
        List<Element> found = elements(parent, tag);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("<mbean> without <" + tag + ">");
        }
        return found.get(0).getTextContent().trim();
    }

    private static List<Element> elements(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                out.add(e);
            }
        }
        return out;
    }

    /** XML from an untrusted file: no DTDs, no external entities. */
    private static Document parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(in);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalArgumentException("cannot parse " + file + ": " + e.getMessage());
        }
    }
}
