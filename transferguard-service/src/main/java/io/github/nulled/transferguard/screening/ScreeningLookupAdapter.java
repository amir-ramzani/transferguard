package io.github.nulled.transferguard.screening;

import io.github.nulled.transferguard.cases.ScreeningLookup;
import io.github.nulled.transferguard.screening.api.ScreeningDetail;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ScreeningLookupAdapter implements ScreeningLookup {

    private final ScreeningRepository repository;

    ScreeningLookupAdapter(ScreeningRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ScreeningDetail> find(UUID screeningId) {
        return repository.findById(screeningId, false).map(ScreeningRepository.Row::detail);
    }
}
