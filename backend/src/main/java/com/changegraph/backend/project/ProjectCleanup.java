package com.changegraph.backend.project;

import com.changegraph.backend.analysis.AnalysisRepository;
import com.changegraph.backend.ingestion.IngestionJobRepository;
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

    public ProjectCleanup(
            AnalysisRepository analyses,
            IngestionJobRepository ingestions,
            ProjectRepositoryLinkRepository repositories,
            ProjectGraphRepository graphs,
            ProjectRepository projects) {
        this.analyses = analyses;
        this.ingestions = ingestions;
        this.repositories = repositories;
        this.graphs = graphs;
        this.projects = projects;
    }

    @Transactional
    public void deleteRows(UUID projectId) {
        analyses.deleteByProjectId(projectId);
        ingestions.deleteByProjectId(projectId);
        repositories.deleteByProjectId(projectId);
        graphs.findByProjectId(projectId).ifPresent(graphs::delete);
        projects.deleteById(projectId);
    }
}
