package com.changegraph.backend.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceParserTest {

    private final JavaSourceParser parser = new JavaSourceParser();

    @Test
    void overloadedMethodsReceiveDistinctIds() {
        String source = """
                package demo;
                class Payments {
                  void charge(int amount) { receipt(amount); }
                  void charge(String currency) { }
                  void receipt(int amount) { }
                }
                """;
        JavaSourceParser.ParsedFile file = parser.parse("src/main/java/demo/Payments.java", source);
        assertEquals(3, file.functions().size());
        long distinct = file.functions().stream().map(JavaSourceParser.ParsedFunction::id).distinct().count();
        assertEquals(3, distinct);
        assertTrue(file.functions().stream().anyMatch(function -> function.id().contains("charge(int)")));
        assertTrue(file.functions().stream().anyMatch(function -> function.id().contains("charge(String)")));
    }

    @Test
    void parseFailureStaysOnThatFile() {
        JavaSourceParser.ParsedFile file = parser.parse("src/Broken.java", "class {");
        assertEquals(1, file.errors().size());
        assertTrue(file.functions().isEmpty());
    }
}
