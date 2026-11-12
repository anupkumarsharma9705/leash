package com.leash.payment.infra;

import com.leash.payment.app.AdminPaymentRow;
import com.leash.payment.app.PaymentListFilter;
import com.leash.payment.app.PaymentStore;
import com.leash.payment.domain.ApprovalRecord;
import com.leash.payment.domain.ApprovalStatus;
import com.leash.payment.domain.BudgetSnapshot;
import com.leash.payment.domain.BudgetTotals;
import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.payment.domain.PendingApproval;
import com.leash.policy.PolicyOutcome;
import com.leash.policy.PolicyReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL-backed payment and approval store. */
@Repository
public class JdbcPaymentStore implements PaymentStore {

  private static final String INSERT_PAYMENT =
      """
      INSERT INTO payment_requests (
          id, agent_id, idempotency_key, request_fingerprint, payee_email, amount_minor,
          currency, memo, state, decision_outcome, decision_reasons, paypal_batch_id,
          raw_status, budget_limit_minor, budget_spent_minor, budget_reserved_minor,
          version, created_at, updated_at
      ) VALUES (
          ?, ?, ?, ?, ?, ?,
          ?, ?, ?, ?, ?::jsonb, ?,
          ?, ?, ?, ?,
          ?, ?, ?
      )
      """;

  private static final String FIND_BY_KEY =
      """
      SELECT * FROM payment_requests
      WHERE agent_id = ? AND idempotency_key = ?
      """;

  private static final String FIND_BY_ID = "SELECT * FROM payment_requests WHERE id = ?";

  private static final String FIND_STALE_BY_STATE =
      """
      SELECT * FROM payment_requests
      WHERE state = ? AND updated_at < ?
      ORDER BY updated_at
      LIMIT ?
      """;

  private static final String BUDGET_TOTALS =
      """
      SELECT
          COALESCE(SUM(
              CASE WHEN state IN ('APPROVED', 'SUBMITTED', 'SUCCEEDED')
                   THEN amount_minor ELSE 0 END
          ), 0)::bigint AS spent_minor,
          COALESCE(SUM(
              CASE WHEN state = 'PENDING_APPROVAL'
                   THEN amount_minor ELSE 0 END
          ), 0)::bigint AS reserved_minor
      FROM payment_requests
      WHERE agent_id = ? AND created_at >= ? AND created_at < ?
      """;

  private static final String BUDGET_TOTALS_BY_AGENT =
      """
      SELECT agent_id,
             COALESCE(SUM(
                 CASE WHEN state IN ('APPROVED', 'SUBMITTED', 'SUCCEEDED')
                      THEN amount_minor ELSE 0 END
             ), 0)::bigint AS spent_minor,
             COALESCE(SUM(
                 CASE WHEN state = 'PENDING_APPROVAL'
                      THEN amount_minor ELSE 0 END
             ), 0)::bigint AS reserved_minor
      FROM payment_requests
      WHERE created_at >= ? AND created_at < ?
      GROUP BY agent_id
      """;

  private static final String INSERT_APPROVAL =
      """
      INSERT INTO approvals (id, payment_id, status, requested_at, version)
      VALUES (?, ?, 'PENDING', ?, 0)
      """;

  private static final String UPDATE_PAYPAL_STATE =
      """
      UPDATE payment_requests
      SET state = ?, paypal_batch_id = ?, raw_status = ?,
          version = version + 1, updated_at = ?
      WHERE id = ? AND version = ?
      """;

  private static final String FIND_PENDING_APPROVALS =
      """
      SELECT a.id AS approval_id,
             a.payment_id AS approval_payment_id,
             a.status AS approval_status,
             a.requested_at AS approval_requested_at,
             a.decided_at AS approval_decided_at,
             a.decided_by AS approval_decided_by,
             a.note AS approval_note,
             a.version AS approval_version,
             p.*
      FROM approvals a
      JOIN payment_requests p ON p.id = a.payment_id
      WHERE a.status = 'PENDING'
      ORDER BY a.requested_at, a.id
      """;

  private static final String FIND_APPROVAL =
      """
      SELECT id, payment_id, status, requested_at, decided_at, decided_by, note, version
      FROM approvals
      WHERE id = ?
      """;

  private static final String DECIDE_APPROVAL =
      """
      UPDATE approvals
      SET status = ?, decided_at = ?, decided_by = ?, note = ?, version = version + 1
      WHERE id = ? AND version = ? AND status = 'PENDING'
      """;

  private static final String UPDATE_STATE =
      """
      UPDATE payment_requests
      SET state = ?, version = version + 1, updated_at = ?
      WHERE id = ? AND version = ?
      """;

  private static final String FIND_PAGE_HEAD =
      """
      SELECT p.*, a.name AS agent_name
      FROM payment_requests p
      JOIN agents a ON a.id = p.agent_id
      """;

  private static final String FIND_PAGE_TAIL =
      """
      ORDER BY p.created_at DESC, p.id DESC
      LIMIT ? OFFSET ?
      """;

  private static final String COUNT_HEAD = "SELECT count(*) FROM payment_requests p ";

  private static final TypeReference<List<PolicyReason>> REASONS_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JdbcPaymentStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(PaymentRecord record) {
    jdbcTemplate.update(
        INSERT_PAYMENT,
        record.id(),
        record.agentId(),
        record.idempotencyKey(),
        record.requestFingerprint(),
        record.payeeEmail(),
        record.amountMinor(),
        record.currency(),
        record.memo(),
        record.state().name(),
        record.decisionOutcome().name(),
        objectMapper.writeValueAsString(record.decisionReasons()),
        record.paypalBatchId(),
        record.rawStatus(),
        record.budget().limitMinor(),
        record.budget().spentMinor(),
        record.budget().reservedMinor(),
        record.version(),
        timestamp(record.createdAt()),
        timestamp(record.updatedAt()));
  }

  @Override
  public Optional<PaymentRecord> findByAgentAndIdempotencyKey(UUID agentId, String idempotencyKey) {
    List<PaymentRecord> rows =
        jdbcTemplate.query(
            FIND_BY_KEY, (resultSet, rowNumber) -> mapRecord(resultSet), agentId, idempotencyKey);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<PaymentRecord> findById(UUID paymentId) {
    List<PaymentRecord> rows =
        jdbcTemplate.query(FIND_BY_ID, (resultSet, rowNumber) -> mapRecord(resultSet), paymentId);
    return rows.stream().findFirst();
  }

  @Override
  public List<AdminPaymentRow> findPage(PaymentListFilter filter, long offset, int limit) {
    FilterClause clause = filterClause(filter);
    List<Object> parameters = new ArrayList<>(clause.parameters());
    parameters.add(limit);
    parameters.add(offset);
    String sql = FIND_PAGE_HEAD + clause.whereClause() + FIND_PAGE_TAIL;
    return jdbcTemplate.query(
        sql,
        (resultSet, rowNumber) ->
            new AdminPaymentRow(resultSet.getString("agent_name"), mapRecord(resultSet)),
        parameters.toArray());
  }

  @Override
  public long count(PaymentListFilter filter) {
    FilterClause clause = filterClause(filter);
    Long count =
        jdbcTemplate.queryForObject(
            COUNT_HEAD + clause.whereClause(), Long.class, clause.parameters().toArray());
    return count == null ? 0 : count;
  }

  @Override
  public List<PaymentRecord> findStaleByState(
      PaymentState state, Instant updatedBefore, int limit) {
    return jdbcTemplate.query(
        FIND_STALE_BY_STATE,
        (resultSet, rowNumber) -> mapRecord(resultSet),
        state.name(),
        timestamp(updatedBefore),
        limit);
  }

  @Override
  public BudgetTotals budgetTotals(UUID agentId, Instant startInclusive, Instant endExclusive) {
    return jdbcTemplate.queryForObject(
        BUDGET_TOTALS,
        (resultSet, rowNumber) ->
            new BudgetTotals(resultSet.getLong("spent_minor"), resultSet.getLong("reserved_minor")),
        agentId,
        timestamp(startInclusive),
        timestamp(endExclusive));
  }

  @Override
  public Map<UUID, BudgetTotals> budgetTotalsByAgent(Instant startInclusive, Instant endExclusive) {
    Map<UUID, BudgetTotals> totals = new LinkedHashMap<>();
    jdbcTemplate.query(
        BUDGET_TOTALS_BY_AGENT,
        (org.springframework.jdbc.core.RowCallbackHandler)
            resultSet ->
                totals.put(
                    resultSet.getObject("agent_id", UUID.class),
                    new BudgetTotals(
                        resultSet.getLong("spent_minor"), resultSet.getLong("reserved_minor"))),
        timestamp(startInclusive),
        timestamp(endExclusive));
    return Map.copyOf(totals);
  }

  @Override
  public void insertApproval(UUID approvalId, UUID paymentId, Instant requestedAt) {
    jdbcTemplate.update(INSERT_APPROVAL, approvalId, paymentId, timestamp(requestedAt));
  }

  @Override
  public List<PendingApproval> findPendingApprovals() {
    return jdbcTemplate.query(
        FIND_PENDING_APPROVALS, (resultSet, rowNumber) -> mapPendingApproval(resultSet));
  }

  @Override
  public Optional<ApprovalRecord> findApproval(UUID approvalId) {
    List<ApprovalRecord> rows =
        jdbcTemplate.query(
            FIND_APPROVAL, (resultSet, rowNumber) -> mapApproval(resultSet), approvalId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<ApprovalRecord> decideApproval(
      UUID approvalId,
      long expectedVersion,
      ApprovalStatus status,
      String decidedBy,
      String note,
      Instant decidedAt) {
    int updated =
        jdbcTemplate.update(
            DECIDE_APPROVAL,
            status.name(),
            timestamp(decidedAt),
            decidedBy,
            note,
            approvalId,
            expectedVersion);
    if (updated == 0) {
      return Optional.empty();
    }
    return findApproval(approvalId);
  }

  @Override
  public PaymentRecord updateState(
      UUID paymentId, long expectedVersion, PaymentState state, Instant updatedAt) {
    int updated =
        jdbcTemplate.update(
            UPDATE_STATE, state.name(), timestamp(updatedAt), paymentId, expectedVersion);
    if (updated == 0) {
      throw new OptimisticLockingFailureException("Payment changed concurrently: " + paymentId);
    }
    return findById(paymentId)
        .orElseThrow(
            () -> new IllegalStateException("Payment disappeared after update: " + paymentId));
  }

  @Override
  public PaymentRecord updatePayPalState(
      UUID paymentId,
      long expectedVersion,
      PaymentState state,
      String paypalBatchId,
      String rawStatus,
      Instant updatedAt) {
    int updated =
        jdbcTemplate.update(
            UPDATE_PAYPAL_STATE,
            state.name(),
            paypalBatchId,
            rawStatus,
            timestamp(updatedAt),
            paymentId,
            expectedVersion);
    if (updated == 0) {
      throw new OptimisticLockingFailureException("Payment changed concurrently: " + paymentId);
    }
    return findById(paymentId)
        .orElseThrow(
            () -> new IllegalStateException("Payment disappeared after update: " + paymentId));
  }

  private static FilterClause filterClause(PaymentListFilter filter) {
    java.util.Objects.requireNonNull(filter, "filter is required");
    List<String> conditions = new ArrayList<>();
    List<Object> parameters = new ArrayList<>();
    if (filter.status() != null) {
      conditions.add("p.state = ?");
      parameters.add(filter.status().name());
    }
    if (filter.agentId() != null) {
      conditions.add("p.agent_id = ?");
      parameters.add(filter.agentId());
    }
    if (filter.from() != null) {
      conditions.add("p.created_at >= ?");
      parameters.add(timestamp(filter.from()));
    }
    if (filter.to() != null) {
      conditions.add("p.created_at < ?");
      parameters.add(timestamp(filter.to()));
    }
    String whereClause =
        conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions) + "\n";
    return new FilterClause(whereClause, parameters);
  }

  private record FilterClause(String whereClause, List<Object> parameters) {}

  private PaymentRecord mapRecord(ResultSet resultSet) throws SQLException {
    List<PolicyReason> reasons =
        objectMapper.readValue(resultSet.getString("decision_reasons"), REASONS_TYPE);
    return new PaymentRecord(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("agent_id", UUID.class),
        resultSet.getString("idempotency_key"),
        resultSet.getString("request_fingerprint"),
        resultSet.getString("payee_email"),
        resultSet.getLong("amount_minor"),
        resultSet.getString("currency"),
        resultSet.getString("memo"),
        PaymentState.valueOf(resultSet.getString("state")),
        PolicyOutcome.valueOf(resultSet.getString("decision_outcome")),
        reasons,
        resultSet.getString("paypal_batch_id"),
        resultSet.getString("raw_status"),
        new BudgetSnapshot(
            resultSet.getLong("budget_limit_minor"),
            resultSet.getLong("budget_spent_minor"),
            resultSet.getLong("budget_reserved_minor")),
        resultSet.getLong("version"),
        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private PendingApproval mapPendingApproval(ResultSet resultSet) throws SQLException {
    ApprovalRecord approval =
        new ApprovalRecord(
            resultSet.getObject("approval_id", UUID.class),
            resultSet.getObject("approval_payment_id", UUID.class),
            ApprovalStatus.valueOf(resultSet.getString("approval_status")),
            resultSet.getObject("approval_requested_at", OffsetDateTime.class).toInstant(),
            instantOrNull(resultSet, "approval_decided_at"),
            resultSet.getString("approval_decided_by"),
            resultSet.getString("approval_note"),
            resultSet.getLong("approval_version"));
    return new PendingApproval(approval, mapRecord(resultSet));
  }

  private static ApprovalRecord mapApproval(ResultSet resultSet) throws SQLException {
    return new ApprovalRecord(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("payment_id", UUID.class),
        ApprovalStatus.valueOf(resultSet.getString("status")),
        resultSet.getObject("requested_at", OffsetDateTime.class).toInstant(),
        instantOrNull(resultSet, "decided_at"),
        resultSet.getString("decided_by"),
        resultSet.getString("note"),
        resultSet.getLong("version"));
  }

  private static Instant instantOrNull(ResultSet resultSet, String column) throws SQLException {
    OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
