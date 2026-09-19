package io.rocoto.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The servlet interfaces have some 200 methods, Rocoto uses about 40 of them. Instead of stubbing
 * the rest, the Mini* classes declare exactly the supported methods (same name and parameter
 * types) and a dynamic proxy routes the interface calls to them; anything else fails loudly.
 */
final class Proxies {

    private static final Map<Method, Method> TARGETS = new ConcurrentHashMap<>();

    private Proxies() {
    }

    static <T> T create(Class<T> type, Object target) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    default -> type.getSimpleName() + "@rocoto-embedded";
                };
            }
            Method implementation = TARGETS.computeIfAbsent(method, m -> find(target.getClass(), m));
            try {
                return implementation.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
        return type.cast(proxy);
    }

    private static Method find(Class<?> targetClass, Method method) {
        try {
            Method found = targetClass.getDeclaredMethod(method.getName(), method.getParameterTypes());
            found.setAccessible(true);
            return found;
        } catch (NoSuchMethodException e) {
            throw new UnsupportedOperationException(method.getDeclaringClass().getSimpleName() + "."
                    + method.getName() + " is not supported by the embedded Rocoto runtime");
        }
    }
}
