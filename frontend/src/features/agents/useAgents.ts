import { useQuery } from '@tanstack/react-query'
import { api } from '../../api/client'

export const agentsQueryKey = ['agents'] as const

/** Live agent registry. Budgets move as payments land, so poll while visible. */
export function useAgents() {
  return useQuery({
    queryKey: agentsQueryKey,
    queryFn: api.listAgents,
    refetchInterval: 5_000,
  })
}
