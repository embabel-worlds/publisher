package com.embabel.publisher.realm;

import com.embabel.publisher.annotation.RealmType;
import com.embabel.publisher.value.ValueType;
import com.embabel.publisher.where.Op;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * One property of a realm type: a component of the view record.
 *
 * @param entityPath  where the property lives on the type's entity: {@code @RealmType.Path}, or its own name
 * @param filterOps   the operators declared with {@code @RealmType.Filterable}; empty means every
 *                    operator the library evaluates exactly for the type; {@code null} means not filterable
 */
public record PropertyModel(
        String name,
        Class<?> javaType,
        ValueType type,
        ValueType itemType,
        List<String> enumValues,
        String description,
        String spine,
        ReferenceModel reference,
        String entityPath,
        Set<Op> filterOps,
        boolean sortable,
        Method accessor) {

    public record ReferenceModel(Class<?> target, String relationship, RealmType.Direction direction) {
    }

    public boolean filterable() {
        return filterOps != null;
    }
}
