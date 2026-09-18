package io.rocoto.core.jmx;

import java.util.Set;

import javax.management.MBeanInfo;
import javax.management.ObjectName;

/**
 * The only door to JMX. Implementations talk to in-process MBeanServers; Rocoto has no remote
 * (JSR-160) connector and never connects to a target chosen by a client.
 *
 * <p>This interface performs no authorization. It must only be used behind the request pipeline.
 */
public interface MBeanAccess {

    /** @param pattern an ObjectName or pattern; {@code null} matches everything */
    Set<ObjectName> query(ObjectName pattern);

    boolean isRegistered(ObjectName name);

    MBeanInfo info(ObjectName name);

    Object getAttribute(ObjectName name, String attribute);

    void setAttribute(ObjectName name, String attribute, Object value);

    Object invoke(ObjectName name, String operation, Object[] args, String[] signature);
}
