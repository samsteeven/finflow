package com.finflow.ledger.application;

import com.finflow.ledger.domain.Posting;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accès au grand livre. Les écritures et les verrouillages se font dans la transaction de
 * l'appelant, qui doit en ouvrir une.
 */
public interface LedgerRepository {

    /** Crée le compte client s'il n'existe pas encore dans l'organisation courante. */
    void ensureCustomerAccount(UUID accountId);

    /** Identifiant du compte de compensation de l'organisation, créé au besoin. */
    UUID ensureClearingAccount();

    /** Verrouille les comptes (ordre des identifiants) et renvoie leur état ; les inconnus sont absents. */
    List<LedgerAccount> lockAccounts(Collection<UUID> accountIds);

    Optional<PostedTransaction> findByKey(String idempotencyKey);

    Optional<PostedTransaction> findTransaction(UUID id);

    boolean isReversed(UUID transactionId);

    void insert(Posting posting);

    Optional<LedgerAccount> findAccount(UUID accountId);

    List<EntryView> entries(UUID accountId, int limit);

    /** Comptes dont le solde ne correspond pas à leurs écritures : doit toujours être vide. */
    List<UUID> inconsistentAccounts();
}
