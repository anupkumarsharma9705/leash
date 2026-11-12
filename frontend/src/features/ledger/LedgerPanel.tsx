import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import type { GridApi } from 'ag-grid-community'
import { AgGridReact } from 'ag-grid-react'
import { ApiError } from '../../api/client'
import type { AdminPayment, PaymentState } from '../../api/types'
import { EmptyState, ErrorState } from '../../components/StateViews'
import { humanize } from '../../lib/format'
import { gridThemeDark, gridThemeLight } from '../../lib/agGrid'
import { useTheme } from '../../theme/theme'
import { useAgents } from '../agents/useAgents'
import { ledgerColumns, ledgerDefaultColDef } from './columns'
import { PaymentTimelineDrawer } from './PaymentTimelineDrawer'
import { useLedger } from './useLedger'

const PAGE_SIZES = [25, 50, 100]

const STATUS_OPTIONS: PaymentState[] = [
  'PENDING_APPROVAL',
  'APPROVED',
  'SUBMITTED',
  'SUCCEEDED',
  'FAILED',
  'REJECTED',
  'DENIED',
]

function errorDetail(error: unknown): string {
  return error instanceof ApiError
    ? error.detail
    : error instanceof Error
      ? error.message
      : 'The live refresh failed.'
}

export function LedgerPanel() {
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(25)
  const [status, setStatus] = useState<PaymentState | ''>('')
  const [agentId, setAgentId] = useState('')
  const [quickFilter, setQuickFilter] = useState('')
  const [visibleRowCount, setVisibleRowCount] = useState<number | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [selectedSnapshot, setSelectedSnapshot] = useState<AdminPayment | null>(
    null,
  )
  const gridApiRef = useRef<GridApi<AdminPayment> | null>(null)
  const gridContainerRef = useRef<HTMLDivElement | null>(null)

  const theme = useTheme()
  const gridTheme = theme === 'dark' ? gridThemeDark : gridThemeLight
  const ledger = useLedger({ page, size, status, agentId })
  const agents = useAgents()

  useEffect(() => {
    if (!ledger.data) {
      return
    }
    const lastPage = Math.max(0, ledger.data.totalPages - 1)
    if (page > lastPage) {
      // The server's page count can shrink when the ledger is reset. Clamp the
      // cursor to the last real page instead of showing an empty page forever.
      // oxlint-disable-next-line react/set-state-in-effect
      setPage(lastPage)
    }
  }, [ledger.data, page])

  const openPayment = useCallback((payment: AdminPayment) => {
    setSelectedId(payment.paymentId)
    setSelectedSnapshot(payment)
  }, [])

  const closeDrawer = useCallback(() => setSelectedId(null), [])

  const onGridKeyDown = useCallback(
    (event: KeyboardEvent) => {
      if (event.key !== 'Enter' || event.defaultPrevented) {
        return
      }
      const target = event.target as HTMLElement | null
      const cell = target?.closest('.ag-cell')
      if (!cell) {
        return
      }
      const rowIndexValue = cell
        .closest('.ag-row')
        ?.getAttribute('row-index')
      if (rowIndexValue == null) {
        return
      }
      const rowIndex = Number(rowIndexValue)
      const api = gridApiRef.current
      if (!api || !Number.isInteger(rowIndex)) {
        return
      }
      const row = api.getDisplayedRowAtIndex(rowIndex)
      if (row?.data) {
        // Capture phase: AG Grid would otherwise consume Enter first.
        event.preventDefault()
        event.stopPropagation()
        openPayment(row.data)
      }
    },
    [openPayment],
  )

  useEffect(() => {
    const container = gridContainerRef.current
    if (!container) {
      return
    }
    container.addEventListener('keydown', onGridKeyDown, true)
    return () => container.removeEventListener('keydown', onGridKeyDown, true)
  }, [onGridKeyDown, ledger.data])

  const selectedPayment = useMemo(() => {
    if (!selectedId) {
      return null
    }
    return (
      ledger.data?.content.find((payment) => payment.paymentId === selectedId) ??
      selectedSnapshot
    )
  }, [ledger.data, selectedId, selectedSnapshot])

  const hasServerFilters = status !== '' || agentId !== ''
  const hasAnyFilter = hasServerFilters || quickFilter.trim() !== ''

  function changeStatus(next: PaymentState | '') {
    setStatus(next)
    setPage(0)
  }

  function changeAgent(next: string) {
    setAgentId(next)
    setPage(0)
  }

  function changeSize(next: number) {
    setSize(next)
    setPage(0)
  }

  function clearFilters() {
    setStatus('')
    setAgentId('')
    setQuickFilter('')
    setPage(0)
  }

  const total = ledger.data?.totalElements ?? 0
  const totalPages = Math.max(1, ledger.data?.totalPages ?? 1)
  const firstRow = total === 0 ? 0 : page * size + 1
  const lastRow = Math.min((page + 1) * size, total)
  const pageRowCount = ledger.data?.content.length ?? 0
  const visibleRows = visibleRowCount ?? pageRowCount
  const pageFiltered = visibleRows !== pageRowCount

  return (
    <>
      <header className="page-head">
        <div>
          <span className="eyebrow">Money movement</span>
          <h1 className="page-title">Ledger</h1>
          <p className="page-description">
            Every payment request, policy decision, and PayPal status change, newest
            first. Click a row for its append-only timeline.
          </p>
        </div>
        <div className="page-actions">
          <span className="live-pill">
            <span className="live-pill__dot" aria-hidden="true" />
            Live · 3s
          </span>
          <button
            type="button"
            className="btn"
            onClick={() => void ledger.refetch()}
            disabled={ledger.isPending || ledger.isFetching}
          >
            {ledger.isFetching ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </header>

      {ledger.isPending ? (
        <section className="panel ledger-panel" aria-busy="true">
          <div className="panel__header">
            <div>
              <h2 className="panel__title">Payment ledger</h2>
              <p className="panel__subtitle">Loading the newest payments…</p>
            </div>
          </div>
          <div className="ledger-skeleton" role="status">
            {Array.from({ length: 8 }, (_, index) => (
              <span className="skeleton skeleton--row" key={index} />
            ))}
            <span className="sr-only">Loading payments</span>
          </div>
        </section>
      ) : ledger.isError && !ledger.data ? (
        <ErrorState
          error={ledger.error}
          onRetry={() => void ledger.refetch()}
        />
      ) : ledger.data ? (
        <section className="panel ledger-panel" aria-labelledby="ledger-title">
          <div className="panel__header">
            <div>
              <h2 className="panel__title" id="ledger-title">
                Payment ledger
              </h2>
              <p className="panel__subtitle">
                Request, decision, and settlement facts from the audit trail.
              </p>
            </div>
            <span className="count-chip">
              {total.toLocaleString('en-US')} {total === 1 ? 'payment' : 'payments'}
            </span>
          </div>

          <div className="ledger-toolbar">
            <div className="ledger-toolbar__group ledger-toolbar__group--grow">
              <label className="ledger-search">
                <span className="sr-only">Search this page</span>
                <svg
                  className="ledger-search__icon"
                  viewBox="0 0 20 20"
                  width="15"
                  height="15"
                  aria-hidden="true"
                >
                  <circle
                    cx="8.5"
                    cy="8.5"
                    r="5.2"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.6"
                  />
                  <path
                    d="m12.4 12.4 4.1 4.1"
                    stroke="currentColor"
                    strokeWidth="1.6"
                    strokeLinecap="round"
                  />
                </svg>
                <input
                  className="ledger-search__input"
                  type="search"
                  placeholder="Search this page by payee, agent, reason…"
                  value={quickFilter}
                  onChange={(event) => setQuickFilter(event.target.value)}
                />
              </label>
            </div>

            <div className="ledger-toolbar__group">
              <label className="ledger-field">
                <span className="ledger-field__label">Status</span>
                <select
                  className="select"
                  value={status}
                  onChange={(event) =>
                    changeStatus(event.target.value as PaymentState | '')
                  }
                >
                  <option value="">All statuses</option>
                  {STATUS_OPTIONS.map((option) => (
                    <option key={option} value={option}>
                      {humanize(option)}
                    </option>
                  ))}
                </select>
              </label>

              <label className="ledger-field">
                <span className="ledger-field__label">Agent</span>
                <select
                  className="select"
                  value={agentId}
                  onChange={(event) => changeAgent(event.target.value)}
                >
                  <option value="">All agents</option>
                  {agents.data?.map((agent) => (
                    <option key={agent.agentId} value={agent.agentId}>
                      {agent.name}
                    </option>
                  ))}
                </select>
              </label>

              <label className="ledger-field">
                <span className="ledger-field__label">Rows</span>
                <select
                  className="select"
                  value={size}
                  onChange={(event) => changeSize(Number(event.target.value))}
                >
                  {PAGE_SIZES.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          </div>

          <p className="ledger-scope">
            Status and agent filters apply to the whole ledger. Search and column
            filters narrow the current page.
          </p>

          {ledger.isError ? (
            <div className="alert alert--warning ledger-alert" role="alert">
              Live refresh failed: {errorDetail(ledger.error)}. The rows below are
              the last successful read.{' '}
              <button
                type="button"
                className="link-btn"
                onClick={() => void ledger.refetch()}
              >
                Retry
              </button>
            </div>
          ) : null}

          {total === 0 ? (
            <EmptyState
              title={
                hasAnyFilter ? 'No payments match these filters' : 'No payments yet'
              }
              description={
                hasAnyFilter
                  ? 'Widen the status or agent filter, or clear the search, to see more of the ledger.'
                  : 'Once an agent calls POST /api/v1/payments, its requests and decisions appear here in real time.'
              }
              action={
                hasServerFilters ? (
                  <button type="button" className="btn" onClick={clearFilters}>
                    Clear filters
                  </button>
                ) : undefined
              }
            />
          ) : (
            <>
              <div className="ledger-grid" ref={gridContainerRef}>
                <AgGridReact<AdminPayment>
                  theme={gridTheme}
                  rowData={ledger.data.content}
                  columnDefs={ledgerColumns}
                  defaultColDef={ledgerDefaultColDef}
                  getRowId={(params) => params.data.paymentId}
                  getRowClass={(params) =>
                    params.data?.paymentId === selectedId
                      ? 'ledger-row--active'
                      : undefined
                  }
                  quickFilterText={quickFilter}
                  onGridReady={(event) => {
                    gridApiRef.current = event.api
                  }}
                  onModelUpdated={(event) => {
                    const next = event.api.getDisplayedRowCount()
                    setVisibleRowCount((current) =>
                      current === next ? current : next,
                    )
                  }}
                  onRowClicked={(event) => {
                    if (event.data) {
                      openPayment(event.data)
                    }
                  }}
                  overlayNoRowsTemplate='<span class="ledger-overlay">No payments to show.</span>'
                  localeText={{
                    noMatchingRows:
                      'No rows on this page match the search or column filters.',
                    noRowsToShow: 'No payments to show.',
                  }}
                  animateRows={false}
                  tooltipShowDelay={250}
                />
              </div>

              <footer className="pager">
                <span className="pager__summary">
                  Rows {firstRow}–{lastRow} of {total.toLocaleString('en-US')}
                  {pageFiltered ? (
                    <span className="pager__filtered">
                      {' '}
                      · {visibleRows} visible after page filters
                    </span>
                  ) : null}
                </span>
                <div className="pager__controls">
                  <button
                    type="button"
                    className="btn btn--small"
                    onClick={() => setPage((current) => Math.max(0, current - 1))}
                    disabled={page === 0 || ledger.isFetching}
                  >
                    Previous
                  </button>
                  <span className="pager__page">
                    Page {page + 1} of {totalPages}
                  </span>
                  <button
                    type="button"
                    className="btn btn--small"
                    onClick={() =>
                      setPage((current) => Math.min(totalPages - 1, current + 1))
                    }
                    disabled={page + 1 >= totalPages || ledger.isFetching}
                  >
                    Next
                  </button>
                </div>
              </footer>
            </>
          )}
        </section>
      ) : null}

      {selectedPayment ? (
        <PaymentTimelineDrawer
          key={selectedPayment.paymentId}
          payment={selectedPayment}
          onClose={closeDrawer}
        />
      ) : null}
    </>
  )
}
