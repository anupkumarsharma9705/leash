/**
 * Wire types for the Leash admin API.
 *
 * These mirror the Java DTO records exactly: camelCase field names and
 * ISO-8601 UTC timestamps. Money is always an integer count of minor units
 * (cents), never a float.
 */

export type AgentStatus = 'ACTIVE' | 'SUSPENDED'

export type PaymentState =
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'SUBMITTED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'REJECTED'
  | 'DENIED'

export type DecisionOutcome = 'APPROVE' | 'DENY' | 'ESCALATE'

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export type PolicyReasonCode =
  | 'AGENT_SUSPENDED'
  | 'CURRENCY_NOT_SUPPORTED'
  | 'INVALID_AMOUNT'
  | 'AMOUNT_EXCEEDS_CAP'
  | 'PAYEE_NOT_ALLOWED'
  | 'BUDGET_EXCEEDED'

export type LedgerEventType =
  | 'PAYMENT_REQUESTED'
  | 'DECISION_MADE'
  | 'APPROVAL_CREATED'
  | 'APPROVAL_DECIDED'
  | 'PAYPAL_SUBMITTED'
  | 'PAYPAL_STATUS_CHANGED'
  | 'RECONCILED'
  | 'POLICY_CHANGED'
  | 'AGENT_STATUS_CHANGED'
  | 'API_KEY_ROTATED'

export interface PolicyReason {
  code: PolicyReasonCode
  message: string
}

export interface AdminBudget {
  limitMinor: number
  spentMinor: number
  reservedMinor: number
  remainingMinor: number
}

export interface AdminPolicy {
  weeklyLimitMinor: number
  autoApproveThresholdMinor: number
  allowedPayees: string[]
}

export interface AdminAgent {
  agentId: string
  name: string
  status: AgentStatus
  createdAt: string
  policy: AdminPolicy
  budget: AdminBudget
}

/** The create response is the only one that carries the plaintext key. */
export interface AdminCreatedAgent extends AdminAgent {
  apiKey: string
}

export interface AdminRotatedKey {
  agentId: string
  apiKey: string
}

export interface AdminPayment {
  paymentId: string
  agentId: string
  agentName: string
  payeeEmail: string
  amountMinor: number
  currency: string
  memo: string | null
  state: PaymentState
  decisionOutcome: DecisionOutcome
  decisionReasons: PolicyReason[]
  paypalBatchId: string | null
  rawStatus: string | null
  createdAt: string
  updatedAt: string
}

export interface PaymentPage {
  content: AdminPayment[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface AdminPaymentEvent {
  id: number
  eventType: LedgerEventType
  actor: string
  payload: Record<string, unknown>
  createdAt: string
}

export interface AdminPaymentRefresh {
  paymentId: string
  state: PaymentState
  paypalBatchId: string | null
  rawStatus: string | null
  updatedAt: string
  refreshed: boolean
  reason: string | null
}

export interface AdminApproval {
  approvalId: string
  paymentId: string
  agentId: string
  status: ApprovalStatus
  paymentState: PaymentState
  payeeEmail: string
  amountMinor: number
  currency: string
  memo: string | null
  requestedAt: string
  decidedAt: string | null
  decidedBy: string | null
  note: string | null
  replayed: boolean
  autoRejected: boolean
  rejectionReasons: PolicyReason[]
}

export interface CreateAgentRequest {
  name: string
  weeklyLimitMinor: number
  autoApproveThresholdMinor: number
  allowedPayees: string[]
}

export interface UpdatePolicyRequest {
  weeklyLimitMinor: number
  autoApproveThresholdMinor: number
  allowedPayees: string[]
}

export interface PaymentQuery {
  page?: number
  size?: number
  status?: PaymentState
  agentId?: string
  /** Inclusive ISO-8601 instant. */
  from?: string
  /** Exclusive ISO-8601 instant. */
  to?: string
}

export type DemoMode = 'SCRIPTED' | 'LLM'

/**
 * The demo runner exists only under the backend `demo` profile. When it does
 * not, the status route answers 404 and the console hides the section.
 */
export interface DemoStatus {
  enabled: boolean
  ready: boolean
  agentId: string | null
  agentName: string | null
  invoiceCount: number
  llmConfigured: boolean
  modes: DemoMode[]
}

export interface DemoSceneResult {
  invoiceId: string
  label: string
  payeeEmail: string
  amountMinor: number
  currency: string
  memo: string
  paymentId: string | null
  state: PaymentState | null
  outcome: DecisionOutcome | null
  reasons: PolicyReason[]
  replayed: boolean
  errorCode: string | null
  errorMessage: string | null
}

export interface DemoRunResponse {
  mode: DemoMode
  startedAt: string
  finishedAt: string
  budgetBefore: AdminBudget | null
  budgetAfter: AdminBudget | null
  scenes: DemoSceneResult[]
  summary: string
}

export interface HealthResponse {
  status: string
}

/** RFC 7807 body. Extensions used by Leash are optional on the wire. */
export interface ProblemDetails {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  code?: string
  correlationId?: string
  details?: unknown
}

export interface ConfigResponse {
  allowLive: boolean
}
