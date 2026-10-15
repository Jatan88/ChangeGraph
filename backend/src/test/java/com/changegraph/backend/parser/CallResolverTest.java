package com.changegraph.backend.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallResolverTest {

    private final JavaSourceParser parser = new JavaSourceParser();

    @Test
    void resolvesUniqueCallAndLeavesAmbiguousOverloadUnresolved() {
        JavaSourceParser.ParsedFile unique = parser.parse("src/A.java", """
                class A {
                  void start() { finish(1); }
                  void finish(int value) {}
                }
                """);
        CallResolver.ResolvedFile resolved = CallResolver.resolve(List.of(unique)).get(0);
        CallResolver.ResolvedFunction start = resolved.functions().stream()
                .filter(function -> function.function().name().equals("start"))
                .findFirst()
                .orElseThrow();
        assertTrue(start.calls().get(0).resolved());

        JavaSourceParser.ParsedFile ambiguous = parser.parse("src/B.java", """
                class B {
                  void start() { finish(1); }
                  void finish(int value) {}
                  void finish(long value) {}
                }
                """);
        CallResolver.ResolvedFunction ambiguousStart = CallResolver.resolve(List.of(ambiguous)).get(0).functions().stream()
                .filter(function -> function.function().name().equals("start"))
                .findFirst()
                .orElseThrow();
        assertFalse(ambiguousStart.calls().get(0).resolved());
        assertEqualsUnresolved(1, ambiguousStart.unresolvedCallCount());
    }

    @Test
    void resolvesAJavaScriptCallToTheSingleJavaFunctionWithTheSameName() {
        JavaSourceParser.ParsedFile script = SourceParsers.parse("src/pay.js", """
                function start() { finish(1) }
                """);
        JavaSourceParser.ParsedFile java = parser.parse("src/A.java", """
                class A {
                  void finish(int value) {}
                }
                """);
        CallResolver.ResolvedFunction start = CallResolver.resolve(List.of(script, java)).stream()
                .flatMap(file -> file.functions().stream())
                .filter(function -> function.function().name().equals("start"))
                .findFirst()
                .orElseThrow();
        assertTrue(start.calls().get(0).resolved());
    }

    private static void assertEqualsUnresolved(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
