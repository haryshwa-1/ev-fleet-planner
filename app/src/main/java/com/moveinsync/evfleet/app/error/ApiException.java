package com.moveinsync.evfleet.app.error;

import org.springframework.http.HttpStatus;

/** A failure the client should see, with a stable machine-readable code. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
