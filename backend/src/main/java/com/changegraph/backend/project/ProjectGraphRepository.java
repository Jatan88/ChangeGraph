package com.changegraph.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectGraphRepository extends JpaRepository<ProjectGraph, UUID> {

    Optional<ProjectGraph> findByProjectId(UUID projectId);
}
