import { useMemo, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../../api/client'
import type { AdminAgent, AdminApproval } from '../../api/types'
import { BudgetBar } from '../../components/BudgetBar'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews'
import { formatDateTime, formatMoney, humanize, shortId } from '../../lib/format'
import { useAgents } from '../agents/useAgents'
import { useApprovals } from './useApprovals'

const MAX_NOTE = 500

type Decision = 'approve' | 'reject'

interface PendingDecision {
  approval: AdminApproval
  decision: Decision
}

interface Feedback {
  tone: 'success' | 'warning' | 'danger'
  text: string
}

function detailOf(error: unknown, fallback: string): string {
  return error instanceof ApiError
    ? error.detail
    : error instanceof Error
      ? error.message
      : fallback
}

function reasonsText(approval: AdminApproval): string {
  return approval.rejectionReasons
    .map((reason) => `${humanize(reason.code)}: ${reason.message}`)
    .join(' · ')
}

function ApprovalCard({
  approval,
  agent,
  onDecide,
}: {
  approval: AdminApproval
  agent: AdminAgent | undefined
  onDecide: (decision: Decision) => void
}) {
  return (
    <article className="approval-card" aria-labelledby={`approval-${approval.approvalId}`}>
      <div className="approval-card__main">
        <div className="approval-card__head">
          <h2 className="approval-card__amount" id={`approval-${approval.approvalId}`}>
            {formatMoney(approval.amountMinor, approval.currency)}
          </h2>
          <span className="badge badge--warning">Waiting for a human</span>
        </div>

        <p className="approval-card__payee">
          to <span className="mono">{approval.payeeEmail}</span>
        </p>

        {approval.memo ? <p className="approval-card__memo">{approval.memo}</p> : null}

        <dl className="approval-card__facts">
          <div>
            <dt>Agent</dt>
            <dd>{agent?.name ?? shortId(approval.agentId)}</dd>
          </div>
          <div>
            <dt>Requested</dt>
            <dd>{formatDateTime(approval.requestedAt)}</dd>
          </div>
          <div>
            <dt>Approval</dt>
            <dd className="mono" title={approval.approvalId}>
              {shortId(approval.approvalId)}
            </dd>
          </div>
        </dl>
      </div>

      {agent ? (
        <div className="approval-card__budget">
          <span className="approval-card__budget-label">This week for {agent.name}</span>
          <BudgetBar budget={agent.budget} />
        </div>
      ) : null}

      <div className="approval-card__actions">
        <button
          type="button"
          className="btn btn--primary"
          onClick={() => onDecide('approve')}
        >
          Approve payment
        </button>
        <button
          type="button"
          className="btn btn--danger"
          onClick={() => onDecide('reject')}
        >
          Reject
        </button>
      </div>
    </article>
  )
}

export function ApprovalsPanel() {
  const approvals = useApprovals()
  const agents = useAgents()
  const queryClient = useQueryClient()
  const [target, setTarget] = useState<PendingDecision | null>(null)
  const [note, setNote] = useState('')
  const [feedback, setFeedback] = useState<Feedback | null>(null)

  const agentById = useMemo(() => {
    const map = new Map<string, AdminAgent>()
    for (const agent of agents.data ?? []) {
      map.set(agent.agentId, agent)
    }
    return map
  }, [agents.data])

  const decision = useMutation({
    mutationFn: ({ approval, decision: kind }: PendingDecision) =>
      kind === 'approve'
        ? api.approve(approval.approvalId, note)
        : api.reject(approval.approvalId, note),
    onSuccess: (result, variables) => {
      void queryClient.invalidateQueries({ queryKey: ['approvals'] })
      void queryClient.invalidateQueries({ queryKey: ['agents'] })
      void queryClient.invalidateQueries({ queryKey: ['payments'] })

      const amount = formatMoney(result.amountMinor, result.currency)
      if (variables.decision === 'approve' && result.autoRejected) {
        setFeedback({
          tone: 'warning',
          text: `${amount} to ${result.payeeEmail} was auto-rejected because it no longer passes policy. ${reasonsText(result)} The reservation is released.`,
        })
      } else if (variables.decision === 'approve') {
        setFeedback({
          tone: 'success',
          text: result.replayed
            ? `${amount} to ${result.payeeEmail} was already approved. No second PayPal call was made.`
            : `${amount} to ${result.payeeEmail} is approved and queued for a PayPal sandbox payout.`,
        })
      } else {
        setFeedback({
          tone: 'success',
          text: result.replayed
            ? `${amount} to ${result.payeeEmail} was already rejected.`
            : `${amount} to ${result.payeeEmail} is rejected. The reserved amount is released.`,
        })
      }

      setTarget(null)
      setNote('')
      decision.reset()
    },
  })

  const openDecision = (approval: AdminApproval, kind: Decision) => {
    decision.reset()
    setNote('')
    setTarget({ approval, decision: kind })
  }

  const closeDecision = () => {
    if (decision.isPending) {
      return
    }
    setTarget(null)
    setNote('')
    decision.reset()
  }

  const targetAgent = target ? agentById.get(target.approval.agentId) : undefined
  const isApprove = target?.decision === 'approve'

  return (
    <>
      <header className="page-head">
        <div>
          <span className="eyebrow">Human in the loop</span>
          <h1 className="page-title">Approvals</h1>
          <p className="page-description">
            Payments above an agent&apos;s auto-approve threshold wait here. Approving re-checks
            every hard rule, then pays; rejecting releases the reservation.
          </p>
        </div>
        <div className="page-actions">
          <span className="live-pill">
            <span className="live-pill__dot" aria-hidden="true" />
            Live · 5s
          </span>
          <button
            type="button"
            className="btn"
            onClick={() => void approvals.refetch()}
            disabled={approvals.isPending || approvals.isFetching}
          >
            {approvals.isFetching ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </header>

      {feedback ? (
        <div className={`alert alert--${feedback.tone} page-alert`} role="status">
          {feedback.text}{' '}
          <button type="button" className="link-btn" onClick={() => setFeedback(null)}>
            Dismiss
          </button>
        </div>
      ) : null}

      {approvals.isPending ? (
        <LoadingState label="Loading approvals" rows={4} />
      ) : approvals.isError && !approvals.data ? (
        <ErrorState error={approvals.error} onRetry={() => void approvals.refetch()} />
      ) : approvals.data && approvals.data.length === 0 ? (
        <EmptyState
          title="Nothing is waiting for a human"
          description="Escalated payments appear here with their payee, amount, and the agent's live budget. The queue refreshes every five seconds."
        />
      ) : approvals.data ? (
        <>
          {approvals.isError ? (
            <div className="alert alert--warning" role="alert">
              Live refresh failed: {detailOf(approvals.error, 'The queue could not be read.')}. The
              requests below are the last successful read.{' '}
              <button
                type="button"
                className="link-btn"
                onClick={() => void approvals.refetch()}
              >
                Retry
              </button>
            </div>
          ) : null}

          <div className="approval-list">
            {approvals.data.map((approval) => (
              <ApprovalCard
                key={approval.approvalId}
                approval={approval}
                agent={agentById.get(approval.agentId)}
                onDecide={(kind) => openDecision(approval, kind)}
              />
            ))}
          </div>
        </>
      ) : null}

      {target ? (
        <ConfirmDialog
          title={
            isApprove
              ? `Approve ${formatMoney(target.approval.amountMinor, target.approval.currency)} to ${target.approval.payeeEmail}?`
              : `Reject ${formatMoney(target.approval.amountMinor, target.approval.currency)} to ${target.approval.payeeEmail}?`
          }
          description={
            isApprove
              ? 'Approving re-runs every hard policy rule against the current budget. If any rule now fails, the payment is rejected instead. A passing payment is submitted to PayPal sandbox after the decision commits.'
              : 'Rejecting moves no money. The reserved amount is released for the rest of the UTC week.'
          }
          confirmLabel={isApprove ? 'Approve and pay' : 'Reject payment'}
          pendingLabel={isApprove ? 'Approving…' : 'Rejecting…'}
          tone={isApprove ? 'primary' : 'danger'}
          pending={decision.isPending}
          error={
            decision.isError
              ? detailOf(decision.error, 'The decision could not be recorded.')
              : null
          }
          onConfirm={() => decision.mutate(target)}
          onCancel={closeDecision}
        >
          <dl className="facts">
            <div className="facts__row">
              <dt>Agent</dt>
              <dd>{targetAgent?.name ?? shortId(target.approval.agentId)}</dd>
            </div>
            <div className="facts__row">
              <dt>Payee</dt>
              <dd className="mono">{target.approval.payeeEmail}</dd>
            </div>
            <div className="facts__row">
              <dt>Memo</dt>
              <dd>{target.approval.memo || '—'}</dd>
            </div>
            <div className="facts__row">
              <dt>Requested</dt>
              <dd>{formatDateTime(target.approval.requestedAt)}</dd>
            </div>
          </dl>

          {targetAgent ? (
            <div className="modal__budget">
              <span className="approval-card__budget-label">
                This week for {targetAgent.name}
              </span>
              <BudgetBar budget={targetAgent.budget} />
            </div>
          ) : null}

          <label className="field">
            <span className="field__label">Note (optional)</span>
            <textarea
              className="field__input field__textarea"
              rows={3}
              maxLength={MAX_NOTE}
              value={note}
              onChange={(event) => setNote(event.target.value)}
              placeholder="Why are you making this call?"
            />
            <span className="field__hint">
              {note.length}/{MAX_NOTE} characters. The note is written to the ledger.
            </span>
          </label>
        </ConfirmDialog>
      ) : null}
    </>
  )
}
