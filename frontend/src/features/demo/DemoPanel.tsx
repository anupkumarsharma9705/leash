import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import type { DemoMode, DemoRunResponse, DemoSceneResult } from '../../api/types'
import { BudgetBar } from '../../components/BudgetBar'
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews'
import { DecisionBadge, PaymentStateBadge } from '../../components/StatusBadge'
import { formatDateTime, formatMoney, humanize, shortId } from '../../lib/format'
import { useDemoStatus, useRunDemo } from './useDemo'

function detailOf(error: unknown, fallback: string): string {
  return error instanceof ApiError
    ? error.detail
    : error instanceof Error
      ? error.message
      : fallback
}

function sceneTone(scene: DemoSceneResult): 'success' | 'warning' | 'danger' {
  if (scene.errorCode) {
    return 'danger'
  }
  if (scene.outcome === 'DENY') {
    return 'danger'
  }
  if (scene.outcome === 'ESCALATE') {
    return 'warning'
  }
  return 'success'
}

function SceneRow({ scene }: { scene: DemoSceneResult }) {
  const tone = sceneTone(scene)

  return (
    <li className={`demo-scene demo-scene--${tone}`}>
      <div className="demo-scene__head">
        <span className="demo-scene__amount">
          {formatMoney(scene.amountMinor, scene.currency)}
        </span>
        <span className="demo-scene__label">{scene.label}</span>
        <span className="demo-scene__badges">
          {scene.outcome ? <DecisionBadge outcome={scene.outcome} /> : null}
          {scene.state ? <PaymentStateBadge state={scene.state} /> : null}
          {scene.replayed ? <span className="badge badge--info">Replay</span> : null}
          {scene.errorCode ? <span className="badge badge--danger">Failed</span> : null}
        </span>
      </div>

      <p className="demo-scene__payee">
        to <span className="mono">{scene.payeeEmail}</span>
        {scene.paymentId ? (
          <span className="demo-scene__payment" title={scene.paymentId}>
            {' '}
            · payment {shortId(scene.paymentId)}
          </span>
        ) : null}
      </p>

      <p className="demo-scene__memo">{scene.memo}</p>

      {scene.reasons.length > 0 ? (
        <ul className="demo-scene__reasons">
          {scene.reasons.map((reason) => (
            <li key={reason.code}>
              <b>{humanize(reason.code)}</b> {reason.message}
            </li>
          ))}
        </ul>
      ) : null}

      {scene.errorCode ? (
        <p className="demo-scene__error" role="alert">
          {scene.errorCode}: {scene.errorMessage}
        </p>
      ) : null}
    </li>
  )
}

function RunResult({ run }: { run: DemoRunResponse }) {
  const failed = run.scenes.filter((scene) => scene.errorCode !== null).length
  const duration = Math.round(
    Date.parse(run.finishedAt) - Date.parse(run.startedAt),
  )
  const tone = failed > 0 ? 'warning' : 'success'

  return (
    <div className="demo-result">
      <div className={`alert alert--${tone}`} role="status">
        <b>Agent summary.</b> {run.summary}
      </div>

      <p className="demo-result__meta">
        {humanize(run.mode)} run · {formatDateTime(run.startedAt)}
        {Number.isFinite(duration) ? ` · ${duration} ms` : ''} · {run.scenes.length}{' '}
        {run.scenes.length === 1 ? 'scene' : 'scenes'}
      </p>

      {run.budgetBefore || run.budgetAfter ? (
        <div className="demo-budget-grid">
          {run.budgetBefore ? (
            <div>
              <span className="demo-budget-grid__label">Budget before the run</span>
              <BudgetBar budget={run.budgetBefore} />
            </div>
          ) : null}
          {run.budgetAfter ? (
            <div>
              <span className="demo-budget-grid__label">Budget after the run</span>
              <BudgetBar budget={run.budgetAfter} />
            </div>
          ) : null}
        </div>
      ) : null}

      <ul className="demo-scene-list" aria-label="Demo scenes">
        {run.scenes.map((scene) => (
          <SceneRow key={scene.invoiceId} scene={scene} />
        ))}
      </ul>
    </div>
  )
}

function Runner({
  invoiceCount,
  llmConfigured,
}: {
  invoiceCount: number
  llmConfigured: boolean
}) {
  const queryClient = useQueryClient()
  const run = useRunDemo()
  const [mode, setMode] = useState<DemoMode>('SCRIPTED')
  const llmAvailable = llmConfigured

  const start = () => {
    run.mutate(mode, {
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: ['agents'] })
        void queryClient.invalidateQueries({ queryKey: ['approvals'] })
        void queryClient.invalidateQueries({ queryKey: ['payments'] })
      },
    })
  }

  return (
    <section className="panel demo-runner" aria-labelledby="demo-runner-title">
      <header className="panel__header">
        <div>
          <h2 className="panel__title" id="demo-runner-title">
            Scripted agent
          </h2>
          <p className="panel__subtitle">
            The agent calls only the public API with its own key, exactly like an
            external agent.
          </p>
        </div>
        <span className="count-chip">{invoiceCount} invoices</span>
      </header>

      <div className="demo-runner__body">
        <fieldset className="demo-modes">
          <legend className="demo-modes__legend">Agent mode</legend>

          <label className={`demo-mode${mode === 'SCRIPTED' ? ' demo-mode--active' : ''}`}>
            <input
              type="radio"
              name="demo-mode"
              value="SCRIPTED"
              checked={mode === 'SCRIPTED'}
              onChange={() => setMode('SCRIPTED')}
            />
            <span>
              <b>Scripted</b>
              <small>Deterministic tool calls. The safety net for the demo.</small>
            </span>
          </label>

          <label
            className={`demo-mode${mode === 'LLM' ? ' demo-mode--active' : ''}${
              llmAvailable ? '' : ' demo-mode--disabled'
            }`}
          >
            <input
              type="radio"
              name="demo-mode"
              value="LLM"
              checked={mode === 'LLM'}
              disabled={!llmAvailable}
              onChange={() => setMode('LLM')}
            />
            <span>
              <b>LLM tool calling</b>
              <small>
                {llmAvailable
                  ? 'The model chooses tools; policy still decides.'
                  : 'Not configured in this build. Scripted mode stays available.'}
              </small>
            </span>
          </label>
        </fieldset>

        <div className="demo-runner__actions">
          <button
            type="button"
            className="btn btn--primary"
            onClick={start}
            disabled={run.isPending}
          >
            {run.isPending
              ? 'Running the scenes…'
              : run.data
                ? 'Run the demo again'
                : 'Run the demo'}
          </button>
          <p className="demo-runner__hint">
            Each invoice uses a stable <code>invoice-&lt;id&gt;</code> idempotency
            key. A second run replays the same payments and creates no second
            payout.
          </p>
        </div>

        {run.isPending ? (
          <div className="demo-running" role="status" aria-live="polite">
            <span className="spinner" aria-hidden="true" />
            The agent is working through the invoices over HTTP…
          </div>
        ) : null}

        {run.isError ? (
          <div className="alert alert--danger" role="alert">
            The demo run failed: {detailOf(run.error, 'The runner did not finish.')}
          </div>
        ) : null}

        {run.data ? (
          <RunResult run={run.data} />
        ) : !run.isPending ? (
          <div className="demo-blank">
            <h3>Nothing has run yet</h3>
            <p>
              Runs every bundled invoice through <span className="mono">POST
              /api/v1/payments</span>: one within policy and under the threshold,
              one above it, and one to a payee that is not allowed. Expect one
              approval, one escalation, and one denial.
            </p>
          </div>
        ) : null}
      </div>
    </section>
  )
}

export function DemoPanel() {
  const status = useDemoStatus()

  if (status.isPending) {
    return <LoadingState label="Loading the demo runner" rows={3} />
  }

  if (status.isError && status.error instanceof ApiError && status.error.status === 404) {
    return (
      <EmptyState
        title="The demo runner is not enabled"
        description="Start the backend with the demo profile (SPRING_PROFILES_ACTIVE=local,demo). Every other console section works without it."
      />
    )
  }

  if (status.isError) {
    return <ErrorState error={status.error} onRetry={() => void status.refetch()} />
  }

  const info = status.data
  if (!info || !info.enabled) {
    return (
      <EmptyState
        title="The demo runner is not enabled"
        description="This environment does not include the demo profile. Start the backend with SPRING_PROFILES_ACTIVE=local,demo to enable it."
      />
    )
  }

  if (!info.ready) {
    return (
      <EmptyState
        title="The demo agent is still provisioning"
        description="The seeder creates or refreshes the demo agent at startup. Refresh in a moment."
        action={
          <button
            type="button"
            className="btn"
            onClick={() => void status.refetch()}
          >
            Check again
          </button>
        }
      />
    )
  }

  return (
    <>
      <header className="page-head">
        <div>
          <span className="eyebrow">Demo profile</span>
          <h1 className="page-title">Demo runner</h1>
          <p className="page-description">
            {info.agentName ?? 'The demo agent'} spends through Leash over the public
            agent API. Policy, budget holds, and idempotency behave exactly as they
            do for any other agent.
          </p>
        </div>
        <div className="page-actions">
          <span className="live-pill live-pill--static">
            <span className="live-pill__dot" aria-hidden="true" />
            Demo profile
          </span>
        </div>
      </header>

      <Runner invoiceCount={info.invoiceCount} llmConfigured={info.llmConfigured} />
    </>
  )
}
