package com.changegraph.backend.ingestion;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class IngestionRunner {

    private final IngestionService ingestionService;

    public IngestionRunner(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Async("ingestionExecutor")
    public void run(UUID jobId) {
        ingestionService.execute(jobId);
    }
}
