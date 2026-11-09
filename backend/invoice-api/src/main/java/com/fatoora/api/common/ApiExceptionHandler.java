package com.fatoora.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ErrorBody(String code, String message, Map<String, String> fields) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException e) {
        var fields = e instanceof ApiException.Validation v ? v.fields() : null;
        return ResponseEntity.status(e.status()).body(new ErrorBody(e.code(), e.getMessage(), fields));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(f -> f.getField(), f -> String.valueOf(f.getDefaultMessage()), (a, b) -> a));
        return ResponseEntity.badRequest().body(new ErrorBody("validation", "Some fields are invalid", fields));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> illegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorBody("invalid", e.getMessage(), null));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ErrorBody> concurrent(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody("concurrent_update", "Someone else changed this record. Reload and try again.", null));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<ErrorBody> forbidden(org.springframework.security.access.AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorBody("forbidden", "You don't have permission to do this.", null));
    }

    /** Client mistakes are answered as such (4xx), never as a server error. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorBody("malformed_request", "The request body is not valid JSON for this form", null));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorBody> mediaType(org.springframework.web.HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorBody("unsupported_type", "This file or content type is not accepted here (" + e.getContentType() + ")", null));
    }

    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class})
    ResponseEntity<ErrorBody> badParameter(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorBody("invalid_parameter", "A parameter is missing or has the wrong format", null));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorBody> method(Exception e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new ErrorBody("method_not_allowed", "This action is not supported here", null));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ErrorBody> noResource(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("not_found", "Not found", null));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> tooLarge(Exception e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ErrorBody("too_large", "The file is too large", null));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> integrity(Exception e) {
        log.warn("Data integrity: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("conflict", "This change conflicts with existing data (duplicate or still in use)", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.internalServerError().body(new ErrorBody("internal", "Unexpected server error", null));
    }
}
