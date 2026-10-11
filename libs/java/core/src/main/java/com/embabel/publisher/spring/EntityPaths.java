package com.embabel.publisher.spring;

import com.embabel.publisher.realm.PropertyModel;
import com.embabel.publisher.realm.RealmDefinitionException;
import com.embabel.publisher.realm.TypeModel;
import com.embabel.publisher.value.Primitives;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;

/**
 * Checks that a view property's {@code @RealmType.Path} resolves on the type's entity, to an
 * attribute of the same Java type, through single-valued associations only. A to-many step would
 * multiply rows, so a filter through one could not be exact.
 */
final class EntityPaths {

    private EntityPaths() {
    }

    static void requireResolvable(TypeModel type, PropertyModel property, String why) {
        String problem = problem(type, property);
        if (problem != null) {
            throw new RealmDefinitionException(type.name() + "." + property.name() + " is " + why + ", but " + problem);
        }
    }

    static boolean resolves(TypeModel type, PropertyModel property) {
        return problem(type, property) == null;
    }

    private static String problem(TypeModel type, PropertyModel property) {
        if (type.entity() == null) {
            return type.name() + " names no entity in @RealmType(entity = ...)";
        }
        Class<?> current = type.entity();
        String[] segments = property.entityPath().split("\\.");
        for (int i = 0; i < segments.length; i++) {
            Field field = field(current, segments[i]);
            if (field == null) {
                return "its path " + property.entityPath() + " does not resolve on " + type.entity().getSimpleName()
                        + " (" + current.getSimpleName() + " has no " + segments[i] + "); use @RealmType.Path";
            }
            Class<?> next = field.getType();
            if (Collection.class.isAssignableFrom(next) || Map.class.isAssignableFrom(next)) {
                return "its path " + property.entityPath() + " goes through the collection " + segments[i];
            }
            current = next;
        }
        if (Primitives.box(current) != Primitives.box(property.javaType())) {
            return "its path " + property.entityPath() + " is a " + current.getSimpleName() + " on the entity, not a " + property.javaType().getSimpleName();
        }
        return null;
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            }
            catch (NoSuchFieldException e) {
                /* Try the superclass: JPA attributes are often inherited from a mapped superclass. */
            }
        }
        return null;
    }
}
