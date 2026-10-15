package com.changegraph.backend.analysis;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AnalysisRepository extends JpaRepository<AnalysisRecord, UUID> {

    Optional<AnalysisRecord> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<AnalysisRecord> findFirstByProjectIdOrderByCreatedAtDesc(UUID projectId);

    Page<AnalysisRecord> findByProjectId(UUID projectId, Pageable pageable);

    void deleteByProjectId(UUID projectId);
}
