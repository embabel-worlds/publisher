package com.embabel.publisher.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An operation the world may invoke (protocol §5). The method takes at most one parameter, a record
 * whose components are the verb's input, and returns a record (its output) or nothing.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RealmVerb {

    /** The verb's name. Defaults to the method's name. */
    String name() default "";

    /** What the verb does, written for the world's owner and for models planning a call. */
    String description();

    /** The view record of the type the verb acts on, if any. */
    Class<?> subject() default void.class;

    /**
     * There is no default: whether an operation changes data, or does something outside the
     * application that cannot be taken back, is a decision to make, not to inherit.
     */
    Effect effect();

    boolean idempotent() default false;

    Approval approval() default @Approval;

    /** Protocol §5.2. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Approval {
        Required required() default Required.NEVER;

        String reason() default "";
    }

    enum Effect {
        READ, WRITE, EXTERNAL
    }

    enum Required {
        NEVER, ALWAYS, WHEN, DECIDED
    }
}
