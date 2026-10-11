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

    /**
     * Whether the attribute at the property's path can be null. It cannot when every step is a
     * primitive, an {@code @Id}, {@code @Column(nullable = false)}, a required association
     * ({@code optional = false}, {@code @JoinColumn(nullable = false)}), or Bean Validation's
     * {@code @NotNull}, {@code @NotBlank} or {@code @NotEmpty}: the application's own claim that no
     * row lacks it. Annotations are matched by name, so the core needs neither API.
     */
    static boolean nullable(TypeModel type, PropertyModel property) {
        if (type.entity() == null) {
            return true;
        }
        Class<?> current = type.entity();
        for (String segment : property.entityPath().split("\\.")) {
            Field field = field(current, segment);
            if (field == null) {
                return true;
            }
            if (!field.getType().isPrimitive() && !required(field)) {
                return true;
            }
            current = field.getType();
        }
        return false;
    }

    private static boolean required(Field field) {
        for (java.lang.annotation.Annotation annotation : field.getAnnotations()) {
            String name = annotation.annotationType().getName();
            switch (name) {
                case "jakarta.persistence.Id", "jakarta.persistence.EmbeddedId",
                     "jakarta.validation.constraints.NotNull", "jakarta.validation.constraints.NotBlank",
                     "jakarta.validation.constraints.NotEmpty", "javax.validation.constraints.NotNull",
                     "javax.validation.constraints.NotBlank", "javax.validation.constraints.NotEmpty" -> {
                    return true;
                }
                case "jakarta.persistence.Column", "jakarta.persistence.JoinColumn" -> {
                    if (Boolean.FALSE.equals(attribute(annotation, "nullable"))) {
                        return true;
                    }
                }
                case "jakarta.persistence.ManyToOne", "jakarta.persistence.OneToOne" -> {
                    if (Boolean.FALSE.equals(attribute(annotation, "optional"))) {
                        return true;
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }

    private static Object attribute(java.lang.annotation.Annotation annotation, String name) {
        try {
            return annotation.annotationType().getMethod(name).invoke(annotation);
        }
        catch (ReflectiveOperationException e) {
            return null;
        }
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
