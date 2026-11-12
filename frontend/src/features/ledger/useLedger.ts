import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { PaymentState } from '../../api/types'

export interface LedgerFilters {
  page: number
  size: number
  status: PaymentState | ''
  agentId: string
}

/**
 * The ledger page. Status and agent filters are applied by the server so they
 * search the whole ledger, not just the rows already loaded. Polling keeps the
 * grid current while webhooks and the reconciler move payments.
 */
export function useLedger(filters: LedgerFilters) {
  return useQuery({
    queryKey: ['payments', filters.page, filters.size, filters.status, filters.agentId],
    queryFn: () =>
      api.listPayments({
        page: filters.page,
        size: filters.size,
        status: filters.status || undefined,
        agentId: filters.agentId || undefined,
      }),
    placeholderData: keepPreviousData,
    refetchInterval: 3_000,
  })
}

export function usePaymentEvents(paymentId: string | null) {
  return useQuery({
    queryKey: ['payment-events', paymentId],
    queryFn: () => {
      if (!paymentId) {
        throw new Error('paymentId is required')
      }
      return api.paymentEvents(paymentId)
    },
    enabled: paymentId !== null,
    refetchInterval: 3_000,
  })
}
