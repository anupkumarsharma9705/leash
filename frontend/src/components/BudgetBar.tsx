import type { AdminBudget } from '../api/types'
import { formatMoney } from '../lib/format'

/**
 * Spent and reserved against the weekly limit. Percentage widths are display
 * only; the amounts themselves remain integer minor units.
 */
export function BudgetBar({ budget }: { budget: AdminBudget }) {
  const limit = Math.max(0, budget.limitMinor)
  const spent = Math.max(0, budget.spentMinor)
  const reserved = Math.max(0, budget.reservedMinor)
  const spentPercent = limit === 0 ? 0 : Math.min(100, (spent / limit) * 100)
  const reservedPercent =
    limit === 0 ? 0 : Math.min(100 - spentPercent, (reserved / limit) * 100)

  return (
    <div className="budget">
      <div
        className="budget__track"
        role="img"
        aria-label={`${formatMoney(spent)} spent and ${formatMoney(reserved)} reserved of a ${formatMoney(limit)} weekly limit`}
      >
        <span className="budget__spent" style={{ width: `${spentPercent}%` }} />
        <span className="budget__reserved" style={{ width: `${reservedPercent}%` }} />
      </div>
      <div className="budget__meta">
        <span>
          <b>{formatMoney(spent)}</b> spent
        </span>
        <span>{formatMoney(reserved)} reserved</span>
        <span className="budget__remaining">{formatMoney(budget.remainingMinor)} left</span>
      </div>
    </div>
  )
}
