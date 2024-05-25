package io.github.nulled.transferguard.rules;

import static org.mockito.Mockito.mock;

import io.github.nulled.transferguard.audit.AuditWriter;
import io.github.nulled.transferguard.rules.impl.AmountAnomalyRule;
import io.github.nulled.transferguard.rules.impl.NewDeviceRule;
import io.github.nulled.transferguard.rules.impl.NewPayeeHighAmountRule;
import io.github.nulled.transferguard.rules.impl.OddHoursRule;
import io.github.nulled.transferguard.rules.impl.PassThroughRule;
import io.github.nulled.transferguard.rules.impl.VelocityRule;
import io.github.nulled.transferguard.rules.impl.WatchlistRule;
import jakarta.validation.Validation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** The rules exactly as the Flyway seed (V2, V3) defines them, for tests that run without a database. */
public final class SeedRules {

    public static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern ROW = Pattern.compile(
            "\\('(R\\d_[A-Z_]+)',\\s*'([^']*)',\\s*(true|false),\\s*(\\d+),\\s*'(\\{[^']*\\})'\\)");

    private SeedRules() {}

    public static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static List<Rule<?>> beans() {
        return List.of(new VelocityRule(), new NewPayeeHighAmountRule(), new AmountAnomalyRule(), new OddHoursRule(),
                new PassThroughRule(), new NewDeviceRule(), new WatchlistRule());
    }

    public static RuleConfigService configService() {
        return new RuleConfigService(mock(RuleRepository.class), beans(), mock(AuditWriter.class),
                mock(RuleChangeBus.class), Validation.buildDefaultValidatorFactory().getValidator(), MAPPER,
                Clock.systemUTC(), Duration.ofSeconds(60));
    }

    public record SeedRow(String code, String name, boolean enabled, int weight, JsonNode params) {}

    public static List<SeedRow> rows() {
        Matcher m = ROW.matcher(read("V2__seed_rules.sql"));
        List<SeedRow> rows = new ArrayList<>();
        while (m.find()) {
            rows.add(new SeedRow(m.group(1), m.group(2), Boolean.parseBoolean(m.group(3)),
                    Integer.parseInt(m.group(4)), json(m.group(5))));
        }
        return rows;
    }

    public static List<ActiveRule> active() {
        RuleConfigService config = configService();
        List<ActiveRule> active = new ArrayList<>();
        for (SeedRow row : rows()) {
            Rule<?> rule = config.rule(RuleCode.valueOf(row.code()));
            RuleParams params = config.parseParams(rule, row.params(), "params.");
            active.add(new ActiveRule(rule, new RuleDefinition(rule.code(), row.name(), row.enabled(), row.weight(),
                    row.params(), params, 1, Instant.EPOCH, "system")));
        }
        return active;
    }

    public static EngineSettings settings() {
        Matcher m = Pattern.compile("'engine',\\s*'(\\{[^']*\\})'").matcher(read("V3__engine_setting.sql"));
        if (!m.find()) {
            throw new IllegalStateException("No engine row in V3");
        }
        try {
            return MAPPER.readValue(m.group(1), EngineSettings.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(String file) {
        try {
            return Files.readString(MIGRATIONS.resolve(file));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
