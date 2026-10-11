package com.embabel.publisher.realm;

import com.embabel.publisher.annotation.RealmType;
import com.embabel.publisher.value.ValueType;
import com.embabel.publisher.where.Op;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads a {@code @RealmType} view record into a {@link TypeModel}. Framework-free. */
public final class TypeIntrospector {

    private TypeIntrospector() {
    }

    public static boolean isRealmType(Class<?> type) {
        return type != null && type.isAnnotationPresent(RealmType.class);
    }

    public static String typeName(Class<?> view) {
        RealmType annotation = view.getAnnotation(RealmType.class);
        if (annotation == null) {
            throw new RealmDefinitionException(view.getName() + " is not a @RealmType");
        }
        return annotation.name().isEmpty() ? view.getSimpleName() : annotation.name();
    }

    public static TypeModel introspect(Class<?> view) {
        RealmType annotation = view.getAnnotation(RealmType.class);
        if (annotation == null) {
            throw new RealmDefinitionException(view.getName() + " is not annotated @RealmType");
        }
        if (!view.isRecord()) {
            throw new RealmDefinitionException("@RealmType " + view.getName() + " must be a record: its components are what the world sees");
        }
        String name = typeName(view);
        Map<String, PropertyModel> properties = new LinkedHashMap<>();
        String identity = null;
        for (RecordComponent component : view.getRecordComponents()) {
            PropertyModel property = property(name, component);
            properties.put(property.name(), property);
            if (component.isAnnotationPresent(RealmType.Id.class)) {
                if (identity != null) {
                    throw new RealmDefinitionException(name + " has two @RealmType.Id components: " + identity + " and " + property.name());
                }
                identity = property.name();
            }
        }
        if (identity == null) {
            throw new RealmDefinitionException(name + " has no @RealmType.Id component");
        }
        if (!properties.get(identity).type().isScalar()) {
            throw new RealmDefinitionException(name + "'s identity must be a scalar");
        }
        Class<?> entity = annotation.entity() == void.class ? null : annotation.entity();
        return new TypeModel(name, view, entity, ForeignAnnotations.classDescription(view), identity, properties, annotation.cache());
    }

    private static PropertyModel property(String typeName, RecordComponent component) {
        String where = typeName + "." + component.getName();
        Class<?> javaType = component.getType();
        ValueType type = ValueType.ofScalar(javaType);
        ValueType itemType = null;
        if (type == null && Collection.class.isAssignableFrom(javaType)) {
            itemType = ValueType.ofScalar(elementClass(component.getGenericType(), where));
            if (itemType == null) {
                throw new RealmDefinitionException(where + ": a list of non-scalars is a value or a part, which this version of the library does not publish");
            }
            type = ValueType.LIST;
        }
        if (type == null) {
            throw new RealmDefinitionException(where + ": no protocol type for " + javaType.getName()
                    + " (the protocol has no floating point and no datetime without an offset; nested values and parts are not supported yet)");
        }
        List<String> enumValues = null;
        if (type == ValueType.ENUM) {
            enumValues = Arrays.stream(javaType.getEnumConstants()).map(e -> ((Enum<?>) e).name()).toList();
        }
        if (itemType == ValueType.ENUM) {
            throw new RealmDefinitionException(where + ": a list of enums is not supported yet; declare a list of strings");
        }

        RealmType.Spine spine = component.getAnnotation(RealmType.Spine.class);
        RealmType.Reference reference = component.getAnnotation(RealmType.Reference.class);
        RealmType.Path path = component.getAnnotation(RealmType.Path.class);
        RealmType.Filterable filterable = component.getAnnotation(RealmType.Filterable.class);

        Set<Op> filterOps = null;
        if (filterable != null) {
            filterOps = new LinkedHashSet<>(List.of(filterable.value()));
            for (Op op : filterOps) {
                if (!op.appliesTo(type)) {
                    throw new RealmDefinitionException(where + ": the protocol does not define " + op.wire() + " on a " + type.wire());
                }
            }
        }
        if (reference != null && !isRealmType(reference.type())) {
            throw new RealmDefinitionException(where + ": @RealmType.Reference names " + reference.type().getName() + ", which is not a @RealmType");
        }
        return new PropertyModel(
                component.getName(),
                javaType,
                type,
                itemType,
                enumValues,
                ForeignAnnotations.propertyDescription(component),
                spine == null ? null : spine.value(),
                reference == null ? null : new PropertyModel.ReferenceModel(reference.type(), reference.relationship(), reference.direction()),
                path == null ? component.getName() : path.value(),
                filterOps,
                component.isAnnotationPresent(RealmType.Sortable.class),
                component.getAccessor());
    }

    private static Class<?> elementClass(Type generic, String where) {
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        throw new RealmDefinitionException(where + ": declare the element type of the collection");
    }
}
