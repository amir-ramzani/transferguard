package io.github.nulled.transferguard.screening;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nulled.transferguard.common.ApiException;
import io.github.nulled.transferguard.config.GlobalExceptionHandler;
import io.github.nulled.transferguard.config.Problems;
import io.github.nulled.transferguard.config.RequestSizeFilter;
import io.github.nulled.transferguard.config.SecurityConfig;
import io.github.nulled.transferguard.config.TransferGuardProperties;
import io.github.nulled.transferguard.screening.api.Decision;
import io.github.nulled.transferguard.screening.api.ScreeningResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Web slice: scopes, validation and RFC 9457 problem shapes, with the service mocked. */
@WebMvcTest(ScreeningController.class)
@Import({SecurityConfig.class, Problems.class, GlobalExceptionHandler.class, RequestSizeFilter.class})
@EnableConfigurationProperties(TransferGuardProperties.class)
class ScreeningControllerWebTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ScreeningService screening;

    @MockitoBean
    OutcomeService outcomes;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static RequestPostProcessor scope(String scope) {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_transferguard/" + scope));
    }

    private static String body(String amount) {
        return """
                {"transactionId":"TXN-1","sourceAccount":"1402-0012-3456","payeeAccount":"1402-0098-7654",
                 "amount":"%s","currency":"MYR","channel":"MOBILE","deviceId":"dev-1",
                 "requestedAt":"%s"}""".formatted(amount, OffsetDateTime.now(ZoneOffset.ofHours(8)));
    }

    private static ScreeningResponse response() {
        return new ScreeningResponse(UUID.fromString("4b2e9c7a-1f0d-4c2b-9a8e-2f6d1c3b5a90"), "TXN-1", Decision.ALLOW, 0,
                false, List.of(), null, OffsetDateTime.parse("2026-11-19T01:42:10.184+08:00"));
    }

    @Test
    void noTokenIs401Problem() throws Exception {
        mvc.perform(post("/api/v1/screenings").contentType(MediaType.APPLICATION_JSON).content(body("10.00")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.type").value("https://transferguard.dev/problems/unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void wrongScopeIs403Problem() throws Exception {
        mvc.perform(post("/api/v1/screenings").with(scope("screening.read"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("10.00")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://transferguard.dev/problems/forbidden"));
    }

    @Test
    void firstScreeningIs201WithLocation() throws Exception {
        when(screening.screen(any())).thenReturn(new ScreeningService.Result(response(), false));
        mvc.perform(post("/api/v1/screenings").with(scope("screening.write"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("10.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/screenings/4b2e9c7a-1f0d-4c2b-9a8e-2f6d1c3b5a90"))
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.screenedAt").value("2026-11-19T01:42:10.184+08:00"))
                .andExpect(jsonPath("$.caseId").doesNotExist());
    }

    @Test
    void replayIs200WithHeader() throws Exception {
        when(screening.screen(any())).thenReturn(new ScreeningService.Result(response(), true));
        mvc.perform(post("/api/v1/screenings").with(scope("screening.write"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("10.00")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
    }

    @Test
    void invalidFieldsAreListed() throws Exception {
        String bad = """
                {"transactionId":"TXN 1","sourceAccount":"12","payeeAccount":"1402-0098-7654",
                 "amount":"10.5","currency":"MYR","channel":"MOBILE","requestedAt":"2026-11-19T01:42:10+08:00"}""";
        mvc.perform(post("/api/v1/screenings").with(scope("screening.write"))
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://transferguard.dev/problems/validation"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("amount")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("sourceAccount")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("transactionId")));
    }

    @Test
    void unknownFieldsAreRejected() throws Exception {
        String extra = body("10.00").replace("\"currency\"", "\"hacked\":true,\"currency\"");
        mvc.perform(post("/api/v1/screenings").with(scope("screening.write"))
                        .contentType(MediaType.APPLICATION_JSON).content(extra))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("hacked"));
    }

    @Test
    void wrongEnumIsAFieldError() throws Exception {
        mvc.perform(post("/api/v1/screenings").with(scope("screening.write"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("10.00").replace("MYR", "USD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("currency"));
    }

    @Test
    void inProgressIs409WithRetryAfter() throws Exception {
        when(screening.screen(any())).thenThrow(new ApiException(HttpStatus.CONFLICT, "idempotency-in-progress",
                "Screening in progress", "busy", Map.of("Retry-After", "1"), null));
        mvc.perform(post("/api/v1/screenings").with(scope("screening.write"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("10.00")))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.type").value("https://transferguard.dev/problems/idempotency-in-progress"));
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        String huge = body("10.00").replace("dev-1", "x".repeat(17_000));
        mvc.perform(post("/api/v1/screenings").with(scope("screening.write"))
                        .contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().is(413));
    }

    @Test
    void readNeedsTheReadScope() throws Exception {
        mvc.perform(get("/api/v1/screenings/4b2e9c7a-1f0d-4c2b-9a8e-2f6d1c3b5a90").with(scope("screening.write")))
                .andExpect(status().isForbidden());
    }

    @Test
    void notFoundIsAProblem() throws Exception {
        when(screening.get(any())).thenThrow(ApiException.notFound("No screening"));
        mvc.perform(get("/api/v1/screenings/4b2e9c7a-1f0d-4c2b-9a8e-2f6d1c3b5a90").with(scope("screening.read")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://transferguard.dev/problems/not-found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/screenings/4b2e9c7a-1f0d-4c2b-9a8e-2f6d1c3b5a90"));
    }
}
