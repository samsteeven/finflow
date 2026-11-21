package com.finflow.ledger.application;

import com.finflow.ledger.domain.Kind;
import com.finflow.ledger.domain.Line;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Une transaction telle que le journal la conserve. */
public record PostedTransaction(UUID id, Kind kind, String idempotencyKey, String requestHash, UUID reversalOf,
                                String description, Instant postedAt, List<Line> lines) {
}
