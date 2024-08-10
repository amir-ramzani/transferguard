package io.github.nulled.transferguard.audit;

import io.github.nulled.transferguard.common.Times;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Recomputes the chain from the first row and reports the first row whose hash does not match. */
@Component
public class AuditVerifier {

    private final AuditRepository repository;
    private final Clock clock;

    public AuditVerifier(AuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AuditVerifyResult verify() {
        AtomicLong checked = new AtomicLong();
        AtomicReference<String> expectedPrev = new AtomicReference<>(AuditHasher.GENESIS);
        AtomicReference<Long> firstBroken = new AtomicReference<>();
        AtomicReference<String> head = new AtomicReference<>(null);
        repository.streamAll(entry -> {
            checked.incrementAndGet();
            head.set(entry.hash());
            if (firstBroken.get() != null) {
                return;
            }
            String recomputed = AuditHasher.hash(entry.prevHash(), entry.actor(), entry.action(), entry.entity(),
                    entry.before(), entry.after(), entry.createdAt().toInstant());
            if (!entry.prevHash().equals(expectedPrev.get()) || !recomputed.equals(entry.hash())) {
                firstBroken.set(entry.id());
            }
            expectedPrev.set(entry.hash());
        });
        return new AuditVerifyResult(firstBroken.get() == null, checked.get(), firstBroken.get(), head.get(),
                Times.local(clock.instant()));
    }
}
