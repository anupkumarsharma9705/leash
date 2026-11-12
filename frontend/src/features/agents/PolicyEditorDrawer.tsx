import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import type { AdminAgent } from '../../api/types'
import { minorToInput, parseMoneyToMinor } from '../../lib/format'
import { useUpdatePolicy } from './useAgentMutations'

const MAX_PAYEES = 100
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function detailOf(error: unknown, fallback: string): string {
  return error instanceof ApiError
    ? error.detail
    : error instanceof Error
      ? error.message
      : fallback
}

function payeesOf(agent: AdminAgent): string {
  return agent.policy.allowedPayees.join('\n')
}

export function PolicyEditorDrawer({
  agent,
  onClose,
  onSaved,
}: {
  agent: AdminAgent
  onClose: () => void
  onSaved: (message: string) => void
}) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const onCloseRef = useRef(onClose)
  const [limit, setLimit] = useState(() => minorToInput(agent.policy.weeklyLimitMinor))
  const [threshold, setThreshold] = useState(() =>
    minorToInput(agent.policy.autoApproveThresholdMinor),
  )
  const [payeesText, setPayeesText] = useState(() => payeesOf(agent))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const mutation = useUpdatePolicy(agent.agentId)

  useEffect(() => {
    onCloseRef.current = onClose
  }, [onClose])

  // Mount-only: preserve focus and body scroll, close on Escape, restore focus.
  useEffect(() => {
    const previousFocus =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    closeRef.current?.focus()
    document.body.style.overflow = 'hidden'

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onCloseRef.current()
      }
    }
    document.addEventListener('keydown', onKeyDown)

    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
      previousFocus?.focus()
    }
  }, [])

  const submit = () => {
    mutation.reset()
    const nextErrors: Record<string, string> = {}
    const limitMinor = parseMoneyToMinor(limit)
    const thresholdMinor = parseMoneyToMinor(threshold)

    if (limitMinor === null || limitMinor < 0) {
      nextErrors.limit = 'Enter a dollar amount such as 2000.00.'
    }
    if (thresholdMinor === null || thresholdMinor < 0) {
      nextErrors.threshold = 'Enter a dollar amount such as 50.00.'
    }

    const payees = payeesText
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.length > 0)

    if (payees.length > MAX_PAYEES) {
      nextErrors.payees = `Keep at most ${MAX_PAYEES} payees.`
    } else {
      const invalid = payees.find((payee) => !EMAIL_PATTERN.test(payee))
      if (invalid) {
        nextErrors.payees = `"${invalid}" is not a valid email address.`
      }
    }

    setErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0 || limitMinor === null || thresholdMinor === null) {
      return
    }

    mutation.mutate(
      {
        weeklyLimitMinor: limitMinor,
        autoApproveThresholdMinor: thresholdMinor,
        allowedPayees: payees,
      },
      {
        onSuccess: () => onSaved(`Policy for ${agent.name} updated.`),
      },
    )
  }

  return (
    <div className="drawer-layer">
      <button
        type="button"
        className="drawer__scrim"
        aria-label="Close policy editor"
        onClick={onClose}
      />

      <aside
        className="drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="policy-drawer-title"
      >
        <header className="drawer__head">
          <div className="drawer__heading">
            <span className="eyebrow">Policy</span>
            <h2 className="drawer__title" id="policy-drawer-title">
              {agent.name}
            </h2>
            <span className="drawer__id mono" title={agent.agentId}>
              {agent.agentId.slice(0, 8)}
            </span>
          </div>
          <button
            ref={closeRef}
            type="button"
            className="icon-btn"
            onClick={onClose}
            aria-label="Close policy editor"
          >
            <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
              <path
                d="M5 5l10 10M15 5 5 15"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
              />
            </svg>
          </button>
        </header>

        <div className="drawer__body">
          <p className="drawer__lede">
            Policy changes are written to the ledger and take effect on the next decision. Payments
            already waiting for approval are re-checked against the new policy when a human decides.
          </p>

          <form
            className="policy-form"
            onSubmit={(event) => {
              event.preventDefault()
              submit()
            }}
          >
            <label className="field">
              <span className="field__label">Weekly limit (USD)</span>
              <input
                className="field__input"
                inputMode="decimal"
                value={limit}
                onChange={(event) => setLimit(event.target.value)}
                aria-invalid={errors.limit ? true : undefined}
                aria-describedby={errors.limit ? 'policy-limit-error' : undefined}
              />
              {errors.limit ? (
                <span className="field__error" id="policy-limit-error" role="alert">
                  {errors.limit}
                </span>
              ) : (
                <span className="field__hint">
                  Resets every Monday 00:00 UTC. Spent plus reserved must stay within it.
                </span>
              )}
            </label>

            <label className="field">
              <span className="field__label">Auto-approve threshold (USD)</span>
              <input
                className="field__input"
                inputMode="decimal"
                value={threshold}
                onChange={(event) => setThreshold(event.target.value)}
                aria-invalid={errors.threshold ? true : undefined}
                aria-describedby={errors.threshold ? 'policy-threshold-error' : undefined}
              />
              {errors.threshold ? (
                <span className="field__error" id="policy-threshold-error" role="alert">
                  {errors.threshold}
                </span>
              ) : (
                <span className="field__hint">
                  Amounts at or below this pay automatically. Above it, a human decides.
                </span>
              )}
            </label>

            <label className="field">
              <span className="field__label">Allowed payees</span>
              <textarea
                className="field__input field__textarea"
                rows={6}
                value={payeesText}
                onChange={(event) => setPayeesText(event.target.value)}
                aria-invalid={errors.payees ? true : undefined}
                aria-describedby={errors.payees ? 'policy-payees-error' : 'policy-payees-hint'}
                spellCheck={false}
              />
              {errors.payees ? (
                <span className="field__error" id="policy-payees-error" role="alert">
                  {errors.payees}
                </span>
              ) : (
                <span className="field__hint" id="policy-payees-hint">
                  One normalized email per line. Emails are trimmed and lower-cased. An empty list
                  denies every payee.
                </span>
              )}
            </label>

            {mutation.isError ? (
              <div className="alert alert--danger" role="alert">
                {detailOf(mutation.error, 'The policy could not be saved.')}
              </div>
            ) : null}

            <div className="policy-form__actions">
              <button type="button" className="btn" onClick={onClose} disabled={mutation.isPending}>
                Cancel
              </button>
              <button type="submit" className="btn btn--primary" disabled={mutation.isPending}>
                {mutation.isPending ? 'Saving…' : 'Save policy'}
              </button>
            </div>
          </form>
        </div>
      </aside>
    </div>
  )
}
