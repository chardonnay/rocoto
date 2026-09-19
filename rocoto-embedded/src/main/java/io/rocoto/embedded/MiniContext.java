package io.rocoto.embedded;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The supported part of {@code ServletContext}, {@code ServletConfig} and {@code FilterConfig}. */
final class MiniContext {

    private static final Logger LOG = Logger.getLogger("io.rocoto.embedded");

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile String contextPath;
    private final ClassLoader classLoader;
    jakarta.servlet.ServletContext proxy;

    MiniContext(String contextPath, ClassLoader classLoader) {
        this.contextPath = contextPath;
        this.classLoader = classLoader;
    }

    void contextPath(String path) {
        this.contextPath = path;
    }

    // ---- ServletContext ----------------------------------------------------------------------

    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    public void setAttribute(String name, Object value) {
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public String getContextPath() {
        return contextPath;
    }

    public String getServletContextName() {
        return "Rocoto";
    }

    public String getServerInfo() {
        return "rocoto-embedded";
    }

    public int getMajorVersion() {
        return 6;
    }

    public int getMinorVersion() {
        return 0;
    }

    public String getInitParameter(String name) {
        return null;
    }

    public Enumeration<String> getInitParameterNames() {
        return Collections.emptyEnumeration();
    }

    public void log(String message) {
        LOG.info(message);
    }

    public void log(String message, Throwable throwable) {
        LOG.log(Level.WARNING, message, throwable);
    }

    /** {@code ServletConfig} / {@code FilterConfig} of one component. */
    static final class ComponentConfig {

        private final String name;
        private final MiniContext context;

        ComponentConfig(String name, MiniContext context) {
            this.name = name;
            this.context = context;
        }

        public String getServletName() {
            return name;
        }

        public String getFilterName() {
            return name;
        }

        public jakarta.servlet.ServletContext getServletContext() {
            return context.proxy;
        }

        public String getInitParameter(String parameter) {
            return null;
        }

        public Enumeration<String> getInitParameterNames() {
            return Collections.emptyEnumeration();
        }
    }
}
