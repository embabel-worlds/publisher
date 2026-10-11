package com.embabel.publisher.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** A refusal the world classifies by its {@link ErrorCode}, answered as RFC 9457 problem details. */
public class RealmException extends RuntimeException {

    private final ErrorCode code;
    private final String detail;

    public RealmException(ErrorCode code, String detail) {
        super(code + (detail == null ? "" : ": " + detail));
        this.code = code;
        this.detail = detail;
    }

    public static RealmException unsupported(String detail) {
        return new RealmException(ErrorCode.UNSUPPORTED, detail);
    }

    public static RealmException invalid(String detail) {
        return new RealmException(ErrorCode.INVALID_REQUEST, detail);
    }

    public ErrorCode code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    /** The problem document. {@code detail} is omitted when there is none to give. */
    public Map<String, Object> toProblem() {
        return problem(code, detail);
    }

    public static Map<String, Object> problem(ErrorCode code, String detail) {
        var problem = new LinkedHashMap<String, Object>();
        problem.put("type", "about:blank");
        problem.put("title", code.title());
        problem.put("status", code.status());
        problem.put("code", code.name());
        if (detail != null) {
            problem.put("detail", detail);
        }
        return problem;
    }
}
