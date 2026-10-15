package com.changegraph.backend.project;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.user.UserAccount;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ProjectAccess {

    private final ProjectRepository projects;
    private final ProjectGraphRepository graphs;
    private final ProjectRepositoryLinkRepository repositories;

    public ProjectAccess(ProjectRepository projects, ProjectGraphRepository graphs, ProjectRepositoryLinkRepository repositories) {
        this.projects = projects;
        this.graphs = graphs;
        this.repositories = repositories;
    }

    public Project requireProject(UserAccount owner, UUID projectId) {
        return projects.findByIdAndOwnerId(projectId, owner.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Project not found."));
    }

    public ProjectGraph requireGraph(UUID projectId) {
        return graphs.findByProjectId(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_READY", "This project does not have a graph mapping."));
    }

    public ProjectRepositoryLink requireRepository(UUID projectId) {
        return repositories.findByProjectId(projectId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "REPOSITORY_NOT_LINKED",
                        "Connect a public GitHub repository before continuing."));
    }
}
