package com.finflow.account.api;

import com.finflow.account.application.AccountService;
import com.finflow.account.domain.Account;
import com.finflow.account.domain.Limits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Validated
class AccountController {

    private final AccountService service;

    AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request, Authentication caller) {
        Account account = service.open(request.name(), request.type(),
                new Limits(request.perTransactionLimitCents(), request.dailyLimitCents()), caller.getName());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.getId()))
                .body(AccountResponse.of(account));
    }

    @GetMapping
    PageResponse<AccountResponse> list(@RequestParam(required = false) Account.Status status,
                                       @RequestParam(defaultValue = "0") @Min(0) int page,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<Account> result = service.list(status,
                PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id"))));
        return new PageResponse<>(result.map(AccountResponse::of).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements());
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.of(service.get(id));
    }

    @PutMapping("/{id}/limits")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    AccountResponse changeLimits(@PathVariable UUID id, @Valid @RequestBody LimitsRequest request) {
        return AccountResponse.of(service.changeLimits(id,
                new Limits(request.perTransactionLimitCents(), request.dailyLimitCents())));
    }

    @PostMapping("/{id}/freeze")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    AccountResponse freeze(@PathVariable UUID id) {
        return AccountResponse.of(service.freeze(id));
    }

    @PostMapping("/{id}/unfreeze")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    AccountResponse unfreeze(@PathVariable UUID id) {
        return AccountResponse.of(service.unfreeze(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    AccountResponse close(@PathVariable UUID id) {
        return AccountResponse.of(service.close(id));
    }

    record OpenAccountRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull Account.Type type,
            @Pattern(regexp = "EUR", message = "seul l'euro est pris en charge") String currency,
            @Positive long perTransactionLimitCents,
            @Positive long dailyLimitCents) {
    }

    record LimitsRequest(@Positive long perTransactionLimitCents, @Positive long dailyLimitCents) {
    }

    record LimitsResponse(long perTransactionCents, long dailyCents) {
    }

    record AccountResponse(UUID id, String name, Account.Type type, String currency, Account.Status status,
                           LimitsResponse limits, Instant createdAt, Instant updatedAt) {
        static AccountResponse of(Account a) {
            Limits limits = a.getLimits();
            return new AccountResponse(a.getId(), a.getName(), a.getType(), a.getCurrency(), a.getStatus(),
                    new LimitsResponse(limits.perTransactionCents(), limits.dailyCents()),
                    a.getCreatedAt(), a.getUpdatedAt());
        }
    }

    record PageResponse<T>(List<T> content, int page, int size, long totalElements) {
    }
}
