import type { ICellRendererParams } from 'ag-grid-community'
import type { AdminPayment, DecisionOutcome, PaymentState } from '../../api/types'
import { DecisionBadge, PaymentStateBadge } from '../../components/StatusBadge'
import { shortId } from '../../lib/format'

export function AgentCell({ data }: ICellRendererParams<AdminPayment>) {
  if (!data) {
    return null
  }
  return (
    <div className="ledger-agent">
      <span className="ledger-agent__name">{data.agentName}</span>
      <span className="ledger-agent__id mono">{shortId(data.agentId)}</span>
    </div>
  )
}

export function DecisionCell({
  value,
}: ICellRendererParams<AdminPayment, DecisionOutcome>) {
  return value ? <DecisionBadge outcome={value} /> : null
}

export function StateCell({
  value,
  data,
}: ICellRendererParams<AdminPayment, PaymentState>) {
  if (!value) {
    return null
  }
  return (
    <div className="ledger-status">
      <PaymentStateBadge state={value} />
      {data?.rawStatus ? (
        <span className="ledger-status__raw mono">{data.rawStatus}</span>
      ) : null}
    </div>
  )
}
