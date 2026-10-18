package com.changegraph.backend.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface WorkflowEventRepository extends JpaRepository<WorkflowEvent, UUID> {

    List<WorkflowEvent> findByRunIdOrderBySequenceNumberAsc(UUID runId);

    @Query("select coalesce(max(event.sequenceNumber), 0) from WorkflowEvent event where event.runId = :runId")
    int maxSequence(UUID runId);

    void deleteByProjectId(UUID projectId);
}
