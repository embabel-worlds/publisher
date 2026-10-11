package com.embabel.publisher.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lists a type without keys (protocol §4.3). The method may take a JPA {@code Specification}, a
 * {@code Sort}, and either a {@code Pageable} (returning {@code Page<T>} or {@code Slice<T>}, which
 * gives cursor paging) or a {@code Limit} (returning a collection, one page only).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RealmQuery {

    /** The most records one page may hold. At most 1000. */
    int maxPageSize() default 100;
}
