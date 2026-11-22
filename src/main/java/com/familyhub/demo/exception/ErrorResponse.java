package com.familyhub.demo.exception;


import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        int httpStatus,
        String path,
        String message,
        Instant timeStamp,
        List<ValidationError> errors,
        /** A machine-readable reason, for errors the app explains (Phase 4's calendar codes); else null. */
        String code
) {

    public ErrorResponse(int httpStatus, String path, String message) {
        this(httpStatus, path, message, Instant.now(), null, null);
    }

    public ErrorResponse(int httpStatus, String path, String message, List<ValidationError> errors) {
        this(httpStatus, path, message, Instant.now(), errors, null);
    }

    public ErrorResponse(int httpStatus, String path, String message, Instant timeStamp,
                         List<ValidationError> errors) {
        this(httpStatus, path, message, timeStamp, errors, null);
    }

    public record ValidationError(String field, String message) {}
}

