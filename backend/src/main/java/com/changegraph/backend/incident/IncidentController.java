package com.changegraph.backend.incident;

import com.changegraph.backend.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/incidents")
public class IncidentController {

    private final IncidentService incidents;
    private final CurrentUser currentUser;

    public IncidentController(IncidentService incidents, CurrentUser currentUser) {
        this.incidents = incidents;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<IncidentResponses.IncidentSummary> list(@PathVariable UUID projectId) {
        return incidents.list(currentUser.require(), projectId);
    }

    @GetMapping("/{incidentId}")
    public IncidentResponses.IncidentDetail get(@PathVariable UUID projectId, @PathVariable String incidentId) {
        return incidents.get(currentUser.require(), projectId, incidentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentResponses.IncidentDetail create(
            @PathVariable UUID projectId,
            @Valid @RequestBody IncidentRequests.CreateIncident request) {
        return incidents.create(currentUser.require(), projectId, request);
    }

    @PatchMapping("/{incidentId}")
    public IncidentResponses.IncidentDetail update(
            @PathVariable UUID projectId,
            @PathVariable String incidentId,
            @Valid @RequestBody IncidentRequests.UpdateIncident request) {
        return incidents.update(currentUser.require(), projectId, incidentId, request);
    }

    @DeleteMapping("/{incidentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId, @PathVariable String incidentId) {
        incidents.delete(currentUser.require(), projectId, incidentId);
    }

    @PostMapping("/{incidentId}/evidence")
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentResponses.IncidentDetail addEvidence(
            @PathVariable UUID projectId,
            @PathVariable String incidentId,
            @Valid @RequestBody IncidentRequests.CreateEvidence request) {
        return incidents.addEvidence(currentUser.require(), projectId, incidentId, request);
    }

    @PatchMapping("/{incidentId}/evidence/{evidenceId}")
    public IncidentResponses.IncidentDetail updateEvidence(
            @PathVariable UUID projectId,
            @PathVariable String incidentId,
            @PathVariable String evidenceId,
            @Valid @RequestBody IncidentRequests.UpdateEvidence request) {
        return incidents.updateEvidence(currentUser.require(), projectId, incidentId, evidenceId, request);
    }

    @DeleteMapping("/{incidentId}/evidence/{evidenceId}")
    public IncidentResponses.IncidentDetail deleteEvidence(
            @PathVariable UUID projectId,
            @PathVariable String incidentId,
            @PathVariable String evidenceId) {
        return incidents.deleteEvidence(currentUser.require(), projectId, incidentId, evidenceId);
    }
}
