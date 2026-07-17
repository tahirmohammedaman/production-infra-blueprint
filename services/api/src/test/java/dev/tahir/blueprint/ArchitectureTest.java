package dev.tahir.blueprint;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import org.springframework.beans.factory.annotation.Autowired;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;

/**
 * Layering is enforced by a test rather than by review comments, because review comments
 * do not fail the build.
 */
@AnalyzeClasses(packages = "dev.tahir.blueprint", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layersAreRespected = Architectures.layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Api")
            .definedBy("dev.tahir.blueprint.api..")
            .layer("Domain")
            .definedBy("dev.tahir.blueprint.domain..")
            .layer("Observability")
            .definedBy("dev.tahir.blueprint.observability..")
            .whereLayer("Api")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Api", "Observability");

    @ArchTest
    static final ArchRule domainDoesNotDependOnWeb = noClasses()
            .that()
            .resideInAPackage("dev.tahir.blueprint.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
            .because("the domain must stay usable outside an HTTP request");

    /**
     * The projection is maintained by the worker over Kafka. If the API ever reaches into
     * the messaging layer to update the cache directly it has re-created the dual write the
     * outbox exists to prevent, so the boundary is asserted rather than trusted.
     */
    @ArchTest
    static final ArchRule cacheIsNotWrittenFromTheRequestPath = noClasses()
            .that()
            .resideInAPackage("dev.tahir.blueprint.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("dev.tahir.blueprint.outbox..")
            .because("controllers must go through the domain service, which owns the transaction");

    @ArchTest
    static final ArchRule noFieldInjection = noFields()
            .should()
            .beAnnotatedWith(Autowired.class)
            .because("constructor injection keeps dependencies explicit and objects testable");
}
