import type { ReactNode } from 'react'
import { ApiError } from '../api/client'

export function LoadingState({
  label = 'Loading',
  rows = 4,
}: {
  label?: string
  rows?: number
}) {
  return (
    <div className="panel" role="status" aria-busy="true">
      <div className="panel__header">
        <div className="skeleton skeleton--heading" />
      </div>
      <div className="skeleton-rows">
        {Array.from({ length: rows }, (_, index) => (
          <div className="skeleton skeleton--row" key={index} />
        ))}
      </div>
      <span className="sr-only">{label}</span>
    </div>
  )
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className="state state--empty">
      <span className="state__mark" aria-hidden="true">
        0
      </span>
      <h2>{title}</h2>
      <p>{description}</p>
      {action ? <div className="state__action">{action}</div> : null}
    </div>
  )
}

export function ErrorState({
  error,
  onRetry,
}: {
  error: unknown
  onRetry?: () => void
}) {
  const apiError = error instanceof ApiError ? error : null
  const message =
    apiError?.detail ??
    (error instanceof Error ? error.message : 'An unexpected error occurred.')

  return (
    <div className="state state--error" role="alert">
      <span className="state__mark state__mark--error" aria-hidden="true">
        !
      </span>
      <h2>Could not load this view</h2>
      <p>{message}</p>
      {apiError?.correlationId ? (
        <p className="state__meta">
          Correlation id <code>{apiError.correlationId}</code>
        </p>
      ) : null}
      {onRetry ? (
        <button type="button" className="btn" onClick={onRetry}>
          Try again
        </button>
      ) : null}
    </div>
  )
}
