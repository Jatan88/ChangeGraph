package com.changegraph.backend.workflow;

import com.changegraph.backend.agent.LlmClient;
import com.changegraph.backend.github.GitHubClient;
import com.changegraph.backend.github.GitHubModels;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeveloperAgentTest {

    private final GitHubClient github = mock(GitHubClient.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final DeveloperAgent agent = new DeveloperAgent(llm, github, JsonMapper.builder().build());
    private final UUID runId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void unsureFixDoesNotOpenAPullRequest() {
        when(llm.available()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("{\"confident\":false,\"reason\":\"The safe edit is not determined.\",\"changes\":[]}");

        FixResult result = agent.propose(request(review(true)));

        assertFalse(result.pullRequestOpened());
        assertTrue(result.explanation().contains("No pull request was opened"));
        verify(github, never()).createBranch(anyString(), anyString(), anyString(), anyString());
        verify(github, never()).openPullRequest(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void confidentFixOpensASeparatePullRequestAndNeverUsesTheDefaultBranch() {
        when(llm.available()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("""
                {"confident":true,"reason":"Update the reviewed file.","changes":[{"path":"src/Pay.java","content":"class Pay { void charge() {} }"}]}
                """);
        when(github.openPullRequest(eq("acme"), eq("payments"), anyString(), anyString(), eq("develop"), anyString()))
                .thenReturn(new GitHubModels.CreatedPullRequest(22, "https://github.com/acme/payments/pull/22", "changegraph/fix", "develop"));

        FixResult result = agent.propose(request(review(true)));

        assertTrue(result.pullRequestOpened());
        assertEquals(22, result.pullRequestNumber());
        assertTrue(result.branch().startsWith("changegraph/fix-"));
        assertFalse(result.branch().equals("main"));
        verify(github).createBranch(eq("acme"), eq("payments"), argThat(branch -> branch.startsWith("changegraph/fix-")), eq("abc1234def"));
        verify(github).updateFile(eq("acme"), eq("payments"), argThat(branch -> branch.startsWith("changegraph/fix-")), eq("src/Pay.java"), anyString(), anyString());
        verify(github, never()).createBranch(eq("acme"), eq("payments"), eq("main"), anyString());
    }

    @Test
    void unusableModelResponseRestoresARemovedGuardAndOpensAPullRequest() {
        when(llm.available()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("not json");
        when(github.openPullRequest(eq("acme"), eq("payments"), anyString(), anyString(), eq("develop"), anyString()))
                .thenReturn(new GitHubModels.CreatedPullRequest(23, "https://github.com/acme/payments/pull/23", "changegraph/fix", "develop"));
        String current = """
                retryPayment(paymentId: string) {
                  const entry = { id: paymentId };
                  ledgerService.recordLedgerEntry(entry);
                  return entry;
                }
                """;
        String patch = """
                @@ -2,2 +2,2 @@
                   const entry = { id: paymentId };
                -  if (priorRetry) {
                -    return priorRetry;
                -  }
                   ledgerService.recordLedgerEntry(entry);
                """;

        FixResult result = agent.propose(new DeveloperAgent.FixRequest(
                runId,
                1,
                "acme",
                "payments",
                "abc1234def",
                "main",
                "develop",
                "feature/pay",
                "develop",
                true,
                Set.of("server/payment-service.ts"),
                Map.of("server/payment-service.ts", current),
                Map.of("server/payment-service.ts", patch),
                review(true)));

        assertTrue(result.pullRequestOpened());
        assertTrue(result.explanation().contains("removed guard"));
        verify(github).updateFile(
                eq("acme"),
                eq("payments"),
                argThat(branch -> branch.startsWith("changegraph/fix-")),
                eq("server/payment-service.ts"),
                anyString(),
                argThat(body -> body.contains("if (priorRetry)") && body.indexOf("if (priorRetry)") < body.indexOf("recordLedgerEntry")));
    }

    @Test
    void findAndReplaceCanAddATestAndOpenAPullRequest() {
        when(llm.available()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("""
                {"confident":true,"reason":"Restore the call and add a test.","changes":[
                  {"path":"src/Pay.java","find":"int x = 1;","replace":"if (paid) return; int x = 1;"},
                  {"path":"src/PayTest.java","content":"class PayTest { void coversCharge() { new Pay().charge(); } }"}
                ]}
                """);
        when(github.openPullRequest(eq("acme"), eq("payments"), anyString(), anyString(), eq("develop"), anyString()))
                .thenReturn(new GitHubModels.CreatedPullRequest(24, "https://github.com/acme/payments/pull/24", "changegraph/fix", "develop"));

        FixResult result = agent.propose(new DeveloperAgent.FixRequest(
                runId,
                1,
                "acme",
                "payments",
                "abc1234def",
                "main",
                "develop",
                "feature/pay",
                "develop",
                true,
                Set.of("src/Pay.java", "src/PayTest.java"),
                Map.of("src/Pay.java", "class Pay { void charge() { int x = 1; } }"),
                Map.of(),
                review(true)));

        assertTrue(result.pullRequestOpened());
        verify(github).updateFile(eq("acme"), eq("payments"), anyString(), eq("src/Pay.java"), anyString(), argThat(body -> body.contains("if (paid) return;")));
        verify(github).updateFile(eq("acme"), eq("payments"), anyString(), eq("src/PayTest.java"), anyString(), argThat(body -> body.contains("coversCharge")));
    }

    @Test
    void unparseableJavaIsNotOpenedAsAPullRequest() {
        when(llm.available()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("""
                {"confident":true,"reason":"Rewrite the file.","changes":[{"path":"src/Pay.java","content":"class Pay { void charge( "}]}
                """);

        FixResult result = agent.propose(request(review(true)));

        assertFalse(result.pullRequestOpened());
        verify(github, never()).createBranch(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void changeOutsideTheReviewedFilesIsRejected() {
        when(llm.available()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("""
                {"confident":true,"reason":"Edit another file.","changes":[{"path":"src/Other.java","content":"class Other {}"}]}
                """);

        FixResult result = agent.propose(request(review(true)));

        assertFalse(result.pullRequestOpened());
        verify(github, never()).createBranch(anyString(), anyString(), anyString(), anyString());
    }

    private DeveloperAgent.FixRequest request(CodeReview review) {
        return new DeveloperAgent.FixRequest(
                runId,
                1,
                "acme",
                "payments",
                "abc1234def",
                "main",
                "develop",
                "feature/pay",
                "develop",
                true,
                Set.of("src/Pay.java"),
                Map.of("src/Pay.java", "class Pay { void charge() { int x = 1; } }"),
                Map.of(),
                review);
    }

    private static CodeReview review(boolean actionable) {
        return new CodeReview(
                "Review",
                List.of(new CodeReview.ReviewFinding(
                        "f1",
                        "MEDIUM",
                        "TESTS",
                        "No direct test is linked",
                        "Missing coverage",
                        List.of("charge(int)"),
                        List.of("src/Pay.java"),
                        "Add a test.",
                        actionable)),
                List.of(),
                List.of());
    }
}
