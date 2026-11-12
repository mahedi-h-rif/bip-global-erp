package com.fatoora.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.FieldError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Each field's error is a full sentence ("Line 2: Name is required"), and the message lists them all. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError f : e.getBindingResult().getFieldErrors()) fields.putIfAbsent(f.getField(), FriendlyValidation.message(f));
        List<String> problems = new ArrayList<>(fields.values());
        e.getBindingResult().getGlobalErrors().forEach(g -> problems.add(FriendlyValidation.message(g)));
        return ResponseEntity.badRequest().body(new ErrorBody("validation", summary(problems), fields.isEmpty() ? null : fields));
    }

    /** Validation of a list body or of request parameters (e.g. @Valid List<Line>). */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    ResponseEntity<ErrorBody> methodValidation(org.springframework.web.method.annotation.HandlerMethodValidationException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (var result : e.getParameterValidationResults()) {
            for (MessageSourceResolvable r : result.getResolvableErrors()) {
                if (r instanceof FieldError f) {
                    String key = result.getContainerIndex() != null ? "[" + result.getContainerIndex() + "]." + f.getField() : f.getField();
                    fields.putIfAbsent(key, FriendlyValidation.message(key, f));
                } else {
                    String name = result.getMethodParameter().getParameterName();
                    String label = name == null ? "" : FriendlyValidation.label(name);
                    String msg = r.getDefaultMessage() == null ? "is invalid" : r.getDefaultMessage();
                    problems.add(label.isEmpty() ? FriendlyValidation.capitalize(msg) : label + ": " + msg);
                }
            }
        }
        problems.addAll(0, fields.values());
        return ResponseEntity.badRequest().body(new ErrorBody("validation", summary(problems), fields.isEmpty() ? null : fields));
    }

    private static String summary(List<String> problems) {
        if (problems.isEmpty()) return "Some details are invalid. Please check the form.";
        return problems.stream().map(p -> p.endsWith(".") ? p : p + ".").collect(Collectors.joining(" "));
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
    ResponseEntity<ErrorBody> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        // A wrong value in one field (text in a number, a bad date): name the field instead of blaming the "JSON".
        if (e.getCause() instanceof tools.jackson.databind.DatabindException d && !d.getPath().isEmpty()) {
            StringBuilder path = new StringBuilder();
            for (var ref : d.getPath()) {
                if (ref.getPropertyName() != null) path.append(path.isEmpty() ? "" : ".").append(ref.getPropertyName());
                else if (ref.getIndex() >= 0) path.append("[").append(ref.getIndex()).append("]");
            }
            String key = path.toString();
            if (!key.isEmpty()) {
                String text = FriendlyValidation.label(key) + " has an invalid value";
                return ResponseEntity.badRequest().body(new ErrorBody("validation", text + ".", Map.of(key, text)));
            }
        }
        return ResponseEntity.badRequest().body(new ErrorBody("malformed_request", "Some of the entered values could not be read. Please check the form and try again.", null));
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
        return ResponseEntity.badRequest().body(new ErrorBody("invalid_parameter", "Some required information is missing or in the wrong format. Please check and try again.", null));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorBody> method(Exception e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(new ErrorBody("method_not_allowed", "This action isn't available here.", null));
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
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("conflict", "This can't be saved because it duplicates an existing record, or the record is still in use elsewhere.", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.internalServerError().body(new ErrorBody("internal", "Something went wrong on our side. Please try again; if it keeps happening, contact support.", null));
    }
}
