package io.github.nulled.transferguard.screening;

import io.github.nulled.transferguard.screening.api.OutcomeRequest;
import io.github.nulled.transferguard.screening.api.ScreeningDetail;
import io.github.nulled.transferguard.screening.api.ScreeningRequest;
import io.github.nulled.transferguard.screening.api.ScreeningResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/screenings")
public class ScreeningController {

    private final ScreeningService screening;
    private final OutcomeService outcomes;

    public ScreeningController(ScreeningService screening, OutcomeService outcomes) {
        this.screening = screening;
        this.outcomes = outcomes;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_transferguard/screening.write')")
    public ResponseEntity<ScreeningResponse> screen(@Valid @RequestBody ScreeningRequest request) {
        ScreeningService.Result result = screening.screen(request);
        if (result.replay()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.response());
        }
        return ResponseEntity.created(URI.create("/api/v1/screenings/" + result.response().screeningId()))
                .body(result.response());
    }

    @GetMapping("/{screeningId}")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/screening.read')")
    public ScreeningDetail get(@PathVariable UUID screeningId) {
        return screening.get(screeningId);
    }

    @PostMapping("/{screeningId}/outcome")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/screening.write')")
    public ResponseEntity<Void> outcome(@PathVariable UUID screeningId, @Valid @RequestBody OutcomeRequest request) {
        outcomes.record(screeningId, request);
        return ResponseEntity.noContent().build();
    }
}
