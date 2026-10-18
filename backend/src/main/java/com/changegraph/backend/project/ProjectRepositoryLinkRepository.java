package com.changegraph.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectRepositoryLinkRepository extends JpaRepository<ProjectRepositoryLink, UUID> {

    Optional<ProjectRepositoryLink> findByProjectId(UUID projectId);

    java.util.List<ProjectRepositoryLink> findByGithubRepositoryId(long githubRepositoryId);

    void deleteByProjectId(UUID projectId);
}
