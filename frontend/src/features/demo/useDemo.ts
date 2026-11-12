import { useMutation, useQuery } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { DemoMode } from '../../api/types'

export const demoStatusQueryKey = ['demo', 'status'] as const

/**
 * The demo runner's availability. Outside the backend `demo` profile the route
 * answers 404, so a missing runner is a normal state, not an error worth
 * retrying.
 */
export function useDemoStatus() {
  return useQuery({
    queryKey: demoStatusQueryKey,
    queryFn: api.demoStatus,
    retry: false,
    staleTime: 30_000,
  })
}

export function useRunDemo() {
  return useMutation({
    mutationFn: (mode: DemoMode) => api.runDemo(mode),
  })
}
