package io.rocoto.adapters;

import java.util.Set;

import javax.management.ObjectName;

/**
 * How adapters read JMX. The implementation is backed by the request pipeline, so the normalized
 * resource view obeys exactly the same policy, masking and audit rules as a direct read.
 */
public interface ResourceReader {

    Set<ObjectName> search(String pattern);

    /** @return the attribute value, or {@code null} if it does not exist, fails or is not permitted */
    Object read(ObjectName name, String attribute);

    /** The first non-null value among several spellings of an attribute (server versions differ). */
    default Object readFirst(ObjectName name, String... attributes) {
        for (String a : attributes) {
            Object v = read(name, a);
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
