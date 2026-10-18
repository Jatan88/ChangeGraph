package com.changegraph.backend.workflow;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
public class GitHubWebhookController {

    private final GitHubWebhookService webhooks;

    public GitHubWebhookController(GitHubWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/api/github/webhooks")
    public ResponseEntity<Map<String, Object>> receive(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(1_000_001);
        GitHubWebhookService.Result result = webhooks.receive(
                request.getHeader("X-GitHub-Event"),
                request.getHeader("X-GitHub-Delivery"),
                request.getHeader("X-Hub-Signature-256"),
                body);
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
