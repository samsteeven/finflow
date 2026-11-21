package com.finflow.account.application;

import com.finflow.account.domain.Account;
import com.finflow.account.domain.AccountRepository;
import com.finflow.account.domain.Limits;
import com.finflow.security.TenantContext;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final Clock clock;

    public AccountService(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    /** Le tenant du compte est celui du jeton, jamais une valeur envoyée par le client. */
    @Transactional
    public Account open(String name, Account.Type type, Limits limits, String createdBy) {
        UUID tenant = TenantContext.require().value();
        return accounts.save(new Account(UUID.randomUUID(), tenant, name, type, limits, createdBy, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        TenantContext.require();
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Account> list(Account.Status status, Pageable pageable) {
        TenantContext.require();
        return status == null ? accounts.findAll(pageable) : accounts.findByStatus(status, pageable);
    }

    @Transactional
    public Account changeLimits(UUID id, Limits limits) {
        return update(id, account -> account.changeLimits(limits, clock.instant()));
    }

    @Transactional
    public Account freeze(UUID id) {
        return update(id, account -> account.freeze(clock.instant()));
    }

    @Transactional
    public Account unfreeze(UUID id) {
        return update(id, account -> account.unfreeze(clock.instant()));
    }

    @Transactional
    public Account close(UUID id) {
        return update(id, account -> account.close(clock.instant()));
    }

    private Account update(UUID id, Consumer<Account> change) {
        Account account = get(id);
        change.accept(account);
        return accounts.saveAndFlush(account);
    }
}
