package dev.oranegonzales.ledgerrail.reconciliation;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/reconciliation")
class ReconciliationOperationsController {

    private final ReconciliationRepository repository;

    ReconciliationOperationsController(ReconciliationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    List<ReconciliationResult> recent() {
        return repository.findRecent(100);
    }
}
