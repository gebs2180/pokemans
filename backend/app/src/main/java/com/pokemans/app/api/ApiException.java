package com.pokemans.app.api;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * A refusal with a code the web app can branch on, beside the words people read (architecture review
 * #27). It renders like any {@link ResponseStatusException}, a problem detail whose {@code detail} is the
 * message, plus a {@code code} property. Branch on the code, never on the message.
 */
public class ApiException extends ResponseStatusException {

    /** The problem-detail property that carries the code. */
    public static final String CODE = "code";

    private final String code;

    public ApiException(HttpStatusCode status, Enum<?> code, String message) {
        super(status, message);
        this.code = code.name();
        getBody().setProperty(CODE, this.code);
    }

    public String getCode() {
        return code;
    }
}
