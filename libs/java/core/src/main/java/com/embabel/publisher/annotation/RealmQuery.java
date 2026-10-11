package com.embabel.publisher.annotation;

import com.embabel.publisher.where.Op;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lists a type without keys (protocol §4.3).
 * <p>
 * The method takes what the application's own search already takes: a <strong>criteria
 * record</strong>, whose components declare exactly which filters a world may push down (see
 * {@link Criterion}), with a {@code Pageable} returning {@code Page<T>} or {@code Slice<T>} for
 * cursor paging, or a {@code Sort} and a {@code Limit} returning a collection for one page. A JPA
 * {@code Specification} is the last resort, for a developer who wants the whole filter tree and
 * accepts a repository that extends {@code JpaSpecificationExecutor}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RealmQuery {

    /** The most records one page may hold. At most 1000. */
    int maxPageSize() default 100;

    /**
     * One component of a criteria record: the filter it carries. Without this annotation a
     * component is the view property of the same name, compared with {@code eq}; a
     * {@code Collection} component with {@code in}; a Spring Data {@code Range} with
     * {@code between}. The annotation names the property, the operator, or both:
     * {@code @RealmQuery.Criterion(property = "lastName", op = Op.PREFIX) String lastNamePrefix}.
     * <p>
     * A component the world did not push a value for is {@code null}, so the method treats it as
     * "no filter", as the application's own search form does.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
    @interface Criterion {

        /** The view property filtered. Defaults to the component's name. */
        String property() default "";

        /** The operator. Defaults by the component's type: {@code eq}, {@code in} or {@code between}. */
        Op[] op() default {};
    }
}
