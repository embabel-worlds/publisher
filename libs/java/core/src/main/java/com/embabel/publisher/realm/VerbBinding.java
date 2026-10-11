package com.embabel.publisher.realm;

import com.embabel.publisher.annotation.RealmVerb;

/**
 * A verb (§5) and its implementation.
 *
 * @param inputType  the record bound from the call's body, or {@code null} for a verb without input
 * @param outputType the record returned, or {@code null} for none
 */
public record VerbBinding(
        String name,
        String description,
        Class<?> subject,
        RealmVerb.Effect effect,
        boolean idempotent,
        RealmVerb.Required approval,
        String approvalReason,
        Class<?> inputType,
        Class<?> outputType,
        Invoker invoker,
        String source) {

    @FunctionalInterface
    public interface Invoker {
        Object invoke(Object input);
    }
}
