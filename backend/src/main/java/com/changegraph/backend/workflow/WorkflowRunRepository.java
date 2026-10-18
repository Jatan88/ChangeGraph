package com.changegraph.backend.workflow;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    Optional<WorkflowRun> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<WorkflowRun> findByProjectIdAndDeliveryId(UUID projectId, String deliveryId);

    Optional<WorkflowRun> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey);

    Page<WorkflowRun> findByProjectId(UUID projectId, Pageable pageable);

    void deleteByProjectId(UUID projectId);
}
