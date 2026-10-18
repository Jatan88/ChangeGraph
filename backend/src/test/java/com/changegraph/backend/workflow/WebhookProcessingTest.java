package com.changegraph.backend.workflow;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookProcessingTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void signatureMatchesTheRawBodyAndRejectsADifferentBody() throws Exception {
        byte[] body = "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);
        String header = "sha256=" + hex(mac("test-secret", body));

        assertTrue(WebhookSignature.matches("test-secret", body, header));
        assertFalse(WebhookSignature.matches("test-secret", "{\"ref\":\"refs/heads/dev\"}".getBytes(StandardCharsets.UTF_8), header));
        assertFalse(WebhookSignature.matches("test-secret", body, "sha1=abcd"));
        assertFalse(WebhookSignature.matches("", body, header));
    }

    @Test
    void pushOnTheSelectedBranchIsAcceptedAndOtherBranchesAreIgnored() throws Exception {
        JsonNode payload = json.readTree("""
                {"ref":"refs/heads/develop","after":"abc1234","repository":{"id":9,"private":false,"visibility":"public"},
                 "commits":[{"message":"Fix checkout"}]}
                """);

        WebhookPayloads.Decision selected = WebhookPayloads.interpret("push", payload, "develop");
        WebhookPayloads.Decision other = WebhookPayloads.interpret("push", payload, "main");

        assertEquals("ACCEPT", selected.disposition());
        assertEquals(WorkflowKind.PUSH, selected.accepted().kind());
        assertEquals("develop", selected.accepted().sourceBranch());
        assertEquals("develop", selected.accepted().targetBranch());
        assertEquals("abc1234", selected.accepted().commitSha());
        assertEquals("IGNORE", other.disposition());
        assertEquals("push is on develop, selected branch is main", other.reason());
        assertNull(other.accepted());
    }

    @Test
    void pullRequestKeepsItsOwnBaseBranch() throws Exception {
        JsonNode payload = json.readTree("""
                {"action":"opened","repository":{"id":9,"private":false,"visibility":"public"},
                 "pull_request":{"number":14,"title":"Ship release",
                   "head":{"ref":"feature/pay","sha":"abc1234"},
                   "base":{"ref":"release","sha":"def5678"}}}
                """);

        WebhookPayloads.Decision decision = WebhookPayloads.interpret("pull_request", payload, "main");

        assertEquals("ACCEPT", decision.disposition());
        assertEquals(WorkflowKind.PULL_REQUEST, decision.accepted().kind());
        assertEquals("feature/pay", decision.accepted().sourceBranch());
        assertEquals("release", decision.accepted().targetBranch());
        assertEquals(14, decision.accepted().pullRequestNumber());
    }

    @Test
    void privateRepositoryAndClosedPullRequestAreIgnored() throws Exception {
        JsonNode privateRepo = json.readTree("""
                {"ref":"refs/heads/main","after":"abc1234","repository":{"id":9,"private":true,"visibility":"private"}}
                """);
        JsonNode closed = json.readTree("""
                {"action":"closed","repository":{"id":9,"private":false},"pull_request":{"number":1,"title":"x",
                  "head":{"ref":"a","sha":"abc1234"},"base":{"ref":"main","sha":"def5678"}}}
                """);

        assertEquals("IGNORE", WebhookPayloads.interpret("push", privateRepo, "main").disposition());
        assertEquals("IGNORE", WebhookPayloads.interpret("pull_request", closed, "main").disposition());
    }

    private static byte[] mac(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(body);
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
