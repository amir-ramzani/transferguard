package io.github.nulled.transferguard.screening.api;

public final class Accounts {

    public static final String PATTERN = "^[0-9]{4}-[0-9]{4}-[0-9]{4,8}$";
    public static final String MESSAGE = "must look like 1402-0012-3456";

    private Accounts() {}
}
