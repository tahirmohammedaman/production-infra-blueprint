package dev.tahir.blueprint.platform;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import dev.tahir.blueprint.platform.observability.CorrelationIdFilter;
import dev.tahir.blueprint.platform.observability.OperationalSpanFilter;

/**
 * Registered through {@code AutoConfiguration.imports} rather than component scanning.
 *
 * <p>Component scanning a shared library means every service has to remember to add the
 * package to its {@code @SpringBootApplication} scan, and silently loses the behaviour when
 * someone forgets. Auto-configuration makes the dependency itself sufficient, and
 * {@code @ConditionalOnMissingBean} still lets a service override any of it.
 */
@AutoConfiguration
public class PlatformAutoConfiguration {

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    /**
     * Spring Boot's tracing auto-configuration applies every {@code SpanExportingPredicate}
     * bean before a span leaves the process, so declaring it is the whole integration.
     */
    @Bean
    @ConditionalOnMissingBean
    public OperationalSpanFilter operationalSpanFilter() {
        return new OperationalSpanFilter();
    }

}
