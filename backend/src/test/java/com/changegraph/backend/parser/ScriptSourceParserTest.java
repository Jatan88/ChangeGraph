package com.changegraph.backend.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptSourceParserTest {

    private final ScriptSourceParser parser = new ScriptSourceParser();

    @Test
    void classMethodsKeepTypeScriptParametersAndGenericCalls() {
        JavaSourceParser.ParsedFile file = parser.parse("src/payment/PaymentService.ts", """
                @Injectable()
                class PaymentService {
                  chargeCard(orderId: string, cents: number): void {
                    this.retryPayment(orderId)
                  }
                  retryPayment(orderId: string): void {
                    const [paid, setPaid] = useState<boolean>(false)
                  }
                }
                """);

        assertEquals("Injectable", file.services().get(0).basis());
        JavaSourceParser.ParsedFunction charge = function(file, "chargeCard");
        assertEquals("chargeCard(orderId,cents)", charge.signature());
        assertTrue(charge.calls().stream().anyMatch(call -> call.calleeName().equals("retryPayment") && call.argumentCount() == 1));
        JavaSourceParser.ParsedFunction retry = function(file, "retryPayment");
        assertTrue(retry.calls().stream().anyMatch(call -> call.calleeName().equals("useState") && call.argumentCount() == 1));
        assertTrue(retry.calls().stream().noneMatch(call -> call.calleeName().equals("boolean")));
    }

    @Test
    void jsxExpressionCallsBelongToTheComponent() {
        JavaSourceParser.ParsedFile file = parser.parse("src/CheckoutPage.tsx", """
                export function CheckoutPage(orderId: string) {
                  return <button onClick={() => retryPayment(orderId)}>Pay</button>
                }
                """);

        JavaSourceParser.ParsedFunction page = function(file, "CheckoutPage");
        assertEquals(1, page.calls().stream().filter(call -> call.calleeName().equals("retryPayment")).count());
        assertTrue(file.functions().stream().noneMatch(function -> function.name().equals("button")));
    }

    @Test
    void testCallbackBecomesATestFunction() {
        JavaSourceParser.ParsedFile file = parser.parse("src/payment/PaymentService.test.ts", """
                test("retries the payment", () => {
                  retryPayment("pay-1")
                })
                """);

        JavaSourceParser.ParsedFunction test = function(file, "retries the payment");
        assertTrue(test.test());
        assertTrue(test.calls().stream().anyMatch(call -> call.calleeName().equals("retryPayment") && call.argumentCount() == 1));
    }

    @Test
    void constructorCallUsesTheClassName() {
        JavaSourceParser.ParsedFile file = parser.parse("src/main.ts", """
                class PaymentService {
                  constructor(paymentId: string) {}
                }
                function build() {
                  return new PaymentService("pay-1")
                }
                """);

        assertTrue(function(file, "<init>").constructor());
        assertTrue(function(file, "build").calls().stream()
                .anyMatch(call -> call.calleeName().equals("PaymentService") && "CONSTRUCTOR".equals(call.kind()) && call.argumentCount() == 1));
    }

    private static JavaSourceParser.ParsedFunction function(JavaSourceParser.ParsedFile file, String name) {
        return file.functions().stream().filter(function -> function.name().equals(name)).findFirst().orElseThrow();
    }
}
