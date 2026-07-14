package dev.oranegonzales.ledgerrail.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    TransferRepository transferRepository;

    @Mock
    LedgerRepository ledgerRepository;

    @Mock
    OutboxRepository outboxRepository;

    TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(transferRepository, ledgerRepository, outboxRepository);
    }

    @Test
    void createsTransferLedgerEntriesAndOutboxEvent() {
        UUID accountId = UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(
                accountId, TransferType.PAY_IN, new BigDecimal("125.50"), "jmd");
        when(transferRepository.insert(any())).thenReturn(true);

        CreateTransferResult result = transferService.create("transfer-unit-001", request);

        assertThat(result.replayed()).isFalse();
        assertThat(result.transfer().currency()).isEqualTo("JMD");
        verify(ledgerRepository).insert(
                any(), any(), anyString(), org.mockito.ArgumentMatchers.eq(LedgerEntryType.DEBIT),
                any(), anyString(), any());
        verify(ledgerRepository).insert(
                any(), any(), anyString(), org.mockito.ArgumentMatchers.eq(LedgerEntryType.CREDIT),
                any(), anyString(), any());
        verify(outboxRepository).insertTransferCompleted(any());
    }

    @Test
    void replaysMatchingTransferWithoutNewLedgerEntries() {
        UUID accountId = UUID.randomUUID();
        TransferRecord existing = new TransferRecord(
                UUID.randomUUID(),
                "transfer-unit-002",
                accountId,
                TransferType.PAY_OUT,
                new BigDecimal("50.00"),
                "USD",
                TransferStatus.COMPLETED,
                Instant.now());
        when(transferRepository.insert(any())).thenReturn(false);
        when(transferRepository.findByAccountIdAndIdempotencyKey(accountId, "transfer-unit-002"))
                .thenReturn(Optional.of(existing));

        CreateTransferResult result = transferService.create(
                "transfer-unit-002",
                new CreateTransferRequest(accountId, TransferType.PAY_OUT, new BigDecimal("50"), "usd"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.transfer().id()).isEqualTo(existing.id());
        verify(ledgerRepository, never()).insert(any(), any(), anyString(), any(), any(), anyString(), any());
        verify(outboxRepository, never()).insertTransferCompleted(any());
    }

    @Test
    void rejectsConflictingIdempotentRequest() {
        UUID accountId = UUID.randomUUID();
        TransferRecord existing = new TransferRecord(
                UUID.randomUUID(),
                "transfer-unit-003",
                accountId,
                TransferType.PAY_OUT,
                new BigDecimal("50.00"),
                "USD",
                TransferStatus.COMPLETED,
                Instant.now());
        when(transferRepository.insert(any())).thenReturn(false);
        when(transferRepository.findByAccountIdAndIdempotencyKey(accountId, "transfer-unit-003"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> transferService.create(
                "transfer-unit-003",
                new CreateTransferRequest(accountId, TransferType.PAY_OUT, new BigDecimal("75.00"), "USD")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void rejectsMalformedIdempotencyKey() {
        CreateTransferRequest request = new CreateTransferRequest(
                UUID.randomUUID(), TransferType.PAY_IN, BigDecimal.ONE, "USD");

        assertThatThrownBy(() -> transferService.create("short", request))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }
}
