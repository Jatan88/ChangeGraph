package com.changegraph.backend.workflow;

import com.changegraph.backend.project.ProjectRepositoryLink;
import com.changegraph.backend.project.ProjectRepositoryLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class GitHubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookService.class);

    private final String secret;
    private final ProjectRepositoryLinkRepository links;
    private final WorkflowService workflows;
    private final JsonMapper jsonMapper;

    public GitHubWebhookService(
            @Value("${changegraph.github.webhook-secret:${GITHUB_WEBHOOK_SECRET:}}") String secret,
            ProjectRepositoryLinkRepository links,
            WorkflowService workflows,
            JsonMapper jsonMapper) {
        this.secret = secret == null ? "" : secret.trim();
        this.links = links;
        this.workflows = workflows;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public Result receive(String event, String deliveryId, String signature, byte[] body) {
        log.info("GitHub webhook arrived: event {}, delivery {}", eventName(event), deliveryLabel(deliveryId));
        if (secret.isBlank()) {
            log.warn("Rejected GitHub webhook {} because GITHUB_WEBHOOK_SECRET is empty", deliveryLabel(deliveryId));
            return new Result(HttpStatus.SERVICE_UNAVAILABLE, Map.of("status", "disabled"));
        }
        if (body == null || body.length > 1_000_000) {
            log.warn("Rejected GitHub webhook {} because the body is empty or larger than 1 MB", deliveryLabel(deliveryId));
            return new Result(HttpStatus.PAYLOAD_TOO_LARGE, Map.of("status", "rejected"));
        }
        if (!WebhookSignature.matches(secret, body, signature)) {
            log.warn("Rejected GitHub webhook {} because the signature did not match", deliveryLabel(deliveryId));
            return new Result(HttpStatus.UNAUTHORIZED, Map.of("status", "rejected"));
        }
        if ("ping".equals(event)) {
            log.info("Accepted GitHub webhook ping {}", deliveryLabel(deliveryId));
            return new Result(HttpStatus.OK, Map.of("status", "ok"));
        }
        if (deliveryId == null || deliveryId.isBlank() || deliveryId.length() > 80) {
            log.warn("Rejected GitHub webhook because the delivery id is missing or too long");
            return new Result(HttpStatus.BAD_REQUEST, Map.of("status", "rejected"));
        }
        JsonNode payload;
        try {
            payload = jsonMapper.readTree(body);
        } catch (RuntimeException ex) {
            log.warn("Rejected GitHub webhook {} because the body is not JSON", deliveryLabel(deliveryId));
            return new Result(HttpStatus.BAD_REQUEST, Map.of("status", "rejected"));
        }
        long repositoryId = payload.path("repository").path("id").asLong(0);
        if (repositoryId <= 0) {
            log.warn("Rejected GitHub webhook {} because the repository id is missing", deliveryLabel(deliveryId));
            return new Result(HttpStatus.BAD_REQUEST, Map.of("status", "rejected"));
        }
        List<ProjectRepositoryLink> matches = links.findByGithubRepositoryId(repositoryId);
        if (matches.isEmpty()) {
            log.info("Ignored GitHub webhook {} for repository {} because no project is linked to it", deliveryLabel(deliveryId), repositoryId);
            return new Result(HttpStatus.ACCEPTED, Map.of("status", "ignored"));
        }
        List<String> runIds = new ArrayList<>();
        boolean accepted = false;
        boolean rejected = false;
        for (ProjectRepositoryLink link : matches) {
            WebhookPayloads.Decision decision = WebhookPayloads.interpret(event, payload, link.branchForSync());
            log.info("GitHub webhook {} repository {} project {} branch {}: {}",
                    deliveryLabel(deliveryId),
                    repositoryId,
                    link.getProject().getId(),
                    link.branchForSync(),
                    decision.reason());
            if ("REJECT".equals(decision.disposition())) {
                rejected = true;
                continue;
            }
            if (!"ACCEPT".equals(decision.disposition()) || decision.accepted() == null) {
                continue;
            }
            accepted = true;
            for (java.util.UUID runId : workflows.acceptWebhook(deliveryId, link, decision.accepted())) {
                runIds.add(runId.toString());
            }
        }
        if (accepted) {
            log.info("Accepted GitHub webhook {} and queued runs {}", deliveryLabel(deliveryId), runIds);
            return new Result(HttpStatus.ACCEPTED, Map.of("status", "accepted", "runs", runIds));
        }
        if (rejected) {
            log.warn("Rejected GitHub webhook {} after checking linked projects", deliveryLabel(deliveryId));
            return new Result(HttpStatus.BAD_REQUEST, Map.of("status", "rejected"));
        }
        log.info("Ignored GitHub webhook {} after checking linked projects", deliveryLabel(deliveryId));
        return new Result(HttpStatus.ACCEPTED, Map.of("status", "ignored"));
    }

    private static String eventName(String event) {
        return event == null || event.isBlank() ? "unknown" : event;
    }

    private static String deliveryLabel(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return "without a delivery id";
        }
        return deliveryId.length() > 80 ? deliveryId.substring(0, 80) : deliveryId;
    }

    public record Result(HttpStatus status, Map<String, Object> body) {
    }
}
