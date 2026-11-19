package io.github.nulled.transferguard.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The code and the committed contract cannot drift: every operation in docs/openapi.yaml has exactly
 * one handler with the same method, path and scope, and no handler exists outside the contract.
 */
class OpenApiContractTest {

    private static final String PREFIX = "/api/v1";
    private static final Pattern SCOPE = Pattern.compile("hasAuthority\\('SCOPE_([^']+)'\\)");
    private static final java.util.Set<String> METHODS = java.util.Set.of("get", "put", "post", "patch", "delete");

    @Test
    void everyContractOperationIsImplementedWithItsScope() throws Exception {
        assertThat(implemented()).isEqualTo(contract());
    }

    @Test
    void contractHasNineteenOperations() {
        assertThat(contract()).hasSize(19);
    }

    /** "POST /screenings" → "transferguard/screening.write". */
    static Map<String, String> contract() {
        JsonNode spec = OpenApi.load();
        Map<String, String> ops = new TreeMap<>();
        spec.get("paths").properties().forEach(path -> path.getValue().properties().forEach(op -> {
            if (METHODS.contains(op.getKey())) {
                JsonNode scopes = op.getValue().at("/security/0/oauth2");
                ops.put(op.getKey().toUpperCase() + " " + path.getKey(), scopes.get(0).asString());
            }
        }));
        return ops;
    }

    static Map<String, String> implemented() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, String> ops = new TreeMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("io.github.nulled.transferguard")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String prefix = base == null || base.path().length == 0 ? "" : base.path()[0];
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = prefix + (mapping.path().length == 0 ? "" : mapping.path()[0]);
                assertThat(path).as(m.toString()).startsWith(PREFIX);
                PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
                assertThat(pre).as("@PreAuthorize on " + m).isNotNull();
                Matcher scope = SCOPE.matcher(pre.value());
                assertThat(scope.matches()).as("exactly one scope on " + m).isTrue();
                ops.put(mapping.method()[0].name() + " " + path.substring(PREFIX.length()), scope.group(1));
            }
        }
        return ops;
    }
}
