package com.embabel.publisher.value;

import java.util.Map;

/** Boxing, so a component declared {@code int} and a key collection of {@code Integer} agree. */
public final class Primitives {

    private static final Map<Class<?>, Class<?>> BOXES = Map.of(
            int.class, Integer.class, long.class, Long.class, short.class, Short.class, byte.class, Byte.class,
            boolean.class, Boolean.class, char.class, Character.class, double.class, Double.class, float.class, Float.class);

    private Primitives() {
    }

    public static Class<?> box(Class<?> type) {
        return type.isPrimitive() ? BOXES.get(type) : type;
    }
}
