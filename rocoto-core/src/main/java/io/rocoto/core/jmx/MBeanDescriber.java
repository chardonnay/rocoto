package io.rocoto.core.jmx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.openmbean.OpenType;

/** Renders {@link MBeanInfo} as a JSON-ready tree for the native API. */
public final class MBeanDescriber {

    private MBeanDescriber() {
    }

    public static Map<String, Object> describe(MBeanInfo info) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("className", info.getClassName());
        out.put("description", info.getDescription());

        Map<String, Object> attributes = new LinkedHashMap<>();
        for (MBeanAttributeInfo a : info.getAttributes()) {
            attributes.put(a.getName(), describe(a));
        }
        out.put("attributes", attributes);

        List<Object> operations = new ArrayList<>();
        for (MBeanOperationInfo op : info.getOperations()) {
            operations.add(describe(op));
        }
        out.put("operations", operations);

        List<Object> notifications = new ArrayList<>();
        for (MBeanNotificationInfo n : info.getNotifications()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n.getName());
            m.put("description", n.getDescription());
            m.put("types", List.of(n.getNotifTypes()));
            notifications.add(m);
        }
        out.put("notifications", notifications);
        return out;
    }

    public static Map<String, Object> describe(MBeanAttributeInfo a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", a.getType());
        OpenType<?> openType = MBeanMetadata.openType(a);
        if (openType != null) {
            m.put("openType", openType.getTypeName());
        }
        m.put("description", a.getDescription());
        m.put("readable", a.isReadable());
        m.put("writable", a.isWritable());
        return m;
    }

    public static Map<String, Object> describe(MBeanOperationInfo op) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", op.getName());
        m.put("description", op.getDescription());
        m.put("returnType", op.getReturnType());
        m.put("impact", impact(op.getImpact()));
        List<Object> params = new ArrayList<>();
        for (MBeanParameterInfo p : op.getSignature()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.getName());
            pm.put("type", p.getType());
            pm.put("description", p.getDescription());
            params.add(pm);
        }
        m.put("parameters", params);
        return m;
    }

    private static String impact(int impact) {
        return switch (impact) {
            case MBeanOperationInfo.INFO -> "INFO";
            case MBeanOperationInfo.ACTION -> "ACTION";
            case MBeanOperationInfo.ACTION_INFO -> "ACTION_INFO";
            default -> "UNKNOWN";
        };
    }
}
