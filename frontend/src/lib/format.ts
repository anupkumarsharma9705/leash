/**
 * Display formatting. Money stays integer minor units until the final string;
 * no float arithmetic is ever used for an amount.
 */

export function formatMoney(amountMinor: number, currency = 'USD'): string {
  const sign = amountMinor < 0 ? '\u2212' : ''
  const absolute = Math.abs(Math.trunc(amountMinor))
  const whole = Math.floor(absolute / 100).toLocaleString('en-US')
  const cents = String(absolute % 100).padStart(2, '0')
  const prefix = currency === 'USD' ? '$' : `${currency} `
  return `${sign}${prefix}${whole}.${cents}`
}

export function formatDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '\u2014'
  }
  return date.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

export function humanize(value: string): string {
  const words = value.toLowerCase().replaceAll('_', ' ').replaceAll('paypal', 'PayPal')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

export function shortId(value: string): string {
  return value.slice(0, 8)
}

/**
 * Parses a dollar string such as `"1,250.50"` into integer minor units without
 * ever converting the amount to a binary float.
 */
export function parseMoneyToMinor(value: string): number | null {
  const cleaned = value.trim().replace(/^\$/, '').replaceAll(',', '')
  if (!/^\d+(\.\d{0,2})?$/.test(cleaned)) {
    return null
  }
  const [whole, fraction = ''] = cleaned.split('.')
  const digits = `${whole}${fraction.padEnd(2, '0')}`
  const minor = Number(digits)
  return Number.isSafeInteger(minor) ? minor : null
}

/** Renders integer minor units as a plain dollar input value. */
export function minorToInput(amountMinor: number): string {
  const absolute = Math.abs(Math.trunc(amountMinor))
  const whole = Math.floor(absolute / 100)
  const cents = String(absolute % 100).padStart(2, '0')
  return `${amountMinor < 0 ? '-' : ''}${whole}.${cents}`
}
