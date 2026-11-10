package com.pokemans.app.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one place API errors become responses (architecture review #46). Every error is a problem detail
 * ({@code application/problem+json}): {@code title}, {@code status}, {@code detail} (the words people
 * read) and {@code instance}, plus {@code code} on an {@link ApiException}.
 *
 * <p>Spring's own handling stays as it was: it replaces Boot's {@code ProblemDetailsExceptionHandler},
 * which only applies when no other {@link ResponseEntityExceptionHandler} exists, and inherits the same
 * mappings, so {@code ResponseStatusException}s and request errors keep their status and message. What it
 * adds is the cases nobody mapped, which used to be a bare 500.
 */
@RestControllerAdvice
class ApiErrorHandler extends ResponseEntityExceptionHandler {

    /** Codes for errors this handler words itself. */
    enum Code { CONFLICT }

    static final String CONFLICT = "This changed while you were saving it. Reload the page and try again.";

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    /**
     * A write lost a race the service didn't catch (two requests creating the same row, say): a database
     * constraint said no, so nothing was saved. 409, not 500: trying again is the fix.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException e) {
        // The cause's message can quote the row's values, so only its type is logged.
        log.warn("A database constraint refused a write ({}); answered 409", e.getMostSpecificCause().getClass().getSimpleName());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, CONFLICT);
        body.setProperty(ApiException.CODE, Code.CONFLICT.name());
        return body;
    }
}
