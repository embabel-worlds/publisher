package com.embabel.publisher.protocol;

/** The protocol's error codes (§4.6), each with the HTTP status it travels with. */
public enum ErrorCode {
    UNAUTHENTICATED(401, "The world's credential was missing or wrong"),
    FORBIDDEN(403, "Forbidden"),
    ACTING_USER_REQUIRED(401, "An acting user is required"),
    UNKNOWN_TYPE(404, "Not a type in the current manifest"),
    UNKNOWN_VERB(404, "Not a verb in the current manifest"),
    UNSUPPORTED(400, "Not declared in the manifest"),
    INVALID_REQUEST(400, "The request is not valid"),
    KEY_BOUND(400, "More keys than the lookup's maxKeys"),
    EXPRESSION_BOUND(400, "The expression is over a limit"),
    APPROVAL_REQUIRED(403, "The call needs an approval"),
    APPROVER_NOT_AUTHORIZED(403, "The approver is not accepted"),
    RATE_LIMITED(429, "Rate limited"),
    UNAVAILABLE(503, "Temporarily unavailable"),
    PUBLISHER_DISABLED(503, "Publication is turned off"),
    INTERNAL(500, "The publisher failed");

    private final int status;
    private final String title;

    ErrorCode(int status, String title) {
        this.status = status;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }
}
