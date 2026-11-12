import { useState } from 'react'
import { ApiError } from '../../api/client'
import type { AdminAgent, AdminRotatedKey } from '../../api/types'
import { BudgetBar } from '../../components/BudgetBar'
import { AgentStatusBadge } from '../../components/StatusBadge'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews'
import { formatDateTime, formatMoney, shortId } from '../../lib/format'
import { PolicyEditorDrawer } from './PolicyEditorDrawer'
import { useAgents } from './useAgents'
import { useRotateKey, useSuspendAgent, useResumeAgent } from './useAgentMutations'

interface Totals {
  limitMinor: number
  spentMinor: number
  reservedMinor: number
  remainingMinor: number
}

function detailOf(error: unknown, fallback: string): string {
  return error instanceof ApiError
    ? error.detail
    : error instanceof Error
      ? error.message
      : fallback
}

function summarize(agents: AdminAgent[]): Totals {
  return agents.reduce<Totals>(
    (totals, agent) => ({
      limitMinor: totals.limitMinor + agent.budget.limitMinor,
      spentMinor: totals.spentMinor + agent.budget.spentMinor,
      reservedMinor: totals.reservedMinor + agent.budget.reservedMinor,
      remainingMinor: totals.remainingMinor + agent.budget.remainingMinor,
    }),
    { limitMinor: 0, spentMinor: 0, reservedMinor: 0, remainingMinor: 0 },
  )
}

function errorDetail(error: unknown): string {
  if (error instanceof ApiError) {
    return error.detail
  }
  return error instanceof Error ? error.message : 'The refresh failed.'
}

function AgentsTable({
  agents,
  onEditPolicy,
  onRotateKey,
  onSuspend,
  onResume,
}: {
  agents: AdminAgent[]
  onEditPolicy: (agent: AdminAgent) => void
  onRotateKey: (agent: AdminAgent) => void
  onSuspend: (agent: AdminAgent) => void
  onResume: (agent: AdminAgent) => void
}) {
  return (
    <section className="panel" aria-labelledby="agents-table-title">
      <div className="panel__header">
        <div>
          <h2 className="panel__title" id="agents-table-title">
            Agent registry
          </h2>
          <p className="panel__subtitle">
            Current UTC-week totals, refreshed every five seconds.
          </p>
        </div>
        <span className="count-chip">{agents.length} total</span>
      </div>

      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th scope="col">Agent</th>
              <th scope="col">Status</th>
              <th scope="col">This week</th>
              <th scope="col">Auto-approve</th>
              <th scope="col" className="hide-sm">
                Allow-list
              </th>
              <th scope="col" className="hide-md">
                Created
              </th>
              <th scope="col">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {agents.map((agent) => {
              const visiblePayees = agent.policy.allowedPayees.slice(0, 2)
              const hiddenPayees = agent.policy.allowedPayees.length - visiblePayees.length
              const isActive = agent.status === 'ACTIVE'
              return (
                <tr key={agent.agentId}>
                  <td>
                    <div className="agent-cell">
                      <span className="agent-cell__name">{agent.name}</span>
                      <span className="agent-cell__id mono" title={agent.agentId}>
                        {shortId(agent.agentId)}
                      </span>
                    </div>
                  </td>
                  <td>
                    <AgentStatusBadge status={agent.status} />
                  </td>
                  <td className="cell-budget">
                    <BudgetBar budget={agent.budget} />
                  </td>
                  <td className="nowrap">
                    <span className="money">{formatMoney(agent.policy.autoApproveThresholdMinor)}</span>
                    <span className="muted"> and below</span>
                  </td>
                  <td className="hide-sm">
                    <div className="payees">
                      {visiblePayees.length === 0 ? (
                        <span className="muted">No payees</span>
                      ) : (
                        visiblePayees.map((email) => (
                          <span className="payee" key={email} title={email}>
                            {email}
                          </span>
                        ))
                      )}
                      {hiddenPayees > 0 ? (
                        <span className="payees__more">+{hiddenPayees} more</span>
                      ) : null}
                    </div>
                  </td>
                  <td className="hide-md nowrap muted">{formatDateTime(agent.createdAt)}</td>
                  <td className="nowrap">
                    <div className="agent-actions">
                      <button
                        type="button"
                        className="btn btn--small"
                        onClick={() => onEditPolicy(agent)}
                        aria-label={`Edit policy for ${agent.name}`}
                      >
                        Edit policy
                      </button>
                      <button
                        type="button"
                        className="btn btn--small"
                        onClick={() => onRotateKey(agent)}
                        aria-label={`Rotate API key for ${agent.name}`}
                      >
                        Rotate key
                      </button>
                      {isActive ? (
                        <button
                          type="button"
                          className="btn btn--small btn--danger"
                          onClick={() => onSuspend(agent)}
                          aria-label={`Suspend ${agent.name}`}
                        >
                          Suspend
                        </button>
                      ) : (
                        <button
                          type="button"
                          className="btn btn--small"
                          onClick={() => onResume(agent)}
                          aria-label={`Resume ${agent.name}`}
                        >
                          Resume
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </section>
  )
}

export function AgentsPanel() {
  const query = useAgents()
  const [editing, setEditing] = useState<AdminAgent | null>(null)
  const [feedback, setFeedback] = useState<string | null>(null)
  const [rotatingKey, setRotatingKey] = useState<AdminAgent | null>(null)
  const [suspendingAgent, setSuspendingAgent] = useState<AdminAgent | null>(null)
  const [resumingAgent, setResumingAgent] = useState<AdminAgent | null>(null)

  const rotateKey = useRotateKey()
  const suspend = useSuspendAgent()
  const resume = useResumeAgent()

  const handleRotateKey = (agent: AdminAgent) => {
    setRotatingKey(agent)
  }

  const confirmRotateKey = () => {
    if (!rotatingKey) return
    rotateKey.mutate(rotatingKey.agentId, {
      onSuccess: (result: AdminRotatedKey) => {
        setFeedback(`API key rotated: ${result.apiKey}`)
        setRotatingKey(null)
      },
      onError: (error) => {
        const detail = error instanceof ApiError ? error.detail : error.message
        setFeedback(`Failed to rotate key: ${detail}`)
        setRotatingKey(null)
      },
    })
  }

  const cancelRotateKey = () => {
    setRotatingKey(null)
  }

  const handleSuspend = (agent: AdminAgent) => {
    setSuspendingAgent(agent)
  }

  const confirmSuspend = () => {
    if (!suspendingAgent) return
    suspend.mutate(suspendingAgent.agentId, {
      onSuccess: () => {
        setFeedback(`${suspendingAgent.name} suspended`)
        setSuspendingAgent(null)
      },
      onError: (error) => {
        const detail = error instanceof ApiError ? error.detail : error.message
        setFeedback(`Failed to suspend: ${detail}`)
        setSuspendingAgent(null)
      },
    })
  }

  const cancelSuspend = () => {
    setSuspendingAgent(null)
  }

  const handleResume = (agent: AdminAgent) => {
    setResumingAgent(agent)
  }

  const confirmResume = () => {
    if (!resumingAgent) return
    resume.mutate(resumingAgent.agentId, {
      onSuccess: () => {
        setFeedback(`${resumingAgent.name} resumed`)
        setResumingAgent(null)
      },
      onError: (error) => {
        const detail = error instanceof ApiError ? error.detail : error.message
        setFeedback(`Failed to resume: ${detail}`)
        setResumingAgent(null)
      },
    })
  }

  const cancelResume = () => {
    setResumingAgent(null)
  }

  return (
    <>
      <header className="page-head">
        <div>
          <span className="eyebrow">Operations</span>
          <h1 className="page-title">Agents</h1>
          <p className="page-description">
            Every agent&apos;s leash: weekly budget, allowed payees, and the threshold above which
            a payment needs a human.
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
            onClick={() => void query.refetch()}
            disabled={query.isPending || query.isFetching}
          >
            {query.isFetching ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </header>

      {feedback ? (
        <div className="alert alert--success page-alert" role="status">
          {feedback}{' '}
          <button type="button" className="link-btn" onClick={() => setFeedback(null)}>
            Dismiss
          </button>
        </div>
      ) : null}

      {query.isPending ? (
        <LoadingState label="Loading agents" rows={5} />
      ) : query.isError && !query.data ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : query.data && query.data.length === 0 ? (
        <EmptyState
          title="No agents registered yet"
          description="Create an agent through the admin API. Once it exists, its weekly budget, allow-list, and status appear here."
        />
      ) : query.data ? (
        <>
          {query.isError ? (
            <div className="alert alert--warning" role="alert">
              Live refresh failed: {errorDetail(query.error)}. The figures below are the last
              successful read.{' '}
              <button type="button" className="link-btn" onClick={() => void query.refetch()}>
                Retry
              </button>
            </div>
          ) : null}

          <SummaryStats agents={query.data} />
          <AgentsTable
            agents={query.data}
            onEditPolicy={setEditing}
            onRotateKey={handleRotateKey}
            onSuspend={handleSuspend}
            onResume={handleResume}
          />
        </>
      ) : null}

      {editing ? (
        <PolicyEditorDrawer
          key={editing.agentId}
          agent={editing}
          onClose={() => setEditing(null)}
          onSaved={(message) => {
            setFeedback(message)
            setEditing(null)
          }}
        />
      ) : null}

      {rotatingKey ? (
        <ConfirmDialog
          title={`Rotate API key for ${rotatingKey.name}?`}
          description="The current API key will be revoked immediately. A new key will be generated and shown once. The old key will no longer work."
          confirmLabel="Rotate key"
          pendingLabel="Rotating…"
          pending={rotateKey.isPending}
          tone="danger"
          error={rotateKey.isError ? detailOf(rotateKey.error, 'Failed to rotate key') : null}
          onConfirm={confirmRotateKey}
          onCancel={cancelRotateKey}
        />
      ) : null}

      {suspendingAgent ? (
        <ConfirmDialog
          title={`Suspend ${suspendingAgent.name}?`}
          description="Suspending is immediate for new payment requests: every policy decision is denied with AGENT_SUSPENDED until the agent is resumed. A payout already submitted to PayPal is not recalled."
          confirmLabel="Suspend agent"
          pendingLabel="Suspending…"
          pending={suspend.isPending}
          tone="danger"
          error={suspend.isError ? detailOf(suspend.error, 'The agent could not be suspended.') : null}
          onConfirm={confirmSuspend}
          onCancel={cancelSuspend}
        />
      ) : null}

      {resumingAgent ? (
        <ConfirmDialog
          title={`Resume ${resumingAgent.name}?`}
          description="Resuming allows the agent to make new payment requests immediately."
          confirmLabel="Resume agent"
          pendingLabel="Resuming…"
          pending={resume.isPending}
          tone="primary"
          error={resume.isError ? detailOf(resume.error, 'The agent could not be resumed.') : null}
          onConfirm={confirmResume}
          onCancel={cancelResume}
        />
      ) : null}
    </>
  )
}

function SummaryStats({ agents }: { agents: AdminAgent[] }) {
  const active = agents.filter((agent) => agent.status === 'ACTIVE').length
  const suspended = agents.length - active
  const totals = summarize(agents)
  const committed = totals.spentMinor + totals.reservedMinor

  return (
    <div className="stat-grid">
      <div className="stat">
        <span className="stat__label">Active agents</span>
        <span className="stat__value">{active}</span>
        <span className="stat__hint">
          {suspended === 0 ? 'None suspended' : `${suspended} suspended`}
        </span>
      </div>
      <div className="stat">
        <span className="stat__label">Weekly limits</span>
        <span className="stat__value stat__value--money">{formatMoney(totals.limitMinor)}</span>
        <span className="stat__hint">Combined policy caps</span>
      </div>
      <div className="stat">
        <span className="stat__label">Committed</span>
        <span className="stat__value stat__value--money">{formatMoney(committed)}</span>
        <span className="stat__hint">
          {formatMoney(totals.spentMinor)} spent · {formatMoney(totals.reservedMinor)} reserved
        </span>
      </div>
      <div className="stat">
        <span className="stat__label">Remaining</span>
        <span className="stat__value stat__value--money">
          {formatMoney(totals.remainingMinor)}
        </span>
        <span className="stat__hint">Until Monday 00:00 UTC</span>
      </div>
    </div>
  )
}
