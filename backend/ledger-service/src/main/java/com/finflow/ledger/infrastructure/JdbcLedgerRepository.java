package com.finflow.ledger.infrastructure;

import com.finflow.ledger.application.EntryView;
import com.finflow.ledger.application.LedgerAccount;
import com.finflow.ledger.application.LedgerRepository;
import com.finflow.ledger.application.PostedTransaction;
import com.finflow.ledger.domain.Kind;
import com.finflow.ledger.domain.Line;
import com.finflow.ledger.domain.Posting;
import com.finflow.ledger.domain.Side;
import com.finflow.events.PostedLine;
import com.finflow.events.TransactionPosted;
import com.finflow.messaging.Envelopes;
import com.finflow.messaging.Outbox;
import com.finflow.security.TenantContext;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Le grand livre en JDBC. La RLS borne chaque requête à l'organisation de la transaction (ADR-005). */
@Repository
@Transactional(readOnly = true)
class JdbcLedgerRepository implements LedgerRepository {

    private static final String CURRENCY = "EUR";

    static final String TOPIC = "ledger.events";

    private final JdbcClient jdbc;
    private final Outbox outbox;
    private final Clock clock;

    private final io.micrometer.core.instrument.MeterRegistry meters;

    JdbcLedgerRepository(JdbcClient jdbc, Outbox outbox, Clock clock, io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureCustomerAccount(UUID accountId) {
        jdbc.sql("""
                        insert into ledger_accounts (id, tenant_id, kind, currency)
                        values (:id, :tenantId, 'CUSTOMER', :currency)
                        on conflict (id) do nothing""")
                .param("id", accountId)
                .param("tenantId", tenant())
                .param("currency", CURRENCY)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID ensureClearingAccount() {
        jdbc.sql("""
                        insert into ledger_accounts (id, tenant_id, kind, currency)
                        values (:id, :tenantId, 'CLEARING', :currency)
                        on conflict do nothing""")
                .param("id", UUID.randomUUID())
                .param("tenantId", tenant())
                .param("currency", CURRENCY)
                .update();
        return jdbc.sql("select id from ledger_accounts where kind = 'CLEARING'").query(UUID.class).single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerAccount> lockAccounts(Collection<UUID> accountIds) {
        // Verrous pris dans l'ordre des identifiants : A->B et B->A ne peuvent pas s'interbloquer.
        return jdbc.sql("""
                        select id, kind, currency, balance_cents from ledger_accounts
                         where id in (:ids) order by id for update""")
                .param("ids", accountIds)
                .query(JdbcLedgerRepository::account)
                .list();
    }

    @Override
    public Optional<PostedTransaction> findByKey(String idempotencyKey) {
        return jdbc.sql("select id from journal_transactions where idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(UUID.class)
                .optional()
                .flatMap(this::findTransaction);
    }

    @Override
    public Optional<PostedTransaction> findTransaction(UUID id) {
        Optional<PostedTransaction> header = jdbc.sql("""
                        select id, kind, idempotency_key, request_hash, reversal_of, description, posted_at
                          from journal_transactions where id = :id""")
                .param("id", id)
                .query((rs, row) -> new PostedTransaction(rs.getObject("id", UUID.class),
                        Kind.valueOf(rs.getString("kind")), rs.getString("idempotency_key"),
                        rs.getString("request_hash"), rs.getObject("reversal_of", UUID.class),
                        rs.getString("description"), rs.getTimestamp("posted_at").toInstant(), List.of()))
                .optional();
        return header.map(h -> new PostedTransaction(h.id(), h.kind(), h.idempotencyKey(), h.requestHash(),
                h.reversalOf(), h.description(), h.postedAt(), lines(id)));
    }

    private List<Line> lines(UUID transactionId) {
        return jdbc.sql("""
                        select account_id, side, amount_cents from journal_entries
                         where transaction_id = :id order by line_no""")
                .param("id", transactionId)
                .query((rs, row) -> new Line(rs.getObject("account_id", UUID.class),
                        Side.valueOf(rs.getString("side")), rs.getLong("amount_cents")))
                .list();
    }

    @Override
    public boolean isReversed(UUID transactionId) {
        return jdbc.sql("select count(*) from journal_transactions where reversal_of = :id")
                .param("id", transactionId)
                .query(Long.class)
                .single() > 0;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(Posting posting) {
        UUID tenant = tenant();
        jdbc.sql("""
                        insert into journal_transactions (id, tenant_id, kind, idempotency_key, request_hash,
                            reversal_of, description)
                        values (:id, :tenantId, :kind, :key, :hash, :reversalOf, :description)""")
                .param("id", posting.id())
                .param("tenantId", tenant)
                .param("kind", posting.kind().name())
                .param("key", posting.idempotencyKey())
                .param("hash", posting.requestHash())
                .param("reversalOf", posting.reversalOf())
                .param("description", posting.description())
                .update();
        short lineNo = 0;
        for (Line line : posting.lines()) {
            lineNo++;
            jdbc.sql("""
                            insert into journal_entries (transaction_id, line_no, tenant_id, account_id, side,
                                amount_cents, currency)
                            values (:transactionId, :lineNo, :tenantId, :accountId, :side, :amount, :currency)""")
                    .param("transactionId", posting.id())
                    .param("lineNo", lineNo)
                    .param("tenantId", tenant)
                    .param("accountId", line.accountId())
                    .param("side", line.side().name())
                    .param("amount", line.amountCents())
                    .param("currency", CURRENCY)
                    .update();
        }
        // Dans la même transaction que les écritures : publié si et seulement si comptabilisé (ADR-013).
        TransactionPosted payload = TransactionPosted.newBuilder()
                .setTransactionId(posting.id().toString())
                .setKind(posting.kind().name())
                .setReversalOf(posting.reversalOf() == null ? null : posting.reversalOf().toString())
                .setLines(posting.lines().stream()
                        .map(line -> PostedLine.newBuilder().setAccountId(line.accountId().toString())
                                .setSide(com.finflow.events.Side.valueOf(line.side().name()))
                                .setAmountCents(line.amountCents()).build())
                        .toList())
                .build();
        outbox.append(TOPIC, Envelopes.of(UUID.randomUUID(), "ledger-service", "TransactionPosted", posting.id(), 1,
                tenant, null, null, clock.instant(), payload));
        String kind = posting.kind().name();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        meters.counter("finflow.ledger.postings", "kind", kind).increment();
                    }
                });
    }

    @Override
    public Optional<LedgerAccount> findAccount(UUID accountId) {
        return jdbc.sql("select id, kind, currency, balance_cents from ledger_accounts where id = :id")
                .param("id", accountId)
                .query(JdbcLedgerRepository::account)
                .optional();
    }

    @Override
    public List<EntryView> entries(UUID accountId, int limit) {
        return jdbc.sql("""
                        select e.transaction_id, t.kind, e.side, e.amount_cents, t.posted_at
                          from journal_entries e join journal_transactions t on t.id = e.transaction_id
                         where e.account_id = :id
                         order by t.posted_at desc, e.transaction_id desc
                         limit :limit""")
                .param("id", accountId)
                .param("limit", limit)
                .query((rs, row) -> new EntryView(rs.getObject("transaction_id", UUID.class), rs.getString("kind"),
                        rs.getString("side"), rs.getLong("amount_cents"),
                        ((Timestamp) rs.getTimestamp("posted_at")).toInstant()))
                .list();
    }

    @Override
    public List<UUID> inconsistentAccounts() {
        return jdbc.sql("select account_id from comptes_incoherents()").query(UUID.class).list();
    }

    private static LedgerAccount account(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new LedgerAccount(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("currency"),
                rs.getLong("balance_cents"));
    }

    private static UUID tenant() {
        return TenantContext.require().value();
    }
}
