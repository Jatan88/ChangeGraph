package com.changegraph.backend.project;

import com.changegraph.backend.analysis.AnalysisRepository;
import com.changegraph.backend.ingestion.IngestionJobRepository;
import com.changegraph.backend.workflow.WorkflowEventRepository;
import com.changegraph.backend.workflow.WorkflowRunRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class ProjectCleanup {

    private final AnalysisRepository analyses;
    private final IngestionJobRepository ingestions;
    private final ProjectRepositoryLinkRepository repositories;
    private final ProjectGraphRepository graphs;
    private final ProjectRepository projects;
    private final WorkflowEventRepository workflowEvents;
    private final WorkflowRunRepository workflowRuns;

    public ProjectCleanup(
            AnalysisRepository analyses,
            IngestionJobRepository ingestions,
            ProjectRepositoryLinkRepository repositories,
            ProjectGraphRepository graphs,
            ProjectRepository projects,
            WorkflowEventRepository workflowEvents,
            WorkflowRunRepository workflowRuns) {
        this.analyses = analyses;
        this.ingestions = ingestions;
        this.repositories = repositories;
        this.graphs = graphs;
        this.projects = projects;
        this.workflowEvents = workflowEvents;
        this.workflowRuns = workflowRuns;
    }

    @Transactional
    public void deleteRows(UUID projectId) {
        workflowEvents.deleteByProjectId(projectId);
        workflowRuns.deleteByProjectId(projectId);
        analyses.deleteByProjectId(projectId);
        ingestions.deleteByProjectId(projectId);
        repositories.deleteByProjectId(projectId);
        graphs.findByProjectId(projectId).ifPresent(graphs::delete);
        projects.deleteById(projectId);
    }
}
