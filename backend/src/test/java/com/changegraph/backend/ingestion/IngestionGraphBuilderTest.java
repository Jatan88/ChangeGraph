package com.changegraph.backend.ingestion;

import com.changegraph.backend.github.GitHubModels;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.parser.JavaSourceParser;
import com.changegraph.backend.parser.PythonSourceParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionGraphBuilderTest {

    @Test
    void repeatedIngestionDoesNotDuplicateFunctionsAndOnlyMarksOverlappingChanges() {
        String source = """
                package payment;
                class Pay {
                  void retryPayment() {
                    recordLedger();
                  }
                  void recordLedger() {
                  }
                }
                """;
        JavaSourceParser parser = new JavaSourceParser();
        JavaSourceParser.ParsedFile parsed = parser.parse("src/Pay.java", source);
        int changedLine = parsed.functions().stream()
                .filter(function -> function.name().equals("retryPayment"))
                .findFirst()
                .orElseThrow()
                .startLine();
        String patch = "@@ -" + changedLine + ",1 +" + changedLine + ",1 @@\n- old\n+ new\n";
        GitHubModels.CommitFile file = new GitHubModels.CommitFile("src/Pay.java", null, "modified", patch, 1, 1);
        GitHubModels.CommitDetail commit = new GitHubModels.CommitDetail("abc1234", "change", "2026-01-01T00:00:00Z", List.of("parent"), List.of(file));
        GitHubModels.Repository repository = new GitHubModels.Repository(1, "acme", "payments", "main", "https://github.com/acme/payments", true);
        IngestionGraphBuilder.Input input = new IngestionGraphBuilder.Input(
                repository, commit, Map.of("src/Pay.java", source), Map.of("src/Pay.java", source), 10);

        IngestionGraphBuilder.Built first = new IngestionGraphBuilder().build(input);
        IngestionGraphBuilder.Built second = new IngestionGraphBuilder().build(input);
        first.graph().mergeAll(second.graph());

        assertEquals(2, first.graph().nodesOf(com.changegraph.backend.graph.NodeType.FUNCTION).size());
        long changed = first.graph().edgesOf(EdgeType.CHANGES_FUNCTION).stream()
                .filter(edge -> "MODIFIED".equals(edge.property("changeType")))
                .count();
        assertEquals(1, changed);
        assertTrue(first.graph().edgesOf(EdgeType.CALLS).stream().anyMatch(edge -> edge.getTargetId().contains("recordLedger")));
    }

    @Test
    void pythonChangeMarksTheOverlappingFunction() {
        String source = """
                class Pay:
                    def retry_payment(self, payment_id):
                        record_ledger(payment_id)

                def record_ledger(payment_id):
                    return payment_id
                """;
        JavaSourceParser.ParsedFile parsed = new PythonSourceParser().parse("src/pay.py", source);
        int changedLine = parsed.functions().stream()
                .filter(function -> function.name().equals("retry_payment"))
                .findFirst()
                .orElseThrow()
                .startLine();
        String patch = "@@ -" + changedLine + ",1 +" + changedLine + ",1 @@\n- old\n+ new\n";
        GitHubModels.CommitFile file = new GitHubModels.CommitFile("src/pay.py", null, "modified", patch, 1, 1);
        GitHubModels.CommitDetail commit = new GitHubModels.CommitDetail("abc1234", "change", "2026-01-01T00:00:00Z", List.of("parent"), List.of(file));
        GitHubModels.Repository repository = new GitHubModels.Repository(1, "acme", "payments", "main", "https://github.com/acme/payments", true);
        IngestionGraphBuilder.Built built = new IngestionGraphBuilder().build(new IngestionGraphBuilder.Input(
                repository, commit, java.util.Map.of("src/pay.py", source), java.util.Map.of("src/pay.py", source), 10));

        assertEquals(1, built.graph().edgesOf(EdgeType.CHANGES_FUNCTION).size());
        assertTrue(built.graph().edgesOf(EdgeType.CALLS).stream().anyMatch(edge -> edge.getTargetId().contains("record_ledger")));
    }
}
