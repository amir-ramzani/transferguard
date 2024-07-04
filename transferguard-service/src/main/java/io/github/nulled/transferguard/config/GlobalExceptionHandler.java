package io.github.nulled.transferguard.config;

import io.github.nulled.transferguard.common.ApiException;
import io.github.nulled.transferguard.common.ApiException.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import com.fasterxml.jackson.databind.JsonMappingException;

/** Every error is an RFC 9457 problem with a traceId; validation errors list each invalid field. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Problems problems;

    public GlobalExceptionHandler(Problems problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e, HttpServletRequest request) {
        ProblemDetail p = problems.build(e.status(), e.type(), e.title(), e.getMessage(), request.getRequestURI(),
                e.errors());
        HttpHeaders headers = new HttpHeaders();
        e.headers().forEach(headers::set);
        return ResponseEntity.status(e.status()).headers(headers).body(p);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraint(ConstraintViolationException e, HttpServletRequest request) {
        List<FieldError> errors = e.getConstraintViolations().stream()
                .map(v -> new FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .sorted(Comparator.comparing(FieldError::field)).toList();
        return validation(errors, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return validation(List.of(new FieldError(e.getName(), "has an invalid value")), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problems.build(HttpStatus.FORBIDDEN, "forbidden",
                "Forbidden", "The access token lacks the required scope.", request.getRequestURI(), null));
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class, TransientDataAccessException.class})
    ResponseEntity<ProblemDetail> databaseDown(Exception e, HttpServletRequest request) {
        log.error("PostgreSQL unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problems.build(HttpStatus.SERVICE_UNAVAILABLE,
                "unavailable", "Service unavailable",
                "The decision could not be recorded, so none was made. Apply your fallback.",
                request.getRequestURI(), null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problems.build(
                HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Internal error",
                "Something went wrong. Quote the traceId when you contact support.", request.getRequestURI(), null));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(f -> errors.add(new FieldError(f.getField(), f.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(g -> errors.add(new FieldError(g.getObjectName(), g.getDefaultMessage())));
        errors.sort(Comparator.comparing(FieldError::field));
        return cast(validation(errors, ((ServletWebRequest) request).getRequest()));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        ex.getAllValidationResults().forEach(r -> r.getResolvableErrors().forEach(err -> errors.add(
                new FieldError(r.getMethodParameter().getParameterName(), err.getDefaultMessage()))));
        return cast(validation(errors, ((ServletWebRequest) request).getRequest()));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        HttpServletRequest servlet = ((ServletWebRequest) request).getRequest();
        if (rootCause(ex) instanceof RequestSizeFilter.PayloadTooLargeException) {
            return cast(ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problems.build(
                    HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", "Payload too large",
                    "Request bodies are limited to 16 KB.", servlet.getRequestURI(), null)));
        }
        String field = "body";
        String message = "is not valid JSON for this operation";
        if (ex.getCause() instanceof JsonMappingException db) {
            String path = db.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .reduce((a, b) -> b.startsWith("[") ? a + b : a + "." + b).orElse("body");
            field = path;
            message = db.getOriginalMessage().contains("Unrecognized property") ? "is not a known field"
                    : "has an invalid value";
        }
        return cast(validation(List.of(new FieldError(field, message)), servlet));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail p) {
            problems.enrich(p, null);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ResponseEntity<ProblemDetail> validation(List<FieldError> errors, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(problems.build(HttpStatus.BAD_REQUEST, "validation",
                "Validation failed", "The request failed validation.", request.getRequestURI(), errors));
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Object> cast(ResponseEntity<?> e) {
        return (ResponseEntity) e;
    }
}
