package dev.oranegonzales.ledgerrail.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TransferService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,100}");
    private final TransferRepository transferRepository;
    private final LedgerRepository ledgerRepository;
    private final OutboxRepository outboxRepository;

    TransferService(
            TransferRepository transferRepository,
            LedgerRepository ledgerRepository,
            OutboxRepository outboxRepository) {
        this.transferRepository = transferRepository;
        this.ledgerRepository = ledgerRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    CreateTransferResult create(String idempotencyKey, CreateTransferRequest request) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
        String currency = request.currency().toUpperCase(Locale.ROOT);
        TransferRecord proposed = new TransferRecord(
                UUID.randomUUID(),
                idempotencyKey,
                request.accountId(),
                request.type(),
                request.amount(),
                currency,
                TransferStatus.COMPLETED,
                Instant.now());
        boolean inserted = transferRepository.insert(proposed);
        if (!inserted) {
            TransferRecord existing = transferRepository
                    .findByAccountIdAndIdempotencyKey(request.accountId(), idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotent transfer could not be read"));
            if (!matches(existing, proposed)) {
                throw new IdempotencyConflictException();
            }
            return new CreateTransferResult(existing.toResponse(), true);
        }
        createLedgerEntries(proposed);
        outboxRepository.insertTransferCompleted(proposed);
        return new CreateTransferResult(proposed.toResponse(), false);
    }

    TransferResponse findById(UUID id) {
        return transferRepository.findById(id)
                .map(TransferRecord::toResponse)
                .orElseThrow(() -> new TransferNotFoundException(id));
    }

    List<TransferResponse> findByAccountId(UUID accountId, int limit) {
        return transferRepository.findByAccountId(accountId, limit).stream()
                .map(TransferRecord::toResponse)
                .toList();
    }

    List<LedgerEntryResponse> findLedgerEntries(UUID transferId) {
        findById(transferId);
        return ledgerRepository.findByTransferId(transferId);
    }

    private void createLedgerEntries(TransferRecord transfer) {
        String userAccount = "USER:%s".formatted(transfer.accountId());
        String clearingAccount = "PLATFORM_CLEARING:%s".formatted(transfer.currency());
        if (transfer.type() == TransferType.PAY_IN) {
            ledgerRepository.insert(
                    UUID.randomUUID(), transfer.id(), clearingAccount, LedgerEntryType.DEBIT,
                    transfer.amount(), transfer.currency(), transfer.createdAt());
            ledgerRepository.insert(
                    UUID.randomUUID(), transfer.id(), userAccount, LedgerEntryType.CREDIT,
                    transfer.amount(), transfer.currency(), transfer.createdAt());
            return;
        }
        ledgerRepository.insert(
                UUID.randomUUID(), transfer.id(), userAccount, LedgerEntryType.DEBIT,
                transfer.amount(), transfer.currency(), transfer.createdAt());
        ledgerRepository.insert(
                UUID.randomUUID(), transfer.id(), clearingAccount, LedgerEntryType.CREDIT,
                transfer.amount(), transfer.currency(), transfer.createdAt());
    }

    private boolean matches(TransferRecord existing, TransferRecord proposed) {
        return existing.accountId().equals(proposed.accountId())
                && existing.type() == proposed.type()
                && existing.amount().compareTo(proposed.amount()) == 0
                && existing.currency().equals(proposed.currency());
    }
}
