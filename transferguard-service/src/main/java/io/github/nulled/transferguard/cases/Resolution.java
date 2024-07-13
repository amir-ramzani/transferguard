package io.github.nulled.transferguard.cases;

public enum Resolution {
    CONFIRMED_FRAUD, FALSE_POSITIVE;

    public CaseStatus status() {
        return CaseStatus.valueOf(name());
    }
}
