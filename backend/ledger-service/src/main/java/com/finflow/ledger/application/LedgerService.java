package com.finflow.ledger.application;

import com.finflow.ledger.domain.Fingerprint;
import com.finflow.ledger.domain.InvalidPostingException;
import com.finflow.ledger.domain.Kind;
import com.finflow.ledger.domain.Posting;
import com.finflow.security.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Comptabilisation (ADR-011, ADR-012). Chaque opération tient en une transaction courte : les
 * comptes concernés sont d'abord verrouillés, toujours dans l'ordre des identifiants, puis la clé
 * d'idempotence est consultée sous ces verrous. Deux requêtes identiques en parallèle se
 * sérialisent donc, et la seconde relit la transaction de la première.
 */
@Service
public class LedgerService {

    public record Result(PostedTransaction transaction, boolean replayed) {
    }

    private final LedgerRepository repository;
    private final AccountDirectory accounts;
    private final TransactionTemplate tx;

    public LedgerService(LedgerRepository repository, AccountDirectory accounts, TransactionTemplate tx) {
        this.repository = repository;
        this.accounts = accounts;
        this.tx = tx;
    }

    public Result transfer(UUID paymentId, UUID source, UUID destination, long amountCents, String description) {
        TenantContext.require();
        String key = "payment:" + paymentId;
        String hash = Fingerprint.of(Kind.TRANSFER, source, destination, amountCents, description);
        if (source.equals(destination)) {
            throw new InvalidPostingException("Le compte source et le compte de destination doivent différer");
        }
        return tx.execute(status -> {
            repository.ensureCustomerAccount(source);
            repository.ensureCustomerAccount(destination);
            Map<UUID, LedgerAccount> accounts = lock(Set.of(source, destination));
            requireCustomers(accounts);
            Optional<Result> replay = replayOf(key, hash);
            if (replay.isPresent()) {
                return replay.get();
            }
            Posting posting = Posting.transfer(key, hash, source, destination, amountCents, description);
            requireFunds(posting, accounts);
            return post(posting);
        });
    }

    public Result deposit(String idempotencyKey, UUID accountId, long amountCents, String description) {
        TenantContext.require();
        String key = "deposit:" + idempotencyKey;
        String hash = Fingerprint.of(Kind.DEPOSIT, accountId, amountCents, description);
        // Appel réseau, donc hors transaction : le compte doit exister et être actif (ADR-012).
        AccountView account = accounts.find(accountId).orElseThrow(() -> new LedgerAccountNotFoundException(accountId));
        if (!account.active()) {
            throw new AccountNotActiveException(accountId, account.status());
        }
        return tx.execute(status -> {
            repository.ensureCustomerAccount(accountId);
            UUID clearing = repository.ensureClearingAccount();
            Map<UUID, LedgerAccount> locked = lock(Set.of(accountId, clearing));
            if (locked.get(accountId).clearing()) {
                throw new LedgerAccountNotFoundException(accountId);
            }
            Optional<Result> replay = replayOf(key, hash);
            if (replay.isPresent()) {
                return replay.get();
            }
            return post(Posting.deposit(key, hash, clearing, accountId, amountCents, description));
        });
    }

    public Result reverse(UUID refundId, UUID transactionId, String description) {
        TenantContext.require();
        String key = "refund:" + refundId;
        String hash = Fingerprint.of(Kind.REVERSAL, transactionId, description);
        return tx.execute(status -> {
            PostedTransaction original = repository.findTransaction(transactionId)
                    .orElseThrow(() -> new TransactionNotFoundException(transactionId));
            Set<UUID> touched = new TreeSet<>();
            original.lines().forEach(line -> touched.add(line.accountId()));
            Map<UUID, LedgerAccount> accounts = lock(touched);
            Optional<Result> replay = replayOf(key, hash);
            if (replay.isPresent()) {
                return replay.get();
            }
            if (original.kind() == Kind.REVERSAL) {
                throw new NotReversibleException(transactionId, "c'est déjà une contrepassation");
            }
            if (repository.isReversed(transactionId)) {
                throw new NotReversibleException(transactionId, "elle a déjà été contrepassée");
            }
            Posting posting = Posting.reversal(key, hash, transactionId, original.lines(), description);
            requireFunds(posting, accounts);
            return post(posting);
        });
    }

    public LedgerAccount balance(UUID accountId) {
        TenantContext.require();
        return repository.findAccount(accountId)
                .filter(account -> !account.clearing())
                .orElseThrow(() -> new LedgerAccountNotFoundException(accountId));
    }

    public List<EntryView> entries(UUID accountId, int limit) {
        balance(accountId);
        return repository.entries(accountId, limit);
    }

    public PostedTransaction transaction(UUID id) {
        TenantContext.require();
        return repository.findTransaction(id).orElseThrow(() -> new TransactionNotFoundException(id));
    }

    public List<UUID> inconsistentAccounts() {
        TenantContext.require();
        return repository.inconsistentAccounts();
    }

    private Map<UUID, LedgerAccount> lock(Set<UUID> ids) {
        Map<UUID, LedgerAccount> accounts = new HashMap<>();
        repository.lockAccounts(ids).forEach(account -> accounts.put(account.id(), account));
        for (UUID id : ids) {
            if (!accounts.containsKey(id)) {
                // Inconnu, ou appartenant à une autre organisation : la RLS ne les distingue pas.
                throw new LedgerAccountNotFoundException(id);
            }
        }
        return accounts;
    }

    /** Un client ne peut pas désigner le compte de compensation comme source ou destination. */
    private static void requireCustomers(Map<UUID, LedgerAccount> accounts) {
        accounts.values().stream().filter(LedgerAccount::clearing).findFirst().ifPresent(account -> {
            throw new LedgerAccountNotFoundException(account.id());
        });
    }

    private Optional<Result> replayOf(String key, String hash) {
        return repository.findByKey(key).map(existing -> {
            if (!existing.requestHash().equals(hash)) {
                throw new IdempotencyKeyReusedException(key);
            }
            return new Result(existing, true);
        });
    }

    /** Aucun compte client ne passe sous zéro ; la compensation n'a pas de limite. */
    private static void requireFunds(Posting posting, Map<UUID, LedgerAccount> accounts) {
        posting.deltas().forEach((accountId, delta) -> {
            LedgerAccount account = accounts.get(accountId);
            if (!account.clearing() && account.balanceCents() + delta < 0) {
                throw new InsufficientFundsException(accountId, account.balanceCents(), -delta);
            }
        });
    }

    private Result post(Posting posting) {
        repository.insert(posting);
        PostedTransaction posted = repository.findTransaction(posting.id()).orElseThrow();
        return new Result(posted, false);
    }
}
