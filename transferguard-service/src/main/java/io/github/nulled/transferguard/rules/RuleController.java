package io.github.nulled.transferguard.rules;

import io.github.nulled.transferguard.common.Actor;
import io.github.nulled.transferguard.common.ApiException;
import io.github.nulled.transferguard.common.ETags;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RuleController {

    private final RuleConfigService service;

    public RuleController(RuleConfigService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/rules.admin')")
    public List<RuleView> list() {
        return service.list();
    }

    @GetMapping("/rules/{code}")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/rules.admin')")
    public ResponseEntity<RuleView> get(@PathVariable String code) {
        RuleView view = service.get(parse(code));
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PutMapping("/rules/{code}")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/rules.admin')")
    public ResponseEntity<RuleView> update(@PathVariable String code,
                                           @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                           @Valid @RequestBody RuleUpdateRequest request) {
        RuleCode rule = parse(code);
        int expected = ETags.require(ifMatch);
        RuleView view = service.update(rule, expected, request, Actor.current());
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @GetMapping("/settings/engine")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/rules.admin')")
    public ResponseEntity<EngineSettings> settings() {
        var v = service.getSettings();
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v.value());
    }

    @PutMapping("/settings/engine")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/rules.admin')")
    public ResponseEntity<EngineSettings> updateSettings(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody EngineSettings settings) {
        int expected = ETags.require(ifMatch);
        var v = service.updateSettings(expected, settings, Actor.current());
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v.value());
    }

    private static RuleCode parse(String code) {
        RuleCode parsed = RuleCode.parse(code);
        if (parsed == null) {
            throw ApiException.notFound("No rule " + code);
        }
        return parsed;
    }
}
