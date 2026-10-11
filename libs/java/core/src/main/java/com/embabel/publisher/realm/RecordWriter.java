package com.embabel.publisher.realm;

import com.embabel.publisher.value.Values;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes records in their wire form (§2.1): decimals as plain strings, integers as numbers while
 * they fit in ±2^53, dates and datetimes as strings. Only the declared components are written; the
 * core never asks a JSON library how to render a value, because a library's defaults (a decimal as
 * a JSON number, say) are exactly what breaks a join.
 */
public final class RecordWriter {

    private RecordWriter() {
    }

    public static Map<String, Object> write(Object view, TypeModel type) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (PropertyModel property : type.properties().values()) {
            record.put(property.name(), Values.toWire(read(view, property.accessor())));
        }
        return record;
    }

    /** Any record, such as a verb's output, component by component. */
    public static Map<String, Object> writeRecord(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            out.put(component.getName(), Values.toWire(read(value, component.getAccessor())));
        }
        return out;
    }

    private static Object read(Object target, java.lang.reflect.Method accessor) {
        try {
            accessor.setAccessible(true);
            return accessor.invoke(target);
        }
        catch (InvocationTargetException e) {
            throw new IllegalStateException("Reading " + accessor.getName() + " failed", e.getCause());
        }
        catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
