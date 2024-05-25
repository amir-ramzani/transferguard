package io.github.nulled.transferguard.rules;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.nulled.transferguard.audit.AuditWriter;
import io.github.nulled.transferguard.common.AfterCommit;
import io.github.nulled.transferguard.common.ApiException;
import io.github.nulled.transferguard.common.ApiException.FieldError;
import jakarta.annotation.PostConstruct;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Rules and engine settings as data. Each pod keeps them in a Caffeine cache that is invalidated by
 * a message on {@code tg:rules:changed}; a 60-second expiry is the backstop if a message is lost.
 */
@Service
public class RuleConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuleConfigService.class);
    private static final String ENGINE_KEY = "engine";

    public record Snapshot(Map<RuleCode, RuleDefinition> rules, EngineSettings settings, int settingsVersion) {}

    public record Versioned<T>(T value, int version) {}

    private final RuleRepository repository;
    private final Map<RuleCode, Rule<?>> rules;
    private final AuditWriter audit;
    private final RuleChangeBus bus;
    private final Validator validator;
    private final ObjectMapper strictMapper;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final LoadingCache<String, Snapshot> cache;

    public RuleConfigService(RuleRepository repository, List<Rule<?>> ruleBeans, AuditWriter audit, RuleChangeBus bus,
                             Validator validator, ObjectMapper mapper, Clock clock,
                             @Value("${transferguard.rules.cache-ttl:PT60S}") Duration ttl) {
        this.repository = repository;
        Map<RuleCode, Rule<?>> byCode = new EnumMap<>(RuleCode.class);
        ruleBeans.forEach(r -> byCode.put(r.code(), r));
        this.rules = Collections.unmodifiableMap(byCode);
        this.audit = audit;
        this.bus = bus;
        this.validator = validator;
        this.mapper = mapper;
        this.strictMapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        this.clock = clock;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).build(k -> load());
    }

    @PostConstruct
    void listen() {
        bus.subscribe(change -> {
            log.info("Rule configuration changed ({}); reloading", change);
            invalidate();
        });
    }

    public void invalidate() {
        cache.invalidateAll();
    }

    public Snapshot snapshot() {
        return cache.get("all");
    }

    /** Enabled rules in code order, bound to their parsed parameters. */
    public List<ActiveRule> activeRules() {
        Snapshot s = snapshot();
        List<ActiveRule> active = new ArrayList<>();
        for (RuleDefinition d : s.rules().values()) {
            Rule<?> rule = rules.get(d.code());
            if (d.enabled() && rule != null) {
                active.add(new ActiveRule(rule, d));
            }
        }
        return active;
    }

    public EngineSettings settings() {
        return snapshot().settings();
    }

    public Rule<?> rule(RuleCode code) {
        return rules.get(code);
    }

    // ---- Admin API ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return load().rules().values().stream().map(RuleView::of).toList();
    }

    @Transactional(readOnly = true)
    public RuleView get(RuleCode code) {
        RuleDefinition d = load().rules().get(code);
        if (d == null) {
            throw ApiException.notFound("No rule " + code);
        }
        return RuleView.of(d);
    }

    @Transactional
    public RuleView update(RuleCode code, int expectedVersion, RuleUpdateRequest request, String actor) {
        Rule<?> rule = rules.get(code);
        RuleRepository.RuleRow current = repository.find(code.name())
                .orElseThrow(() -> ApiException.notFound("No rule " + code));
        if (rule == null) {
            throw ApiException.notFound("No rule " + code);
        }
        parseParams(rule, request.params(), "params.");
        if (current.version() != expectedVersion) {
            throw ApiException.preconditionFailed("Rule " + code + " is at version " + current.version()
                    + "; read it again and retry.");
        }
        Instant now = clock.instant();
        if (!repository.update(code.name(), request.enabled(), request.weight(), request.params(), expectedVersion,
                actor, now)) {
            throw ApiException.preconditionFailed("Rule " + code + " changed while you were editing it.");
        }
        RuleView before = RuleView.of(toDefinition(current, rule));
        RuleView after = RuleView.of(toDefinition(repository.find(code.name()).orElseThrow(), rule));
        audit.append(actor, "RULE_UPDATED", "rule:" + code, before, after);
        AfterCommit.run(() -> {
            invalidate();
            publish("rule:" + code);
        });
        return after;
    }

    @Transactional(readOnly = true)
    public Versioned<EngineSettings> getSettings() {
        Snapshot s = load();
        return new Versioned<>(s.settings(), s.settingsVersion());
    }

    @Transactional
    public Versioned<EngineSettings> updateSettings(int expectedVersion, EngineSettings settings, String actor) {
        if (settings.allowMax() >= settings.challengeMax()) {
            throw ApiException.badRequest("challengeMax", "must be greater than allowMax");
        }
        EngineSettings normalised = new EngineSettings(settings.allowMax(), settings.challengeMax(),
                settings.failMode(), settings.degradedChallengeMinAmount() == null
                        ? EngineSettings.DEFAULT_DEGRADED_MIN : settings.degradedChallengeMinAmount());
        RuleRepository.SettingRow current = repository.findSetting(ENGINE_KEY)
                .orElseThrow(() -> new IllegalStateException("engine_setting 'engine' is missing"));
        if (current.version() != expectedVersion) {
            throw ApiException.preconditionFailed("Settings are at version " + current.version()
                    + "; read them again and retry.");
        }
        JsonNode value = mapper.valueToTree(normalised);
        if (!repository.updateSetting(ENGINE_KEY, value, expectedVersion, actor, clock.instant())) {
            throw ApiException.preconditionFailed("Settings changed while you were editing them.");
        }
        audit.append(actor, "SETTINGS_UPDATED", "settings:engine", current.value(), value);
        AfterCommit.run(() -> {
            invalidate();
            publish("settings:engine");
        });
        return new Versioned<>(normalised, expectedVersion + 1);
    }

    // ---- Loading and validation ---------------------------------------------------------------

    private Snapshot load() {
        Map<RuleCode, RuleDefinition> defs = new EnumMap<>(RuleCode.class);
        for (RuleRepository.RuleRow row : repository.findAll()) {
            RuleCode code = RuleCode.parse(row.code());
            Rule<?> rule = code == null ? null : rules.get(code);
            if (rule == null) {
                log.warn("Rule row {} has no implementation; ignored", row.code());
                continue;
            }
            try {
                defs.put(code, toDefinition(row, rule));
            } catch (ApiException e) {
                log.error("Rule {} has invalid stored params ({}); disabled until fixed", code, e.errors());
                defs.put(code, new RuleDefinition(code, row.name(), false, row.weight(), row.params(), null,
                        row.version(), row.updatedAt(), row.updatedBy()));
            }
        }
        RuleRepository.SettingRow setting = repository.findSetting(ENGINE_KEY)
                .orElseThrow(() -> new IllegalStateException("engine_setting 'engine' is missing"));
        EngineSettings settings;
        try {
            settings = strictMapper.treeToValue(setting.value(), EngineSettings.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("engine_setting 'engine' is invalid", e);
        }
        return new Snapshot(Collections.unmodifiableMap(defs), settings, setting.version());
    }

    private RuleDefinition toDefinition(RuleRepository.RuleRow row, Rule<?> rule) {
        RuleParams params = parseParams(rule, row.params(), "params.");
        return new RuleDefinition(rule.code(), row.name(), row.enabled(), row.weight(), row.params(), params,
                row.version(), row.updatedAt(), row.updatedBy());
    }

    /** Checks params against the rule's own record: no unknown fields, Bean Validation, cross-field checks. */
    RuleParams parseParams(Rule<?> rule, JsonNode params, String prefix) {
        if (params == null || !params.isObject()) {
            throw ApiException.badRequest("params", "must be an object");
        }
        RuleParams parsed;
        try {
            parsed = strictMapper.treeToValue(params, rule.paramsType());
        } catch (JacksonException e) {
            throw ApiException.badRequest("params", "does not match " + rule.code() + ": " + e.getOriginalMessage());
        }
        Set<ConstraintViolation<RuleParams>> violations = validator.validate(parsed);
        if (!violations.isEmpty()) {
            List<FieldError> errors = violations.stream()
                    .map(v -> new FieldError(prefix + v.getPropertyPath(), v.getMessage()))
                    .sorted(java.util.Comparator.comparing(FieldError::field))
                    .toList();
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed",
                    "params do not match " + rule.code(), null, errors);
        }
        String cross = parsed.crossCheck();
        if (cross != null) {
            throw ApiException.badRequest("params", cross);
        }
        return parsed;
    }

    private void publish(String change) {
        try {
            bus.publish(change);
        } catch (RuntimeException e) {
            log.warn("Could not publish rule change {}; other pods pick it up within the cache TTL: {}",
                    change, e.getMessage());
        }
    }
}
