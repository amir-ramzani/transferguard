package io.github.nulled.transferguard.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * OAuth2 resource server. Every API call needs a JWT access token; each endpoint checks exactly one
 * {@code transferguard/...} scope with {@code @PreAuthorize}. Errors are problem details.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain api(HttpSecurity http, JwtDecoder decoder, Problems problems,
                                   TransferGuardProperties props) throws Exception {
        ProblemHandlers handlers = new ProblemHandlers(problems);
        http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/openapi.yaml",
                                "/actuator/**", "/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(decoder))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .addFilterAfter(new RateLimitFilter(props.rateLimit(), problems), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(TransferGuardProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.security().jwkSetUri()).build();
        decoder.setJwtValidator(JwtValidators.of(props.security()));
        return decoder;
    }

    static final class ProblemHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {
        private final Problems problems;

        ProblemHandlers(Problems problems) {
            this.problems = problems;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
                throws IOException {
            problems.write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized",
                    "Missing, expired or invalid access token.",
                    Map.of("WWW-Authenticate", "Bearer realm=\"transferguard\""));
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
                throws IOException {
            problems.write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "The access token lacks the required scope.", Map.of());
        }
    }
}
