package com.changegraph.backend.analysis;

import com.changegraph.backend.project.Project;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AnalysisSummaryTest {

    @Test
    void summaryKeepsKindAndStatus() {
        AnalysisRecord record = new AnalysisRecord(
                UUID.randomUUID(), mock(Project.class), "ff10519", null, Instant.parse("2026-10-17T16:00:00Z"));
        record.setKind("AGENT");
        record.setStatus("DETERMINISTIC_FALLBACK");
        record.setRiskLevel("LOW");
        record.setRiskScore(0);
        record.setCompletedAt(Instant.parse("2026-10-17T16:00:01Z"));

        AnalysisSummary summary = AnalysisSummary.from(record);

        assertEquals("AGENT", summary.kind());
        assertEquals("DETERMINISTIC_FALLBACK", summary.status());
        assertEquals("ff10519", summary.commitSha());
        assertEquals(0, summary.riskScore());
    }

    @Test
    void olderRowsWithoutKindOrStatusAreCompletedGraphAnalyses() {
        AnalysisRecord record = new AnalysisRecord(
                UUID.randomUUID(), mock(Project.class), "abc1234", null, Instant.parse("2026-10-17T16:00:00Z"));

        AnalysisSummary summary = AnalysisSummary.from(record);

        assertEquals("GRAPH", summary.kind());
        assertEquals("COMPLETED", summary.status());
    }
}
