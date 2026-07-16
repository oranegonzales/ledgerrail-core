package dev.oranegonzales.ledgerrail.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        CreateTransferResult result = transferService.create(idempotencyKey, request);
        URI location = URI.create("/api/v1/transfers/" + result.transfer().id());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .location(location)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.transfer());
    }

    @GetMapping("/{id}")
    public TransferResponse findById(@PathVariable UUID id) {
        return transferService.findById(id);
    }

    @GetMapping
    public List<TransferResponse> findByAccountId(
            @RequestParam UUID accountId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return transferService.findByAccountId(accountId, limit);
    }

    @GetMapping("/{id}/ledger-entries")
    public List<LedgerEntryResponse> findLedgerEntries(@PathVariable UUID id) {
        return transferService.findLedgerEntries(id);
    }
}
