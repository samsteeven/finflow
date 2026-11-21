package com.finflow.account.projection;

import com.finflow.account.application.AccountService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{id}/activity")
class ActivityController {

    private final AccountService accounts;
    private final ProjectionRepository projections;

    ActivityController(AccountService accounts, ProjectionRepository projections) {
        this.accounts = accounts;
        this.projections = projections;
    }

    /** Solde projeté et derniers paiements du compte, d'après les événements reçus. */
    @GetMapping
    AccountActivity activity(@PathVariable UUID id) {
        accounts.get(id); // 404 si le compte n'existe pas dans cette organisation
        return new AccountActivity(id, projections.balance(id).orElse(0L), projections.payments(id, 20));
    }
}
