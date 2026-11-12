import { useState, type ReactNode, useEffect } from 'react'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/useAuth'
import { ConfirmDialog } from './ConfirmDialog'
import { BrandMark } from './BrandMark'
import { HealthIndicator } from './HealthIndicator'
import { ThemeToggle } from './ThemeToggle'
import { useAgents } from '../features/agents/useAgents'
import { useResumeAgent, useSuspendAgent } from '../features/agents/useAgentMutations'
import { useDemoStatus } from '../features/demo/useDemo'
import { api } from '../api/client'
import type { ConfigResponse } from '../api/types'

export type ConsoleSection = 'agents' | 'approvals' | 'ledger' | 'demo'

const SECTIONS: Array<{ id: ConsoleSection; label: string }> = [
  { id: 'ledger', label: 'Ledger' },
  { id: 'approvals', label: 'Approvals' },
  { id: 'agents', label: 'Agents' },
]

function detailOf(error: unknown, fallback: string): string {
  return error instanceof ApiError
    ? error.detail
    : error instanceof Error
      ? error.message
      : fallback
}

/**
 * Global agent context: the selector chooses the agent the kill switch acts on.
 * Every payment decision locks the agent row, so suspend/resume take effect on
 * the next request without a restart.
 */
function AgentSwitch() {
  const agents = useAgents()
  const suspend = useSuspendAgent()
  const resume = useResumeAgent()
  const [selectedId, setSelectedId] = useState('')
  const [confirmSuspend, setConfirmSuspend] = useState(false)
  const [notice, setNotice] = useState<{
    tone: 'success' | 'danger'
    text: string
  } | null>(null)

  const list = agents.data ?? []
  const selected = list.find((agent) => agent.agentId === selectedId) ?? list[0]
  const pending = suspend.isPending || resume.isPending

  const act = () => {
    if (!selected) {
      return
    }
    if (selected.status === 'ACTIVE') {
      setConfirmSuspend(true)
      return
    }
    resume.mutate(selected.agentId, {
      onSuccess: () =>
        setNotice({ tone: 'success', text: `${selected.name} resumed.` }),
      onError: (error) =>
        setNotice({
          tone: 'danger',
          text: detailOf(error, 'The agent could not be resumed.'),
        }),
    })
  }

  return (
    <div className="agent-switch">
      <label className="agent-switch__label" htmlFor="agent-switch">
        Agent
      </label>
      <select
        id="agent-switch"
        className="select select--topbar"
        value={selected?.agentId ?? ''}
        onChange={(event) => setSelectedId(event.target.value)}
        disabled={list.length === 0}
        aria-label="Agent for the kill switch"
      >
        {list.length === 0 ? <option value="">No agents</option> : null}
        {list.map((agent) => (
          <option key={agent.agentId} value={agent.agentId}>
            {agent.name} · {agent.status === 'ACTIVE' ? 'active' : 'suspended'}
          </option>
        ))}
      </select>
      <button
        type="button"
        className={`btn btn--small ${selected?.status === 'ACTIVE' ? 'btn--danger' : ''}`}
        onClick={act}
        disabled={!selected || pending || agents.isPending}
        title={
          selected?.status === 'ACTIVE'
            ? 'Suspend this agent: every new payment is denied until it is resumed'
            : 'Resume this agent'
        }
      >
        {pending
          ? 'Working…'
          : selected?.status === 'ACTIVE'
            ? 'Suspend'
            : 'Resume'}
      </button>

      {notice ? (
        <div className={`toast alert alert--${notice.tone}`} role="status">
          {notice.text}{' '}
          <button type="button" className="link-btn" onClick={() => setNotice(null)}>
            Dismiss
          </button>
        </div>
      ) : null}

      {confirmSuspend && selected ? (
        <ConfirmDialog
          title={`Suspend ${selected.name}?`}
          description="Suspending is immediate for new payment requests: every policy decision is denied with AGENT_SUSPENDED until the agent is resumed. A payout already submitted to PayPal is not recalled."
          confirmLabel="Suspend agent"
          pendingLabel="Suspending…"
          tone="danger"
          pending={suspend.isPending}
          error={
            suspend.isError
              ? detailOf(suspend.error, 'The agent could not be suspended.')
              : null
          }
          onConfirm={() =>
            suspend.mutate(selected.agentId, {
              onSuccess: () => {
                setConfirmSuspend(false)
                setNotice({ tone: 'success', text: `${selected.name} suspended.` })
              },
            })
          }
          onCancel={() => {
            if (!suspend.isPending) {
              setConfirmSuspend(false)
              suspend.reset()
            }
          }}
        />
      ) : null}
    </div>
  )
}

export function AppShell({
  section,
  onSectionChange,
  children,
}: {
  section: ConsoleSection
  onSectionChange: (section: ConsoleSection) => void
  children: ReactNode
}) {
  const { username, signOut } = useAuth()
  const demo = useDemoStatus()
  const initial = (username ?? '?').charAt(0).toUpperCase()
  const sections =
    demo.data?.enabled === true
      ? [...SECTIONS, { id: 'demo' as const, label: 'Demo' }]
      : SECTIONS

  const [config, setConfig] = useState<ConfigResponse | null>(null)

  useEffect(() => {
    api.getConfig()
      .then(setConfig)
      .catch(() => setConfig({ allowLive: false }))
  }, [])

  return (
    <div className="app">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="topbar">
        <div className="topbar__inner">
          <div className="brand">
            <BrandMark />
            <span className="brand__name">Leash</span>
            <span className="brand__tag">spend control</span>
          </div>

          <nav className="nav" aria-label="Console sections">
            {sections.map((item) => (
              <button
                key={item.id}
                type="button"
                className={`nav__item${section === item.id ? ' nav__item--active' : ''}`}
                aria-current={section === item.id ? 'page' : undefined}
                onClick={() => onSectionChange(item.id)}
              >
                {item.label}
              </button>
            ))}
          </nav>

          <div className="topbar__right">
            <AgentSwitch />
            <span className={`env-pill${config?.allowLive ? ' env-pill--live' : ''}`}>
              {config?.allowLive ? '⚠️ Live' : 'Sandbox'}
            </span>
            <HealthIndicator />
            <ThemeToggle />
            <span className="topbar__divider" aria-hidden="true" />
            <div className="account">
              <span className="avatar" aria-hidden="true">
                {initial}
              </span>
              <span className="account__name">{username}</span>
              <button type="button" className="btn btn--ghost btn--small" onClick={signOut}>
                Sign out
              </button>
            </div>
          </div>
        </div>
      </header>

      <main id="main" className="main">
        {children}
      </main>

      <footer className="footer">
        <span>Leash · PayPal sandbox payouts</span>
        <span>All amounts USD · budget weeks start Monday 00:00 UTC</span>
      </footer>
    </div>
  )
}
