package dev.oranegonzales.ledgerrail.home;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, Object> home() {
        return Map.of(
                "name", "LedgerRail Core",
                "description", "A portfolio sandbox for reliable pay-in and pay-out processing",
                "version", "0.1.0",
                "status", "sandbox",
                "health", "/actuator/health",
                "endpoints", List.of(
                        "POST /api/v1/transfers",
                        "GET /api/v1/transfers/{id}",
                        "GET /api/v1/transfers?accountId={accountId}",
                        "GET /api/v1/transfers/{id}/ledger-entries"));
    }
}
