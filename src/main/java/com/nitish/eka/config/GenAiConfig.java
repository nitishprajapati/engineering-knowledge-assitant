package com.nitish.eka.config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class GenAiConfig {

    @Bean
    public Client googleGenAiClient(Environment env) {
        // Uses Spring Boot's relaxed binder—reads the exact resolved value from application.yml!
        String apiKey = env.getRequiredProperty("spring.ai.google.genai.api-key").trim();

        HttpOptions httpOptions = HttpOptions.builder()
                .timeout(20_000) // 20s max timeout (kills the 2-minute per-attempt hang!). (Max 20s per attempt)
                .retryOptions(HttpRetryOptions.builder()
                        .attempts(2) // 1 initial try + 1 quick retry (worst-case wait ~4s, never 8 mins!)
                        .initialDelay(2.0) // Wait 2 seconds before the single retry
                        .maxDelay(3.0)
                        .build())
                .build();

        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(httpOptions)
                .build();
    }
}
