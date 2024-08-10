package io.github.nulled.transferguard.audit;

import io.github.nulled.transferguard.common.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService service;
    private final AuditVerifier verifier;
    private final AuditChainMonitor monitor;

    public AuditController(AuditService service, AuditVerifier verifier, AuditChainMonitor monitor) {
        this.service = service;
        this.verifier = verifier;
        this.monitor = monitor;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_transferguard/audit.read')")
    public PageResponse<AuditEntry> list(@RequestParam(required = false) @Size(max = 128) String entity,
                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(entity, page, size);
    }

    @GetMapping("/verify")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/audit.read')")
    public AuditVerifyResult verify() {
        AuditVerifyResult result = verifier.verify();
        monitor.record(result);
        return result;
    }
}
