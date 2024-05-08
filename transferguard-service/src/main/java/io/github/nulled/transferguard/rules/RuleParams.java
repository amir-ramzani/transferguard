package io.github.nulled.transferguard.rules;

/** Marker for a rule's typed parameter record, parsed from the rule row's JSONB {@code params}. */
public interface RuleParams {

    /** Cross-field checks Bean Validation can't express. Returns an error message or null. */
    default String crossCheck() {
        return null;
    }
}
