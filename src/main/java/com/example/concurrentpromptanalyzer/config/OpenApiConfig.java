package com.example.concurrentpromptanalyzer.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata shown in the Swagger UI at {@code /swagger-ui.html}. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI analyzerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Concurrent Prompt Analyzer API")
                        .version("v1")
                        .description("Submit a batch of prompts for asynchronous, concurrent inference "
                                + "and poll for aggregated results. Includes the mock rate-limited "
                                + "inference endpoint used by the workers.")
                        .license(new License().name("Apache-2.0")));
    }
}
