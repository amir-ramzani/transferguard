package io.github.nulled.transferguard.cases;

import io.github.nulled.transferguard.common.Actor;
import io.github.nulled.transferguard.common.ApiException;
import io.github.nulled.transferguard.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/cases")
public class CaseController {

    private static final String CASE_ID = "^CASE-[0-9]{6,}$";

    private final CaseService service;

    public CaseController(CaseService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_transferguard/cases.read')")
    public PageResponse<FraudCase> list(@RequestParam(required = false) CaseStatus status,
                                        @RequestParam(required = false) @Size(max = 128) String assignee,
                                        @RequestParam(defaultValue = "0") @Min(0) int page,
                                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(status, assignee, page, size);
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/cases.read')")
    public CaseDetail get(@PathVariable String caseId) {
        return service.get(check(caseId));
    }

    @PatchMapping("/{caseId}")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/cases.write')")
    public FraudCase update(@PathVariable String caseId, @Valid @RequestBody CaseUpdate update) {
        return service.update(check(caseId), update, Actor.current());
    }

    @PostMapping("/{caseId}/notes")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/cases.write')")
    public ResponseEntity<CaseNote> addNote(@PathVariable String caseId, @Valid @RequestBody CaseNoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addNote(check(caseId), request, Actor.current()));
    }

    private static String check(String caseId) {
        if (!caseId.matches(CASE_ID)) {
            throw ApiException.notFound("No case " + caseId);
        }
        return caseId;
    }
}
