package com.embabel.publisher.annotation;

import com.embabel.publisher.where.Op;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a record as a realm type: the view a service layer returns, never the entity behind it
 * (protocol §3.1). Its components are the type's properties; nothing else is exposed.
 * <p>
 * Descriptions come from Jackson's {@code @JsonClassDescription} and {@code @JsonPropertyDescription},
 * read by name, so a view described once is described the same way to the application's own
 * tools and to every world.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RealmType {

    /** The type's name in the manifest. Defaults to the record's simple name. */
    String name() default "";

    /**
     * The entity behind the view. Required for a type whose methods take a JPA
     * {@code Specification}, because filters and sorts are translated onto it.
     */
    Class<?> entity() default void.class;

    /** Cache policy (protocol §3.3). The default is the protocol's: per user, never reused. */
    Cache cache() default @Cache;

    /** The type's identity (protocol §3.1). Exactly one component carries it, and it must be a lookup. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
    @interface Id {
    }

    /** Binds the property to a spine, such as {@code Person} or {@code Organization} (protocol §6). */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
    @interface Spine {
        String value();
    }

    /** The property holds another realm type's identity (protocol §3.2). */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
    @interface Reference {
        /** The referenced view record, itself a {@code @RealmType}. */
        Class<?> type();

        String relationship();

        Direction direction() default Direction.OUT;
    }

    /** Where the property lives on {@link RealmType#entity()}, when it is not the same name. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
    @interface Path {
        String value();
    }

    /**
     * The world may filter on this property with these operators (protocol §4.1). With no operators,
     * every operator the publisher can evaluate exactly for the property's type.
     * <p>
     * Filtering is opt-in per property: what a world may push down is the application owner's
     * decision. The starter checks at startup that the type's methods can honour every operator
     * declared here, so the declaration cannot promise more than the code does.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
    @interface Filterable {
        Op[] value() default {};
    }

    /** The world may order by this property. Opt-in, like {@link Filterable}. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
    @interface Sortable {
    }

    /** Protocol §3.3. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Cache {
        Scope value() default Scope.USER;

        int ttlSeconds() default 0;

        int negativeTtlSeconds() default 0;

        boolean immutable() default false;
    }

    enum Direction {
        IN, OUT
    }

    enum Scope {
        /** One answer for every user of the world: authorization does not vary it. */
        SHARED,
        /** The answer depends on the acting user. The safe default. */
        USER
    }
}
