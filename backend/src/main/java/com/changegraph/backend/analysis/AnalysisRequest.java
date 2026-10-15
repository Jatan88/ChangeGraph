package com.changegraph.backend.analysis;

public record AnalysisRequest(String commitSha, Integer pullRequestNumber) {
}
