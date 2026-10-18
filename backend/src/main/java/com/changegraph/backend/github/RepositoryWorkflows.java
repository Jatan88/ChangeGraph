package com.changegraph.backend.github;

import java.util.UUID;

public interface RepositoryWorkflows {

    UUID syncBranch(UUID projectId, String branch, String commitSha);
}
