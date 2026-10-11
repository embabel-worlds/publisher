package com.embabel.publisher.value;

/** A value that is not of the type it was declared as. */
public class InvalidValueException extends RuntimeException {

    public InvalidValueException(String message) {
        super(message);
    }
}
