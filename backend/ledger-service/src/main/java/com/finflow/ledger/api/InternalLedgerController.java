package com.finflow.ledger.api;

import com.finflow.ledger.application.LedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Écritures réservées à Payment (ADR-012). La Gateway ne route pas {@code /internal/**} : ces
 * opérations ne sont atteignables que de l'intérieur, par un service qui a déjà validé l'intention.
 * Elles valident quand même le jeton relayé et l'organisation.
 *
 * <p>La clé d'idempotence est dérivée de l'identifiant du paiement ou du remboursement, pas
 * fournie par le client : une reprise ne peut donc jamais comptabiliser deux fois.
 */
@RestController
@RequestMapping("/internal/v1/ledger")
class InternalLedgerController {

    private final LedgerService service;

    InternalLedgerController(LedgerService service) {
        this.service = service;
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN', 'TENANT_MEMBER', 'SERVICE')")
    ResponseEntity<LedgerController.TransactionResponse> transfer(@Valid @RequestBody TransferRequest request) {
        LedgerService.Result result = service.transfer(request.paymentId(), request.sourceAccountId(),
                request.destinationAccountId(), request.amountCents(), request.description());
        return respond(result);
    }

    @PostMapping("/reversals")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN', 'TENANT_MEMBER', 'SERVICE')")
    ResponseEntity<LedgerController.TransactionResponse> reverse(@Valid @RequestBody ReversalRequest request) {
        LedgerService.Result result = service.reverse(request.refundId(), request.transactionId(),
                request.description());
        return respond(result);
    }

    private static ResponseEntity<LedgerController.TransactionResponse> respond(LedgerService.Result result) {
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/v1/ledger/transactions/" + result.transaction().id()))
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .body(LedgerController.TransactionResponse.of(result.transaction()));
    }

    record TransferRequest(@NotNull UUID paymentId, @NotNull UUID sourceAccountId, @NotNull UUID destinationAccountId,
                           @Positive @Max(100_000_000_000L) long amountCents, @Size(max = 140) String description) {
    }

    record ReversalRequest(@NotNull UUID refundId, @NotNull UUID transactionId, @Size(max = 140) String description) {
    }
}
