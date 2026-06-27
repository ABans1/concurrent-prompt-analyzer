package com.example.concurrentpromptanalyzer;

import com.example.concurrentpromptanalyzer.config.AnalyzerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AnalyzerProperties.class)
public class ConcurrentPromptAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConcurrentPromptAnalyzerApplication.class, args);
    }
}
