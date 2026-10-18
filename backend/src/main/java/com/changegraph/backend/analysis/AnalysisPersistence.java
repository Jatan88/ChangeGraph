package com.changegraph.backend.analysis;

import com.changegraph.backend.agent.AgentReport;
import com.changegraph.backend.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Component
public class AnalysisPersistence {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPersistence.class);

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
        record.setKind("GRAPH");
        record.setStatus("COMPLETED");
        record.setResultJson(jsonMapper.writeValueAsString(report));
        record.setCompletedAt(Instant.now());
        analyses.save(record);
        log.info("Stored graph analysis {} for project {} with status COMPLETED", report.id(), projectId);
    }

    @Transactional
    public void saveAgent(UUID projectId, UUID id, AgentReport report) {
        var project = projects.findById(projectId).orElseThrow();
        AnalysisRecord record = new AnalysisRecord(id, project, report.commitSha(), report.pullRequestNumber(), Instant.now());
        record.setRiskLevel(report.riskLevel());
        record.setRiskScore(report.riskScore());
        record.setKind("AGENT");
        record.setStatus(report.agentStatus());
        record.setResultJson(jsonMapper.writeValueAsString(report));
        record.setCompletedAt(Instant.now());
        analyses.save(record);
        log.info("Stored agent investigation {} for project {} with status {}", id, projectId, report.agentStatus());
    }
}
