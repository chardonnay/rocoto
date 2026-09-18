package io.rocoto.core.jmx;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.management.Attribute;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.InvalidAttributeValueException;
import javax.management.JMException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeMBeanException;
import javax.management.RuntimeOperationsException;

import io.rocoto.core.jmx.MBeanAccessException.Kind;

/** {@link MBeanAccess} over one or more in-process MBeanServers, presented as one merged view. */
public final class MBeanServerAccess implements MBeanAccess {

    private final List<MBeanServerConnection> servers;

    public MBeanServerAccess(List<? extends MBeanServerConnection> servers) {
        if (servers.isEmpty()) {
            throw new IllegalArgumentException("At least one MBeanServer is required");
        }
        this.servers = List.copyOf(new LinkedHashSet<>(servers));
    }

    @Override
    public Set<ObjectName> query(ObjectName pattern) {
        Set<ObjectName> names = new TreeSet<>();
        for (MBeanServerConnection server : servers) {
            try {
                names.addAll(server.queryNames(pattern, null));
            } catch (IOException e) {
                throw new MBeanAccessException(Kind.FAILED, "MBeanServer not reachable", e);
            } catch (SecurityException e) {
                throw forbidden(e);
            }
        }
        return names;
    }

    @Override
    public boolean isRegistered(ObjectName name) {
        return find(name) != null;
    }

    @Override
    public MBeanInfo info(ObjectName name) {
        return call(name, server -> server.getMBeanInfo(name));
    }

    @Override
    public Object getAttribute(ObjectName name, String attribute) {
        return call(name, server -> server.getAttribute(name, attribute));
    }

    @Override
    public void setAttribute(ObjectName name, String attribute, Object value) {
        call(name, server -> {
            server.setAttribute(name, new Attribute(attribute, value));
            return null;
        });
    }

    @Override
    public Object invoke(ObjectName name, String operation, Object[] args, String[] signature) {
        return call(name, server -> server.invoke(name, operation, args, signature));
    }

    @Override
    public void addNotificationListener(ObjectName name, javax.management.NotificationListener listener) {
        call(name, server -> {
            server.addNotificationListener(name, listener, null, null);
            return null;
        });
    }

    @Override
    public void removeNotificationListener(ObjectName name, javax.management.NotificationListener listener) {
        try {
            call(name, server -> {
                server.removeNotificationListener(name, listener);
                return null;
            });
        } catch (MBeanAccessException e) {
            // MBean or listener already gone: nothing left to clean up.
        }
    }

    private MBeanServerConnection find(ObjectName name) {
        for (MBeanServerConnection server : servers) {
            try {
                if (server.isRegistered(name)) {
                    return server;
                }
            } catch (IOException e) {
                throw new MBeanAccessException(Kind.FAILED, "MBeanServer not reachable", e);
            }
        }
        return null;
    }

    private <T> T call(ObjectName name, JmxCall<T> call) {
        MBeanServerConnection server = find(name);
        if (server == null) {
            throw new MBeanAccessException(Kind.MBEAN_NOT_FOUND, "No MBean registered as " + name);
        }
        try {
            return call.apply(server);
        } catch (InstanceNotFoundException e) {
            throw new MBeanAccessException(Kind.MBEAN_NOT_FOUND, "No MBean registered as " + name, e);
        } catch (AttributeNotFoundException e) {
            throw new MBeanAccessException(Kind.ATTRIBUTE_NOT_FOUND, "No such attribute: " + e.getMessage(), e);
        } catch (InvalidAttributeValueException e) {
            throw new MBeanAccessException(Kind.INVALID_VALUE, "Invalid attribute value: " + e.getMessage(), e);
        } catch (SecurityException e) {
            throw forbidden(e);
        } catch (RuntimeMBeanException | RuntimeOperationsException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException se) {
                throw forbidden(se);
            }
            if (cause instanceof UnsupportedOperationException) {
                throw new MBeanAccessException(Kind.FAILED, "Not supported by the MBean", cause);
            }
            throw new MBeanAccessException(Kind.FAILED, describe(cause != null ? cause : e), e);
        } catch (MBeanException e) {
            throw new MBeanAccessException(Kind.FAILED, describe(e.getCause() != null ? e.getCause() : e), e);
        } catch (ReflectionException e) {
            if (e.getCause() instanceof NoSuchMethodException) {
                throw new MBeanAccessException(Kind.OPERATION_NOT_FOUND, "No such operation", e);
            }
            throw new MBeanAccessException(Kind.FAILED, describe(e), e);
        } catch (JMException | IOException e) {
            throw new MBeanAccessException(Kind.FAILED, describe(e), e);
        }
    }

    private static MBeanAccessException forbidden(SecurityException e) {
        return new MBeanAccessException(Kind.FORBIDDEN_BY_SERVER,
                "The application server denied access to this MBean", e);
    }

    private static String describe(Throwable t) {
        return t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
    }

    @FunctionalInterface
    private interface JmxCall<T> {
        T apply(MBeanServerConnection server) throws JMException, IOException;
    }
}
