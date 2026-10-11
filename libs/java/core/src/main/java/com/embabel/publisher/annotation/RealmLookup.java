package com.embabel.publisher.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A batched lookup (protocol §4.2): the records whose property {@link #by()} is one of the given keys.
 * <p>
 * The method takes the keys as a {@code Collection<K>}, where {@code K} is the property's Java type,
 * and returns a collection of the view records. It may also take a JPA {@code Specification} of the
 * type's entity, which receives the world's pushed-down filter, and a Spring Data {@code Sort}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RealmLookup {

    /** The property looked up by. */
    String by();

    /** The most keys one call may carry. The world splits larger batches. At most 1000. */
    int maxKeys() default 100;
}
