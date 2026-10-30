package com.moveinsync.evfleet.app.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error leaves as RFC 7807 JSON with a stable {@code code}; details stay in the logs. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handle(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail handle(DataAccessException e) {
        log.error("database failure", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "DB_UNAVAILABLE", "The database could not complete the request");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handle(Exception e) {
        log.error("unexpected failure", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong on our side");
    }

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setProperty("code", code);
        return p;
    }
}
