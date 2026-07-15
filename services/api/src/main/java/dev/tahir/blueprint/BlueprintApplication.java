package dev.tahir.blueprint;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BlueprintApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlueprintApplication.class, args);
    }
}
