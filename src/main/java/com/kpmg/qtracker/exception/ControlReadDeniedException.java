package com.kpmg.qtracker.exception;

import org.springframework.http.HttpStatus;

/**
 * An API read of a control refused before anything is loaded: 401 without a signed-in user,
 * 404 when the control does not exist, 403 when the user may not read it.
 */
public class ControlReadDeniedException extends RuntimeException {

    private final HttpStatus status;

    private ControlReadDeniedException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static ControlReadDeniedException unauthenticated() {
        return new ControlReadDeniedException(HttpStatus.UNAUTHORIZED, "User not authenticated");
    }

    public static ControlReadDeniedException notFound() {
        return new ControlReadDeniedException(HttpStatus.NOT_FOUND, "Control not found");
    }

    public static ControlReadDeniedException forbidden() {
        return new ControlReadDeniedException(HttpStatus.FORBIDDEN, "Access denied");
    }

    public HttpStatus getStatus() {
        return status;
    }
}
