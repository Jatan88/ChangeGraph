package com.changegraph.backend.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonSourceParserTest {

    private final PythonSourceParser parser = new PythonSourceParser();

    @Test
    void serviceMethodDropsSelfAndKeepsItsCalls() {
        JavaSourceParser.ParsedFile file = parser.parse("src/paydesk/payment/payment_service.py", """
                @service
                class PaymentService:
                    def charge_card(self, order_id, cents):
                        self.retry_payment(order_id)
                        notify_payment(order_id, "ready")

                    def retry_payment(self, order_id):
                        return order_id

                def notify_payment(order_id, message):
                    return message
                """);

        assertEquals("service", file.services().get(0).basis());
        JavaSourceParser.ParsedFunction charge = function(file, "charge_card");
        assertEquals("charge_card(order_id,cents)", charge.signature());
        assertTrue(charge.calls().stream().anyMatch(call -> call.calleeName().equals("retry_payment") && call.argumentCount() == 1));
        assertTrue(charge.calls().stream().anyMatch(call -> call.calleeName().equals("notify_payment") && call.argumentCount() == 2));
        assertEquals(0, function(file, "retry_payment").calls().size());
    }

    @Test
    void testFunctionIsMarkedAndConstructorUsesTheClassName() {
        JavaSourceParser.ParsedFile file = parser.parse("tests/test_payment.py", """
                class PaymentService:
                    def __init__(self, payment_id):
                        self.payment_id = payment_id

                def test_charge():
                    PaymentService("pay-1")
                """);

        JavaSourceParser.ParsedFunction test = function(file, "test_charge");
        assertTrue(test.test());
        assertTrue(test.calls().stream().anyMatch(call -> call.calleeName().equals("PaymentService") && "CONSTRUCTOR".equals(call.kind()) && call.argumentCount() == 1));
        JavaSourceParser.ParsedFunction init = function(file, "<init>");
        assertTrue(init.constructor());
        assertEquals("PaymentService(payment_id)", init.signature());
    }

    @Test
    void definitionInsideAStringIsNotAFunction() {
        JavaSourceParser.ParsedFile file = parser.parse("src/notes.py", """
                text = \"\"\"
                def fake():
                    pass
                \"\"\"

                def real():
                    return text
                """);
        assertEquals(List.of("real"), file.functions().stream().map(JavaSourceParser.ParsedFunction::name).toList());
    }

    private static JavaSourceParser.ParsedFunction function(JavaSourceParser.ParsedFile file, String name) {
        return file.functions().stream().filter(function -> function.name().equals(name)).findFirst().orElseThrow();
    }
}
