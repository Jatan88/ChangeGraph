package com.changegraph.backend.workflow;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class WorkflowRunner {

    private final WorkflowEngine engine;

    public WorkflowRunner(WorkflowEngine engine) {
        this.engine = engine;
    }

    @Async("workflowExecutor")
    public void run(UUID runId) {
        engine.process(runId);
    }
}
