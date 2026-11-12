import type { ColDef } from 'ag-grid-community'
import type { AdminPayment } from '../../api/types'
import { formatDateTime, formatMoney } from '../../lib/format'
import { AgentCell, DecisionCell, StateCell } from './cells'

function reasonCodes(payment: AdminPayment | undefined): string {
  if (!payment || payment.decisionReasons.length === 0) {
    return ''
  }
  return payment.decisionReasons.map((reason) => reason.code).join(', ')
}

function reasonMessages(payment: AdminPayment | undefined): string {
  if (!payment || payment.decisionReasons.length === 0) {
    return 'No policy violation'
  }
  return payment.decisionReasons.map((reason) => reason.message).join('\n')
}

/**
 * Ledger grid columns. Sorting and column filters run over the current server
 * page; the toolbar's status and agent selects narrow the whole ledger on the
 * server. The payment id is the correlation id and stays available but hidden.
 */
export const ledgerColumns: ColDef<AdminPayment>[] = [
  {
    headerName: 'Time',
    field: 'createdAt',
    width: 165,
    filter: 'agTextColumnFilter',
    sort: 'desc',
    valueFormatter: (params) => formatDateTime(params.value ?? ''),
    cellClass: 'nowrap',
  },
  {
    headerName: 'Agent',
    field: 'agentName',
    width: 190,
    filter: 'agTextColumnFilter',
    cellRenderer: AgentCell,
  },
  {
    headerName: 'Payee',
    field: 'payeeEmail',
    minWidth: 200,
    filter: 'agTextColumnFilter',
    cellClass: 'ledger-payee',
    tooltipValueGetter: (params) => params.value,
  },
  {
    headerName: 'Amount',
    field: 'amountMinor',
    width: 120,
    filter: 'agNumberColumnFilter',
    cellClass: 'money cell-amount',
    headerClass: 'cell-amount-header',
    valueFormatter: (params) =>
      formatMoney(params.value ?? 0, params.data?.currency ?? 'USD'),
  },
  {
    headerName: 'Decision',
    field: 'decisionOutcome',
    width: 125,
    filter: 'agTextColumnFilter',
    cellRenderer: DecisionCell,
  },
  {
    headerName: 'Reason',
    colId: 'reason',
    minWidth: 220,
    flex: 1,
    filter: 'agTextColumnFilter',
    valueGetter: (params) => reasonCodes(params.data),
    valueFormatter: (params) => params.value || '—',
    tooltipValueGetter: (params) => reasonMessages(params.data),
    cellClass: 'cell-reason',
  },
  {
    headerName: 'PayPal status',
    field: 'state',
    width: 175,
    filter: 'agTextColumnFilter',
    cellRenderer: StateCell,
  },
  {
    headerName: 'Correlation ID',
    field: 'paymentId',
    width: 280,
    hide: true,
    filter: 'agTextColumnFilter',
  },
]

export const ledgerDefaultColDef: ColDef<AdminPayment> = {
  sortable: true,
  filter: true,
  resizable: true,
  suppressHeaderMenuButton: true,
}
