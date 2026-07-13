package dev.tahir.blueprint.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI blueprintOpenApi(@Value("${spring.application.version:0.0.0}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("Blueprint API")
                        .version(version)
                        .description("Reference service for the production infrastructure blueprint")
                        .license(new License().name("MIT")));
    }
}
