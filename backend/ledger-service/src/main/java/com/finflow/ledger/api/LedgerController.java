package com.finflow.ledger.api;

import com.finflow.ledger.application.EntryView;
import com.finflow.ledger.application.LedgerAccount;
import com.finflow.ledger.application.LedgerService;
import com.finflow.ledger.application.PostedTransaction;
import com.finflow.ledger.domain.Kind;
import com.finflow.ledger.domain.Line;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API publique du grand livre : consultation et dépôts simulés (ADR-011, ADR-012). */
@RestController
@RequestMapping("/api/v1/ledger")
@Validated
class LedgerController {

    private final LedgerService service;

    LedgerController(LedgerService service) {
        this.service = service;
    }

    @GetMapping("/accounts/{accountId}/balance")
    BalanceResponse balance(@PathVariable UUID accountId) {
        LedgerAccount account = service.balance(accountId);
        return new BalanceResponse(account.id(), account.currency(), account.balanceCents());
    }

    @GetMapping("/accounts/{accountId}/entries")
    List<EntryView> entries(@PathVariable UUID accountId,
                            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.entries(accountId, limit);
    }

    @GetMapping("/transactions/{id}")
    TransactionResponse transaction(@PathVariable UUID id) {
        return TransactionResponse.of(service.transaction(id));
    }

    /**
     * Dépôt simulé : il remplace le prestataire de paiement externe. Réservé à l'administrateur
     * de l'organisation ; la clé d'idempotence rend le rejeu inoffensif.
     */
    @PostMapping("/deposits")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    ResponseEntity<TransactionResponse> deposit(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 90) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        LedgerService.Result result = service.deposit(idempotencyKey, request.accountId(), request.amountCents(),
                request.description());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/v1/ledger/transactions/" + result.transaction().id()))
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .body(TransactionResponse.of(result.transaction()));
    }

    record DepositRequest(@NotNull UUID accountId, @Positive @Max(100_000_000_000L) long amountCents,
                          @Size(max = 140) String description) {
    }

    record BalanceResponse(UUID accountId, String currency, long balanceCents) {
    }

    record TransactionResponse(UUID id, Kind kind, UUID reversalOf, String description, Instant postedAt,
                               List<Line> lines) {
        static TransactionResponse of(PostedTransaction t) {
            return new TransactionResponse(t.id(), t.kind(), t.reversalOf(), t.description(), t.postedAt(),
                    t.lines());
        }
    }
}
