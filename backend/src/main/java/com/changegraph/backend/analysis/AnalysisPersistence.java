package com.changegraph.backend.analysis;

import com.changegraph.backend.project.ProjectRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Component
public class AnalysisPersistence {

    private final AnalysisRepository analyses;
    private final ProjectRepository projects;
    private final JsonMapper jsonMapper;

    public AnalysisPersistence(AnalysisRepository analyses, ProjectRepository projects, JsonMapper jsonMapper) {
        this.analyses = analyses;
        this.projects = projects;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public void save(UUID projectId, AnalysisReport report) {
        var project = projects.findById(projectId).orElseThrow();
        AnalysisRecord record = new AnalysisRecord(report.id(), project, report.commitSha(), report.pullRequestNumber(), Instant.now());
        record.setRiskLevel(report.riskLevel());
        record.setRiskScore(report.riskScore());
        record.setResultJson(jsonMapper.writeValueAsString(report));
        record.setCompletedAt(Instant.now());
        analyses.save(record);
    }
}
