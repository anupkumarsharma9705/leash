import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { UpdatePolicyRequest, AdminRotatedKey } from '../../api/types'
import { agentsQueryKey } from './useAgents'

function useInvalidateAgents() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: agentsQueryKey })
}

/** Kill switch. The server is idempotent, so a repeat suspend is harmless. */
export function useSuspendAgent() {
  const invalidate = useInvalidateAgents()
  return useMutation({
    mutationFn: (agentId: string) => api.suspendAgent(agentId),
    onSuccess: () => void invalidate(),
  })
}

export function useResumeAgent() {
  const invalidate = useInvalidateAgents()
  return useMutation({
    mutationFn: (agentId: string) => api.resumeAgent(agentId),
    onSuccess: () => void invalidate(),
  })
}

/** Rotate API key. Returns the new plaintext key once. */
export function useRotateKey() {
  const invalidate = useInvalidateAgents()
  return useMutation<AdminRotatedKey, Error, string>({
    mutationFn: (agentId: string) => api.rotateKey(agentId),
    onSuccess: () => void invalidate(),
  })
}

/**
 * Policy edits take the same agent row lock as a payment decision, so the
 * response is the authoritative new snapshot; the list is refreshed anyway.
 */
export function useUpdatePolicy(agentId: string) {
  const invalidate = useInvalidateAgents()
  return useMutation({
    mutationFn: (body: UpdatePolicyRequest) => api.updatePolicy(agentId, body),
    onSuccess: () => void invalidate(),
  })
}
