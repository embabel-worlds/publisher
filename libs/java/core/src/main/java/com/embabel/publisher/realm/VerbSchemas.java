package com.embabel.publisher.realm;

import com.embabel.publisher.protocol.RealmException;
import com.embabel.publisher.value.InvalidValueException;
import com.embabel.publisher.value.ValueType;
import com.embabel.publisher.value.Values;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A verb's input and output, as JSON Schema 2020-12 generated from its records, and the binding of
 * a call's body onto the input record. The schema is what the world validates against before it
 * calls, and binding checks the same rules again, because the world's check is not the publisher's.
 */
public final class VerbSchemas {

    private VerbSchemas() {
    }

    public static void check(Class<?> type, String where) {
        if (!type.isRecord()) {
            throw new RealmDefinitionException(where + ": a verb's input and output are records, not " + type.getName());
        }
        for (RecordComponent component : type.getRecordComponents()) {
            valueType(component, where);
        }
    }

    public static Map<String, Object> schema(Class<?> record, boolean withRequired) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        List<String> required = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        for (RecordComponent component : record.getRecordComponents()) {
            Map<String, Object> property = propertySchema(component);
            String description = ForeignAnnotations.propertyDescription(component);
            if (description != null) {
                property.put("description", description);
            }
            properties.put(component.getName(), property);
            if (ForeignAnnotations.required(component)) {
                required.add(component.getName());
            }
        }
        if (withRequired && !required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("properties", properties);
        return schema;
    }

    /** Binds a call's body onto the input record, or refuses it with {@code INVALID_REQUEST}. */
    public static Object bind(Object body, Class<?> record) {
        if (!(body instanceof Map<?, ?> map)) {
            throw RealmException.invalid("the input is a JSON object");
        }
        RecordComponent[] components = record.getRecordComponents();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            Object wire = map.get(component.getName());
            if (wire == null) {
                if (ForeignAnnotations.required(component)) {
                    throw RealmException.invalid(component.getName() + " is required");
                }
                continue;
            }
            try {
                args[i] = value(wire, component);
            }
            catch (InvalidValueException e) {
                throw RealmException.invalid(component.getName() + ": " + e.getMessage());
            }
        }
        try {
            Constructor<?> constructor = record.getDeclaredConstructor(
                    Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        }
        catch (InvocationTargetException e) {
            /* A compact constructor's own validation is the application refusing the input. */
            if (e.getCause() instanceof IllegalArgumentException refusal) {
                throw RealmException.invalid(refusal.getMessage());
            }
            throw new IllegalStateException("Constructing " + record.getName() + " failed", e.getCause());
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object value(Object wire, RecordComponent component) {
        if (Collection.class.isAssignableFrom(component.getType())) {
            if (!(wire instanceof List<?> items)) {
                throw new InvalidValueException("not an array");
            }
            Class<?> element = (Class<?>) ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
            Collection<Object> out = Set.class.isAssignableFrom(component.getType()) ? new LinkedHashSet<>() : new ArrayList<>();
            for (Object item : items) {
                out.add(Values.fromWire(item, element));
            }
            return Set.class.isAssignableFrom(component.getType()) ? Set.copyOf(out) : List.copyOf(out);
        }
        return Values.fromWire(wire, component.getType());
    }

    private static Map<String, Object> propertySchema(RecordComponent component) {
        if (Collection.class.isAssignableFrom(component.getType())) {
            Class<?> element = (Class<?>) ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
            Map<String, Object> array = new LinkedHashMap<>();
            array.put("type", "array");
            array.put("items", scalarSchema(element));
            return array;
        }
        return scalarSchema(component.getType());
    }

    private static Map<String, Object> scalarSchema(Class<?> javaType) {
        Map<String, Object> schema = new LinkedHashMap<>();
        switch (ValueType.ofScalar(javaType)) {
            case STRING -> schema.put("type", "string");
            case BOOLEAN -> schema.put("type", "boolean");
            case INTEGER -> schema.put("type", "integer");
            case DECIMAL -> {
                schema.put("type", "string");
                schema.put("format", "decimal");
            }
            case DATE -> {
                schema.put("type", "string");
                schema.put("format", "date");
            }
            case DATETIME -> {
                schema.put("type", "string");
                schema.put("format", "date-time");
            }
            case DURATION -> {
                schema.put("type", "string");
                schema.put("format", "duration");
            }
            case ENUM -> {
                schema.put("type", "string");
                schema.put("enum", Arrays.stream(javaType.getEnumConstants()).map(e -> ((Enum<?>) e).name()).toList());
            }
            case LIST -> throw new IllegalStateException("nested list");
        }
        return schema;
    }

    private static void valueType(RecordComponent component, String where) {
        Class<?> type = component.getType();
        if (Collection.class.isAssignableFrom(type)) {
            if (!(component.getGenericType() instanceof ParameterizedType p) || !(p.getActualTypeArguments()[0] instanceof Class<?> element)
                    || ValueType.ofScalar(element) == null) {
                throw new RealmDefinitionException(where + ": " + component.getName() + " must be a collection of scalars");
            }
            return;
        }
        if (ValueType.ofScalar(type) == null) {
            throw new RealmDefinitionException(where + ": no protocol type for " + component.getName() + " (" + type.getName() + ")");
        }
    }
}
