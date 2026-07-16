package dev.oranegonzales.ledgerrail.home;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping("/api-info")
    public Map<String, Object> home() {
        return Map.of(
                "name", "LedgerRail Core",
                "description", "A portfolio sandbox for reliable pay-in and pay-out processing",
                "version", "0.4.0",
                "status", "sandbox",
                "access", "Transfer endpoints are public and rate-limited; operator endpoints require X-Portfolio-Key",
                "health", "/actuator/health",
                "endpoints", List.of(
                        "POST /api/v1/transfers",
                        "GET /api/v1/transfers/{id}",
                        "GET /api/v1/transfers?accountId={accountId}",
                        "GET /api/v1/transfers/{id}/ledger-entries",
                        "GET /api/v1/operations/reconciliation (operator)",
                        "GET /api/v1/operations/outbox/failed (operator)",
                        "POST /api/v1/operations/outbox/{eventId}/replay (operator)"));
    }
}
