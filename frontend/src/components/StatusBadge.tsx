import type {
  AgentStatus,
  ApprovalStatus,
  DecisionOutcome,
  PaymentState,
} from '../api/types'
import { humanize } from '../lib/format'

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

function toneForPaymentState(state: PaymentState): Tone {
  switch (state) {
    case 'PENDING_APPROVAL':
      return 'warning'
    case 'APPROVED':
    case 'SUBMITTED':
      return 'info'
    case 'SUCCEEDED':
      return 'success'
    case 'FAILED':
    case 'DENIED':
      return 'danger'
    case 'REJECTED':
      return 'neutral'
  }
}

function toneForDecision(outcome: DecisionOutcome): Tone {
  switch (outcome) {
    case 'APPROVE':
      return 'success'
    case 'ESCALATE':
      return 'warning'
    case 'DENY':
      return 'danger'
  }
}

function toneForAgentStatus(status: AgentStatus): Tone {
  return status === 'ACTIVE' ? 'success' : 'warning'
}

function toneForApprovalStatus(status: ApprovalStatus): Tone {
  switch (status) {
    case 'PENDING':
      return 'warning'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'neutral'
  }
}

function Badge({ tone, label }: { tone: Tone; label: string }) {
  return <span className={`badge badge--${tone}`}>{label}</span>
}

export function PaymentStateBadge({ state }: { state: PaymentState }) {
  return <Badge tone={toneForPaymentState(state)} label={humanize(state)} />
}

export function DecisionBadge({ outcome }: { outcome: DecisionOutcome }) {
  return <Badge tone={toneForDecision(outcome)} label={humanize(outcome)} />
}

export function AgentStatusBadge({ status }: { status: AgentStatus }) {
  return <Badge tone={toneForAgentStatus(status)} label={humanize(status)} />
}

export function ApprovalStatusBadge({ status }: { status: ApprovalStatus }) {
  return <Badge tone={toneForApprovalStatus(status)} label={humanize(status)} />
}
