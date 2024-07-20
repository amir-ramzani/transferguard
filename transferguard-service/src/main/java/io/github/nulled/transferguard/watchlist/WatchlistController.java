package io.github.nulled.transferguard.watchlist;

import io.github.nulled.transferguard.common.Actor;
import io.github.nulled.transferguard.common.PageResponse;
import io.github.nulled.transferguard.screening.api.Accounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/watchlist")
public class WatchlistController {

    private final WatchlistService service;

    public WatchlistController(WatchlistService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_transferguard/rules.admin')")
    public PageResponse<WatchlistEntry> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(page, size);
    }

    @PutMapping("/{accountNo}")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/rules.admin')")
    public ResponseEntity<WatchlistEntry> put(
            @PathVariable @Pattern(regexp = Accounts.PATTERN, message = Accounts.MESSAGE) String accountNo,
            @Valid @RequestBody WatchlistEntryRequest request) {
        var result = service.put(accountNo, request, Actor.current());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.entry());
    }

    @DeleteMapping("/{accountNo}")
    @PreAuthorize("hasAuthority('SCOPE_transferguard/rules.admin')")
    public ResponseEntity<Void> remove(
            @PathVariable @Pattern(regexp = Accounts.PATTERN, message = Accounts.MESSAGE) String accountNo) {
        service.remove(accountNo, Actor.current());
        return ResponseEntity.noContent().build();
    }
}
