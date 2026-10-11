package com.embabel.publisher.realm;

import com.embabel.publisher.annotation.RealmType;
import com.embabel.publisher.value.ValueType;

import java.util.Map;

/** A realm type, read from its view record by {@link TypeIntrospector}. */
public record TypeModel(
        String name,
        Class<?> view,
        Class<?> entity,
        String description,
        String identity,
        Map<String, PropertyModel> properties,
        RealmType.Cache cache) {

    public PropertyModel property(String name) {
        return properties.get(name);
    }

    public PropertyModel identityProperty() {
        return properties.get(identity);
    }

    public ValueType typeOf(String property) {
        PropertyModel p = properties.get(property);
        return p == null ? null : p.type();
    }

    public ValueType itemTypeOf(String property) {
        PropertyModel p = properties.get(property);
        return p == null ? null : p.itemType();
    }
}
