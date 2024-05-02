package io.github.nulled.transferguard.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** Who is calling: the JWT subject, or the client ID for machine tokens without one. */
public final class Actor {

    private Actor() {}

    public static String current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return "system";
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            if (jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
                return jwt.getSubject();
            }
            String clientId = jwt.getClaimAsString("client_id");
            if (clientId != null) {
                return clientId;
            }
        }
        return auth.getName();
    }

    public static String clientId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String clientId = jwt.getClaimAsString("client_id");
            return clientId != null ? clientId : jwt.getSubject();
        }
        return "anonymous";
    }
}
