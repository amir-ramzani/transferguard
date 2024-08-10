package io.github.nulled.transferguard.audit;

import io.github.nulled.transferguard.common.Times;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code transferguard_audit_chain_valid} (alert on 0) from a nightly verification, and
 * logs the chain head hourly. A hash chain shows edits but not deletion of the newest rows; in AWS
 * the hourly head is written to an S3 bucket with Object Lock to close that gap.
 */
@Component
public class AuditChainMonitor {

    private static final Logger log = LoggerFactory.getLogger(AuditChainMonitor.class);

    private final AuditVerifier verifier;
    private final AtomicInteger valid = new AtomicInteger(1);

    public AuditChainMonitor(AuditVerifier verifier, MeterRegistry registry) {
        this.verifier = verifier;
        Gauge.builder("transferguard.audit.chain.valid", valid, AtomicInteger::get)
                .description("1 when the audit hash chain verified, 0 when broken")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        Thread.ofVirtual().name("audit-verify-startup").start(this::verifyNow);
    }

    @Scheduled(cron = "${transferguard.audit.verify-cron:0 15 2 * * *}", zone = "Asia/Kuala_Lumpur")
    public void verifyNow() {
        try {
            record(verifier.verify());
        } catch (RuntimeException e) {
            log.warn("Audit chain verification could not run: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${transferguard.audit.anchor-cron:0 0 * * * *}", zone = "Asia/Kuala_Lumpur")
    public void anchorHead() {
        try {
            AuditVerifyResult result = verifier.verify();
            record(result);
            log.info("audit.head hash={} entries={} at={}", result.headHash(), result.entriesChecked(),
                    Times.local(result.checkedAt().toInstant()));
        } catch (RuntimeException e) {
            log.warn("Audit head anchor failed: {}", e.getMessage());
        }
    }

    public void record(AuditVerifyResult result) {
        valid.set(result.valid() ? 1 : 0);
        if (!result.valid()) {
            log.error("AUDIT CHAIN BROKEN at id={} after {} entries", result.firstBrokenId(), result.entriesChecked());
        }
    }
}
