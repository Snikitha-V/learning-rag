package org.example.config;

import org.springframework.context.annotation.*;
import org.example.*;

@Configuration
public class AppConfig {

    @Bean
    public RetrievalService retrievalService() throws Exception {
        // If your RetrievalService has a complex constructor, adapt here.
        // This assumes you can create a default RetrievalService with no args or with Config constants
        return RetrievalService.createDefault(); // or new RetrievalService(...)
    }
}
