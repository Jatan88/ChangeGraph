package com.changegraph.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectRepositoryLinkRepository extends JpaRepository<ProjectRepositoryLink, UUID> {

    Optional<ProjectRepositoryLink> findByProjectId(UUID projectId);

    void deleteByProjectId(UUID projectId);
}
