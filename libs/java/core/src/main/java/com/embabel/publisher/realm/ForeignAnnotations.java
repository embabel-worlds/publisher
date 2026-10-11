package com.embabel.publisher.realm;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Annotations the core reads but does not depend on: Jackson's descriptions and Bean Validation's
 * constraints, matched by name. The core then has no Jackson or validation dependency, and an
 * application that has neither simply has no descriptions or required inputs.
 */
final class ForeignAnnotations {

    private static final String CLASS_DESCRIPTION = "com.fasterxml.jackson.annotation.JsonClassDescription";
    private static final String PROPERTY_DESCRIPTION = "com.fasterxml.jackson.annotation.JsonPropertyDescription";
    private static final Set<String> REQUIRED = Set.of("NotNull", "NotBlank", "NotEmpty");

    private ForeignAnnotations() {
    }

    static String classDescription(Class<?> type) {
        return value(type, CLASS_DESCRIPTION);
    }

    /**
     * Jackson's {@code @JsonPropertyDescription} cannot target a record component, so on a record it
     * lands on the field, the accessor and the constructor parameter. Any of them will do.
     */
    static String propertyDescription(RecordComponent component) {
        for (AnnotatedElement element : places(component)) {
            String description = value(element, PROPERTY_DESCRIPTION);
            if (description != null) {
                return description;
            }
        }
        return null;
    }

    /** Whether a verb input component is required: a primitive, or {@code @NotNull}, {@code @NotBlank} or {@code @NotEmpty}. */
    static boolean required(RecordComponent component) {
        if (component.getType().isPrimitive()) {
            return true;
        }
        List<Annotation> annotations = new ArrayList<>(List.of(component.getAnnotatedType().getAnnotations()));
        for (AnnotatedElement element : places(component)) {
            annotations.addAll(List.of(element.getAnnotations()));
        }
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> type = annotation.annotationType();
            String pkg = type.getPackageName();
            if (REQUIRED.contains(type.getSimpleName())
                    && (pkg.equals("jakarta.validation.constraints") || pkg.equals("javax.validation.constraints"))) {
                return true;
            }
        }
        return false;
    }

    private static List<AnnotatedElement> places(RecordComponent component) {
        List<AnnotatedElement> places = new ArrayList<>();
        places.add(component);
        try {
            Field field = component.getDeclaringRecord().getDeclaredField(component.getName());
            places.add(field);
        }
        catch (NoSuchFieldException ignored) {
            /* A record always has the field; this is the compiler's business, not ours. */
        }
        Method accessor = component.getAccessor();
        if (accessor != null) {
            places.add(accessor);
        }
        return places;
    }

    private static String value(AnnotatedElement element, String annotationName) {
        for (Annotation annotation : element.getAnnotations()) {
            if (annotation.annotationType().getName().equals(annotationName)) {
                try {
                    Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                    return value instanceof String s && !s.isBlank() ? s : null;
                }
                catch (ReflectiveOperationException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
