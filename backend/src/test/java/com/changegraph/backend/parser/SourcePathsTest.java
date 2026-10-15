package com.changegraph.backend.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourcePathsTest {

    @Test
    void contextFilesIncludeApplicationSourceAndSkipDependencies() {
        assertTrue(SourcePaths.isContextFile("src/payment/PaymentService.ts"));
        assertTrue(SourcePaths.isContextFile("src/paydesk/payment/payment_service.py"));
        assertTrue(SourcePaths.isContextFile("src/main/java/pay/Pay.java"));
        assertFalse(SourcePaths.isContextFile("node_modules/react/index.js"));
        assertFalse(SourcePaths.isContextFile("frontend/dist/assets/index.js"));
        assertFalse(SourcePaths.isContextFile("src/types/global.d.ts"));
        assertFalse(SourcePaths.isContextFile("README.md"));
    }
}
