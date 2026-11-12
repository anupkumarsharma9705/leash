import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'

/** A small public liveness pulse in the header. */
export function HealthIndicator() {
  const health = useQuery({
    queryKey: ['health'],
    queryFn: api.health,
    retry: false,
    refetchInterval: 30_000,
  })

  const tone = health.isPending
    ? 'pending'
    : health.isError || health.data?.status !== 'UP'
      ? 'offline'
      : 'online'
  const label =
    tone === 'pending' ? 'Checking API' : tone === 'online' ? 'API online' : 'API offline'
  const detail =
    health.isError
      ? health.error instanceof ApiError
        ? health.error.detail
        : 'The health endpoint could not be reached.'
      : undefined

  return (
    <span className={`health health--${tone}`} title={detail} role="status">
      <span className="health__dot" aria-hidden="true" />
      {label}
    </span>
  )
}
