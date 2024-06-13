package io.github.nulled.transferguard.profile;

import io.github.nulled.transferguard.common.Times;
import io.github.nulled.transferguard.rules.Features.AmountHistory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 30-day account statistics from completed transfers. Daily rows replace a running average, because
 * a true 30-day average has to forget old days.
 */
@Service
public class AccountProfileService {

    private static final Logger log = LoggerFactory.getLogger(AccountProfileService.class);
    static final int WINDOW_DAYS = 30;
    static final int RETENTION_DAYS = 35;

    private final DailyStatsRepository repository;
    private final Clock clock;

    public AccountProfileService(DailyStatsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Records one completed transfer on the bank-local day it happened. Must run in the outcome transaction. */
    public void recordCompleted(String account, BigDecimal amount, Instant occurredAt) {
        repository.add(account, occurredAt.atZone(Times.BANK_ZONE).toLocalDate(), amount);
    }

    public AmountHistory history(String account, Instant now) {
        LocalDate today = now.atZone(Times.BANK_ZONE).toLocalDate();
        DailyStatsRepository.Totals totals = repository.totals(account, today.minusDays(WINDOW_DAYS - 1L), today);
        if (totals.txnCount() == 0) {
            return AmountHistory.NONE;
        }
        BigDecimal average = totals.amountSum().divide(BigDecimal.valueOf(totals.txnCount()), 2, RoundingMode.HALF_UP);
        return new AmountHistory(average, totals.txnCount());
    }

    @Scheduled(cron = "${transferguard.profile.purge-cron:0 30 3 * * *}", zone = "Asia/Kuala_Lumpur")
    public void purge() {
        LocalDate cutoff = clock.instant().atZone(Times.BANK_ZONE).toLocalDate().minusDays(RETENTION_DAYS);
        int removed = repository.purgeBefore(cutoff);
        log.info("Purged {} account_daily_stats rows before {}", removed, cutoff);
    }
}
