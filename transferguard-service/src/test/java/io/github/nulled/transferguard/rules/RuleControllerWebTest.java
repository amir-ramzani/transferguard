package io.github.nulled.transferguard.rules;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.nulled.transferguard.config.GlobalExceptionHandler;
import io.github.nulled.transferguard.config.Problems;
import io.github.nulled.transferguard.config.RequestSizeFilter;
import io.github.nulled.transferguard.config.SecurityConfig;
import io.github.nulled.transferguard.config.TransferGuardProperties;
import io.github.nulled.transferguard.watchlist.WatchlistController;
import io.github.nulled.transferguard.watchlist.WatchlistEntry;
import io.github.nulled.transferguard.watchlist.WatchlistService;
import io.github.nulled.transferguard.watchlist.WatchlistSource;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** If-Match/ETag handling on rule tuning, and watchlist path validation. */
@WebMvcTest({RuleController.class, WatchlistController.class})
@Import({SecurityConfig.class, Problems.class, GlobalExceptionHandler.class, RequestSizeFilter.class})
@EnableConfigurationProperties(TransferGuardProperties.class)
class RuleControllerWebTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    RuleConfigService rules;

    @MockBean
    WatchlistService watchlist;

    @MockBean
    JwtDecoder jwtDecoder;

    private static final RequestPostProcessor ADMIN =
            jwt().authorities(new SimpleGrantedAuthority("SCOPE_transferguard/rules.admin"));
    private static final String BODY = "{\"enabled\":true,\"weight\":30,\"params\":{\"maxCount\":5,\"window\":\"PT15M\"}}";

    private static RuleView view(int version) {
        return new RuleView(RuleCode.R1_VELOCITY, "Velocity", true, 30, SeedRules.MAPPER.createObjectNode(), version,
                OffsetDateTime.parse("2026-11-19T12:00:00+08:00"), "analyst");
    }

    @Test
    void getCarriesTheVersionAsETag() throws Exception {
        when(rules.get(RuleCode.R1_VELOCITY)).thenReturn(view(3));
        mvc.perform(get("/api/v1/rules/R1_VELOCITY").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""));
    }

    @Test
    void successfulUpdateReturnsTheNewETagNot412() throws Exception {
        when(rules.update(eq(RuleCode.R1_VELOCITY), eq(1), any(), anyString())).thenReturn(view(2));
        mvc.perform(put("/api/v1/rules/R1_VELOCITY").with(ADMIN).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void missingIfMatchIs428() throws Exception {
        mvc.perform(put("/api/v1/rules/R1_VELOCITY").with(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.type").value("https://transferguard.dev/problems/precondition-required"));
    }

    @Test
    void unknownRuleIs404() throws Exception {
        mvc.perform(get("/api/v1/rules/R9_NOPE").with(ADMIN)).andExpect(status().isNotFound());
    }

    @Test
    void watchlistCreateIs201AndBadAccountIs400() throws Exception {
        when(watchlist.put(eq("1402-0098-7654"), any(), anyString())).thenReturn(new WatchlistService.PutResult(
                new WatchlistEntry("1402-0098-7654", WatchlistSource.MANUAL, "r", null,
                        OffsetDateTime.parse("2026-11-19T12:00:00+08:00"), "analyst"), true));
        String body = "{\"source\":\"MANUAL\",\"reason\":\"r\"}";
        mvc.perform(put("/api/v1/watchlist/1402-0098-7654").with(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNo").value("1402-0098-7654"));
        mvc.perform(put("/api/v1/watchlist/not-an-account").with(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("accountNo"));
    }
}
