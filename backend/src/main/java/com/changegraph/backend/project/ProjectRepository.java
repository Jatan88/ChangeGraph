package com.changegraph.backend.project;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Page<Project> findByOwnerId(UUID ownerId, Pageable pageable);

    Optional<Project> findByIdAndOwnerId(UUID id, UUID ownerId);
}
