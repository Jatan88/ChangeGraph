package com.changegraph.backend.ingestion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

    Page<IngestionJob> findByProjectId(UUID projectId, Pageable pageable);

    Optional<IngestionJob> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<IngestionJob> findFirstByProjectIdOrderByCreatedAtDesc(UUID projectId);

    boolean existsByProjectIdAndStatusIn(UUID projectId, Collection<JobStatus> statuses);

    void deleteByProjectId(UUID projectId);
}
