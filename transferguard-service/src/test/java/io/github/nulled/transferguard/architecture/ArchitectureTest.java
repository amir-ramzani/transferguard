package io.github.nulled.transferguard.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.github.nulled.transferguard.rules.Rule;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.RestController;

/** Module boundaries of the modular monolith (ADR 005). A later split into services stays possible. */
@AnalyzeClasses(packages = "io.github.nulled.transferguard", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Rules declare what they need; the features module fulfils it. Never the other way round. */
    @ArchTest
    static final ArchRule rulesNeverImportFeatures = noClasses()
            .that().resideInAPackage("..transferguard.rules..")
            .should().dependOnClassesThat().resideInAPackage("..transferguard.features..");

    /** A rule does no I/O: no Redis, JDBC, HTTP or Spring data access. */
    @ArchTest
    static final ArchRule rulesDoNoIo = classes()
            .that().implement(Rule.class)
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "jakarta.validation..", "org.springframework.stereotype..",
                    "..transferguard.rules..", "..transferguard.common..");

    @ArchTest
    static final ArchRule controllersNeverTouchRepositories = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAnnotatedWith(Repository.class);

    /** The public DTOs depend on nothing but the shared helpers. */
    @ArchTest
    static final ArchRule screeningApiIsALeaf = classes()
            .that().resideInAPackage("..transferguard.screening.api..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..", "jakarta.validation..", "com.fasterxml.jackson.annotation..",
                    "..transferguard.screening.api..", "..transferguard.common..");

    @ArchTest
    static final ArchRule onlyFeaturesAndScreeningTalkToRedis = noClasses()
            .that().resideOutsideOfPackages("..transferguard.features..", "..transferguard.screening..",
                    "..transferguard.config..")
            .should().dependOnClassesThat().resideInAPackage("io.lettuce..");
}
