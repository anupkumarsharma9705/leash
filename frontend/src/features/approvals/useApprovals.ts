import { useQuery } from '@tanstack/react-query'
import { api } from '../../api/client'

export const approvalsQueryKey = ['approvals'] as const

/**
 * The pending approval queue. Escalations arrive from agents at any time, so
 * poll while the panel is mounted.
 */
export function useApprovals() {
  return useQuery({
    queryKey: approvalsQueryKey,
    queryFn: api.listApprovals,
    refetchInterval: 5_000,
  })
}
