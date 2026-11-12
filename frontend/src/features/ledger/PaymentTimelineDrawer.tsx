import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../../api/client'
import type { AdminPayment, AdminPaymentEvent } from '../../api/types'
import {
  DecisionBadge,
  PaymentStateBadge,
} from '../../components/StatusBadge'
import {
  formatDateTime,
  formatMoney,
  humanize,
  shortId,
} from '../../lib/format'
import { usePaymentEvents } from './useLedger'

function eventTone(event: AdminPaymentEvent): string {
  if (event.eventType === 'DECISION_MADE') {
    if (event.payload.outcome === 'DENY') {
      return 'danger'
    }
    if (event.payload.outcome === 'ESCALATE') {
      return 'warning'
    }
    return 'success'
  }
  if (event.eventType === 'PAYPAL_STATUS_CHANGED') {
    return 'success'
  }
  if (event.eventType === 'RECONCILED') {
    return 'warning'
  }
  return 'info'
}

function eventDetail(error: unknown): string {
  return error instanceof ApiError
    ? error.detail
    : error instanceof Error
      ? error.message
      : 'The ledger timeline could not be loaded.'
}

function Feedback({
  tone,
  children,
}: {
  tone: 'success' | 'warning' | 'danger'
  children: string
}) {
  return (
    <div className={`alert alert--${tone} drawer__feedback`} role="status">
      {children}
    </div>
  )
}

export function PaymentTimelineDrawer({
  payment,
  onClose,
}: {
  payment: AdminPayment
  onClose: () => void
}) {
  const events = usePaymentEvents(payment.paymentId)
  const queryClient = useQueryClient()
  const closeRef = useRef<HTMLButtonElement>(null)
  const [feedback, setFeedback] = useState<{
    tone: 'success' | 'warning' | 'danger'
    text: string
  } | null>(null)

  useEffect(() => {
    const previousFocus =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    closeRef.current?.focus()
    document.body.style.overflow = 'hidden'

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
      }
    }
    document.addEventListener('keydown', onKeyDown)

    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
      previousFocus?.focus()
    }
  }, [onClose])

  const refresh = useMutation({
    mutationFn: () => api.refreshPayment(payment.paymentId),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ['payments'] })
      void queryClient.invalidateQueries({
        queryKey: ['payment-events', payment.paymentId],
      })
      setFeedback(
        result.refreshed
          ? {
              tone: 'success',
              text: `PayPal now reports ${humanize(result.state)}${
                result.rawStatus ? ` (${result.rawStatus})` : ''
              }.`,
            }
          : {
              tone: 'warning',
              text:
                result.reason ??
                'Nothing was refreshed because this payment has no batch to poll.',
            },
      )
    },
    onError: (error) => {
      setFeedback({ tone: 'danger', text: eventDetail(error) })
    },
  })

  const canRefresh =
    payment.state === 'SUBMITTED' && payment.paypalBatchId !== null

  return (
    <div className="drawer-layer">
      <button
        type="button"
        className="drawer__scrim"
        aria-label="Close payment timeline"
        onClick={onClose}
      />

      <aside
        className="drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="payment-drawer-title"
      >
        <header className="drawer__head">
          <div className="drawer__heading">
            <span className="eyebrow">Payment timeline</span>
            <h2 className="drawer__title" id="payment-drawer-title">
              {formatMoney(payment.amountMinor, payment.currency)} to{' '}
              {payment.payeeEmail}
            </h2>
            <span className="drawer__id mono" title={payment.paymentId}>
              {shortId(payment.paymentId)}
            </span>
          </div>
          <button
            ref={closeRef}
            type="button"
            className="icon-btn"
            onClick={onClose}
            aria-label="Close payment timeline"
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
          <div className="drawer__badges">
            <PaymentStateBadge state={payment.state} />
            <DecisionBadge outcome={payment.decisionOutcome} />
            {payment.rawStatus ? (
              <span className="count-chip mono">{payment.rawStatus}</span>
            ) : null}
          </div>

          <dl className="facts">
            <div className="facts__row">
              <dt>Agent</dt>
              <dd>{payment.agentName}</dd>
            </div>
            <div className="facts__row">
              <dt>Payee</dt>
              <dd className="mono">{payment.payeeEmail}</dd>
            </div>
            <div className="facts__row">
              <dt>Memo</dt>
              <dd>{payment.memo || '—'}</dd>
            </div>
            <div className="facts__row">
              <dt>PayPal batch</dt>
              <dd className="mono">{payment.paypalBatchId ?? '—'}</dd>
            </div>
            <div className="facts__row">
              <dt>Correlation id</dt>
              <dd className="mono facts__id">{payment.paymentId}</dd>
            </div>
            <div className="facts__row">
              <dt>Created</dt>
              <dd>{formatDateTime(payment.createdAt)}</dd>
            </div>
            <div className="facts__row">
              <dt>Updated</dt>
              <dd>{formatDateTime(payment.updatedAt)}</dd>
            </div>
          </dl>

          {payment.decisionReasons.length > 0 ? (
            <div className="drawer__reasons">
              <h3 className="drawer__section-title">Policy reasons</h3>
              <ul className="reason-list">
                {payment.decisionReasons.map((reason) => (
                  <li className="reason-list__item" key={reason.code}>
                    <code>{reason.code}</code>
                    <span>{reason.message}</span>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          <div className="drawer__refresh">
            <button
              type="button"
              className="btn btn--small"
              onClick={() => {
                setFeedback(null)
                refresh.mutate()
              }}
              disabled={!canRefresh || refresh.isPending}
              title={
                canRefresh
                  ? 'Poll PayPal for the latest batch status'
                  : 'Only a submitted payment with a PayPal batch can be polled'
              }
            >
              {refresh.isPending ? 'Refreshing…' : 'Refresh from PayPal'}
            </button>
            <span className="muted drawer__refresh-note">
              Polls PayPal once; never resubmits.
            </span>
          </div>

          {feedback ? (
            <Feedback tone={feedback.tone}>{feedback.text}</Feedback>
          ) : null}

          <section className="timeline" aria-labelledby="timeline-title">
            <div className="timeline__head">
              <h3 className="drawer__section-title" id="timeline-title">
                Ledger events
              </h3>
              {events.data ? (
                <span className="count-chip">{events.data.length} events</span>
              ) : null}
            </div>

            {events.isPending ? (
              <div className="timeline__loading" role="status" aria-busy="true">
                <span className="skeleton skeleton--row" />
                <span className="skeleton skeleton--row" />
                <span className="skeleton skeleton--row" />
              </div>
            ) : events.isError ? (
              <div className="drawer-state drawer-state--error" role="alert">
                <p>{eventDetail(events.error)}</p>
                <button
                  type="button"
                  className="btn btn--small"
                  onClick={() => void events.refetch()}
                >
                  Try again
                </button>
              </div>
            ) : events.data.length === 0 ? (
              <p className="drawer-state">No ledger events recorded yet.</p>
            ) : (
              <>
                {events.isFetching ? (
                  <p className="timeline__updating muted">Updating…</p>
                ) : null}
                <ol className="timeline__list">
                  {events.data.map((event) => (
                    <li className="timeline__item" key={event.id}>
                      <span
                        className={`timeline__dot timeline__dot--${eventTone(event)}`}
                        aria-hidden="true"
                      />
                      <div className="timeline__card">
                        <div className="timeline__top">
                          <strong>{humanize(event.eventType)}</strong>
                          <time dateTime={event.createdAt}>
                            {formatDateTime(event.createdAt)}
                          </time>
                        </div>
                        <p className="timeline__actor">by {event.actor}</p>
                        {Object.keys(event.payload).length > 0 ? (
                          <pre className="timeline__payload">
                            {JSON.stringify(event.payload, null, 2)}
                          </pre>
                        ) : null}
                      </div>
                    </li>
                  ))}
                </ol>
              </>
            )}
          </section>
        </div>
      </aside>
    </div>
  )
}
