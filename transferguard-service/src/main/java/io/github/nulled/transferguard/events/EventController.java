package io.github.nulled.transferguard.events;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final InboundCreditService credits;
    private final SecurityEventService security;

    public EventController(InboundCreditService credits, SecurityEventService security) {
        this.credits = credits;
        this.security = security;
    }

    @PostMapping("/inbound-credits")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/events.write')")
    public ResponseEntity<Void> inboundCredit(@Valid @RequestBody InboundCreditEvent event) {
        credits.record(event);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/security")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/events.write')")
    public ResponseEntity<Void> securityEvent(@Valid @RequestBody SecurityEvent event) {
        security.record(event);
        return ResponseEntity.accepted().build();
    }
}
