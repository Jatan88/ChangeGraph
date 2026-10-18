package com.changegraph.backend.analysis;

import com.changegraph.backend.auth.CurrentUser;
import com.changegraph.backend.common.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/analysis")
public class AnalysisController {

    private final ChangeAnalysisService analyses;
    private final CurrentUser currentUser;

    public AnalysisController(ChangeAnalysisService analyses, CurrentUser currentUser) {
        this.analyses = analyses;
        this.currentUser = currentUser;
    }

    @GetMapping
    public PageResponse<AnalysisSummary> list(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return analyses.list(currentUser.require(), projectId, page, size);
    }

    @PostMapping
    public AnalysisReport create(@PathVariable UUID projectId, @RequestBody(required = false) AnalysisRequest request) {
        return analyses.analyze(currentUser.require(), projectId, request);
    }

    @GetMapping("/{analysisId}")
    public AnalysisReport get(@PathVariable UUID projectId, @PathVariable UUID analysisId) {
        return analyses.get(currentUser.require(), projectId, analysisId);
    }
}
