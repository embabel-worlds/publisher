package com.embabel.publisher.realm;

/**
 * A realm declaration that cannot be served as declared. Thrown at startup, so a mistake surfaces
 * in the developer's build rather than at a world's install or its first query.
 */
public class RealmDefinitionException extends RuntimeException {

    public RealmDefinitionException(String message) {
        super(message);
    }
}
