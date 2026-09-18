package io.rocoto.api;

import java.util.Set;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import io.rocoto.adapters.ResourceReader;
import io.rocoto.core.convert.ValueConverter;
import io.rocoto.core.jmx.MBeanAccessException;
import io.rocoto.security.AccessDeniedException;
import io.rocoto.security.CallContext;
import io.rocoto.security.RequestPipeline;

/** Lets server adapters read JMX through the request pipeline, as the current caller. */
public final class PipelineResourceReader implements ResourceReader {

    private final RequestPipeline pipeline;
    private final CallContext ctx;

    public PipelineResourceReader(RequestPipeline pipeline, CallContext ctx) {
        this.pipeline = pipeline;
        this.ctx = ctx;
    }

    @Override
    public Set<ObjectName> search(String pattern) {
        try {
            return pipeline.search(ctx, new ObjectName(pattern));
        } catch (MalformedObjectNameException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public Object read(ObjectName name, String attribute) {
        if (!pipeline.mayRead(ctx, name, attribute)) {
            // Expected for restricted callers: leave the field out without flooding the audit log.
            return null;
        }
        try {
            Object value = pipeline.read(ctx, name, attribute);
            return ValueConverter.MASK.equals(value) ? null : value;
        } catch (AccessDeniedException | MBeanAccessException e) {
            return null;
        }
    }
}
