package io.github.nulled.transferguard.cases;

import io.github.nulled.transferguard.screening.api.ScreeningDetail;
import java.util.Optional;
import java.util.UUID;

/** Implemented by the screening module, so cases can show a screening without depending on it. */
public interface ScreeningLookup {

    Optional<ScreeningDetail> find(UUID screeningId);
}
