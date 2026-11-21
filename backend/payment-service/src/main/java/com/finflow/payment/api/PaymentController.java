package com.finflow.payment.api;

import com.finflow.payment.application.PaymentService;
import com.finflow.payment.application.PaymentService.InitiatePayment;
import com.finflow.payment.application.PaymentSummary;
import com.finflow.payment.application.StoredEvent;
import com.finflow.payment.domain.Payment;
import com.finflow.payment.domain.PaymentState;
import com.finflow.payment.domain.PaymentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@Validated
class PaymentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String CORRELATION_ID = "X-Correlation-Id";

    private final PaymentService service;

    PaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * 202 : le paiement est accepté pour traitement, pas forcément terminé. La réponse donne son
     * état courant ; un rejeu de la même clé renvoie le même paiement (ADR-009).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN', 'TENANT_MEMBER')")
    ResponseEntity<PaymentResponse> initiate(
            @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 100) String idempotencyKey,
            @RequestHeader(value = CORRELATION_ID, required = false) @Size(max = 100) String correlationId,
            @Valid @RequestBody InitiatePaymentRequest request,
            Authentication caller) {
        String correlation = correlationId == null ? UUID.randomUUID().toString() : correlationId;
        PaymentService.Result result = service.initiate(
                new InitiatePayment(request.sourceAccountId(), request.destinationAccountId(), request.amountCents(),
                        request.currency() == null ? Payment.CURRENCY : request.currency(), request.description()),
                idempotencyKey, caller.getName(), correlation);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/payments/" + result.payment().id()))
                .header(CORRELATION_ID, correlation)
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .body(PaymentResponse.of(result.payment().state()));
    }

    @GetMapping("/{id}")
    PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.of(service.get(id).state());
    }

    /** Historique complet, tel qu'il est conservé : le point de départ de la traçabilité. */
    @GetMapping("/{id}/events")
    List<StoredEvent> events(@PathVariable UUID id) {
        return service.history(id);
    }

    @GetMapping
    PageResponse<PaymentSummary> list(@RequestParam(required = false) PaymentStatus status,
                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return new PageResponse<>(service.list(status, page, size), page, size, service.count(status));
    }

    record InitiatePaymentRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @Positive @Max(100_000_000_000L) long amountCents,
            @Pattern(regexp = "EUR", message = "seul l'euro est pris en charge") String currency,
            @Size(max = 140) String description) {
    }

    record PaymentResponse(UUID id, PaymentStatus status, UUID sourceAccountId, UUID destinationAccountId,
                           long amountCents, String currency, String description, Payment.Rejection rejection,
                           UUID ledgerTransactionId, int version, Instant createdAt, Instant updatedAt) {
        static PaymentResponse of(PaymentState s) {
            return new PaymentResponse(s.id(), s.status(), s.sourceAccountId(), s.destinationAccountId(),
                    s.amountCents(), s.currency(), s.description(), s.rejection(), s.ledgerTransactionId(),
                    s.version(), s.createdAt(), s.updatedAt());
        }
    }

    record PageResponse<T>(List<T> content, int page, int size, long totalElements) {
    }
}
