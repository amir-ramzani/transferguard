package io.github.nulled.transferguard.config;

import io.github.nulled.transferguard.common.ApiException.FieldError;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Builds RFC 9457 problem details with a {@code traceId}, so a support call can jump to the trace. */
@Component
public class Problems {

    public static final String TYPE_BASE = "https://transferguard.dev/problems/";

    private final ObjectProvider<Tracer> tracer;
    private final JsonMapper mapper;

    public Problems(ObjectProvider<Tracer> tracer, JsonMapper mapper) {
        this.tracer = tracer;
        this.mapper = mapper;
    }

    public ProblemDetail build(HttpStatusCode status, String type, String title, String detail, String instance,
                               List<FieldError> errors) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create(TYPE_BASE + type));
        p.setTitle(title);
        if (instance != null) {
            p.setInstance(URI.create(instance));
        }
        enrich(p, errors);
        return p;
    }

    public void enrich(ProblemDetail p, List<FieldError> errors) {
        String traceId = traceId();
        if (traceId != null) {
            p.setProperty("traceId", traceId);
        }
        if (errors != null && !errors.isEmpty()) {
            p.setProperty("errors", errors.stream().map(e -> Map.of("field", e.field(), "message", e.message())).toList());
        }
    }

    /** For filters that run outside Spring MVC (authentication, rate limiting, size limits). */
    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatusCode status, String type,
                      String title, String detail, Map<String, String> headers) throws IOException {
        ProblemDetail p = build(status, type, title, detail, request.getRequestURI(), null);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        headers.forEach(response::setHeader);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("type", p.getType().toString());
        body.put("title", p.getTitle());
        body.put("status", p.getStatus());
        body.put("detail", p.getDetail());
        body.put("instance", request.getRequestURI());
        if (p.getProperties() != null) {
            body.putAll(p.getProperties());
        }
        mapper.writeValue(response.getOutputStream(), body);
    }

    private String traceId() {
        Tracer t = tracer.getIfAvailable();
        if (t != null) {
            Span span = t.currentSpan();
            if (span != null) {
                return span.context().traceId();
            }
        }
        return MDC.get("traceId");
    }
}
