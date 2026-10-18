package com.changegraph.backend.github;

import com.changegraph.backend.project.ProjectResponse;

import java.util.UUID;

public record BranchSelectionResponse(ProjectResponse.RepositorySummary repository, UUID workflowId) {
}
