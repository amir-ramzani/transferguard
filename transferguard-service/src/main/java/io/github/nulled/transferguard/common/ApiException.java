package io.github.nulled.transferguard.common;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error that maps directly to an RFC 9457 problem response. */
public class ApiException extends RuntimeException {

    public record FieldError(String field, String message) {}

    private final HttpStatus status;
    private final String type;
    private final String title;
    private final Map<String, String> headers;
    private final List<FieldError> errors;

    public ApiException(HttpStatus status, String type, String title, String detail,
                        Map<String, String> headers, List<FieldError> errors) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", detail, null, null);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "conflict", "Conflict", detail, null, null);
    }

    public static ApiException badRequest(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed",
                "The request failed validation.", null, List.of(new FieldError(field, message)));
    }

    public static ApiException preconditionFailed(String detail) {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, "precondition-failed", "Precondition failed",
                detail, null, null);
    }

    public static ApiException preconditionRequired() {
        return new ApiException(HttpStatus.PRECONDITION_REQUIRED, "precondition-required", "Precondition required",
                "Send If-Match with the ETag from your last read.", null, null);
    }

    public static ApiException unavailable(String detail) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "Service unavailable", detail,
                null, null);
    }

    public HttpStatus status() { return status; }
    public String type() { return type; }
    public String title() { return title; }
    public Map<String, String> headers() { return headers; }
    public List<FieldError> errors() { return errors; }
}
