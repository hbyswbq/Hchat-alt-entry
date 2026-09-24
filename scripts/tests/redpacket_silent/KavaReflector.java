package h.Hchat.utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

// JVM reflection supplies the host boundary; production parsing and selection stay intact.
public final class KavaReflector {
    public static Method[] declaredMethods(Class<?> type) { return type.getDeclaredMethods(); }
    public static Field[] declaredFields(Class<?> type) { return type.getDeclaredFields(); }
    public static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        try { return type.getMethod(name, parameters); }
        catch (ReflectiveOperationException e) { throw new IllegalArgumentException(e); }
    }
    public static Object invoke(Method method, Object target, Object... args) {
        try { method.setAccessible(true); return method.invoke(target, args); }
        catch (ReflectiveOperationException e) { throw new IllegalArgumentException(e); }
    }
    public static Object readField(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {}
        }
        return null;
    }
    public static Object newInstance(Constructor<?> constructor, Object... args) {
        try { constructor.setAccessible(true); return constructor.newInstance(args); }
        catch (ReflectiveOperationException e) { throw new IllegalArgumentException(e); }
    }
    public static Object newInstanceByArgs(Class<?> type, Object... args) {
        if (type == null) return null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != args.length) continue;
            try { return newInstance(constructor, args); }
            catch (IllegalArgumentException ignored) {}
        }
        return null;
    }
}
