import type { Field } from './model'

// The server has no free-text search. The search box routes what the person types to ONE configured field:
//  1. Input that looks like a number (digits, optionally with spaces, dashes, dots or slashes) goes to the
//     first identifier field, in display order, whose normalization is DIGITS_ONLY. The server strips the
//     separators, as it does for the Filters field.
//  2. Anything else goes to the first searchable text field whose match is Contains or Starts with.
//  3. If neither field exists, nothing is searched and the person is pointed to Filters.

export type BoxTargets = { identifier?: Field; text?: Field }
export type BoxRoute = { ok: true; field: Field; value: string } | { ok: false; message: string }

const NUMBER_SHAPE = /^[\d\s\-./]+$/

export function boxTargets(fields: Field[]): BoxTargets {
  const searchable = fields.filter((f) => f.searchable && f.type !== 'DATE')
  return {
    identifier: searchable.find((f) => f.identifier && f.normalizationRaw === 'DIGITS_ONLY'),
    text: searchable.find((f) => f.normalizationRaw !== 'DIGITS_ONLY' && (f.mode === 'PARTIAL' || f.mode === 'PREFIX')),
  }
}

export const looksLikeNumber = (value: string) => NUMBER_SHAPE.test(value) && /\d/.test(value)

export function routeBoxInput(input: string, fields: Field[]): BoxRoute | null {
  const value = input.trim()
  if (!value) return null
  const { identifier, text } = boxTargets(fields)
  if (identifier && looksLikeNumber(value)) return { ok: true, field: identifier, value }
  if (text) return { ok: true, field: text, value }
  if (identifier) return { ok: false, message: `Enter a ${identifier.label} (digits), or use Filters for other fields.` }
  return { ok: false, message: 'This source has no field the search box can use. Use Filters.' }
}

/** Names the fields the box searches, from the source's own labels. */
export function boxLabel(fields: Field[]): string | null {
  const { identifier, text } = boxTargets(fields)
  if (identifier && text) return `${identifier.label} or ${text.label}`
  return identifier?.label ?? text?.label ?? null
}

export const MODE_WORD = { EXACT: 'is', PREFIX: 'starts with', PARTIAL: 'contains' } as const
export const MODE_HINT = { EXACT: 'Exact', PREFIX: 'Starts with', PARTIAL: 'Contains' } as const

/**
 * Task 0020 (item 6): the hint inside an empty Filters box, from the field's settings only (never its name).
 * The settings hold no length, so a digits-only field says "Digits only", not a digit count. Dates show the
 * browser's own date pattern.
 */
export function filterHint(f: Field): string | undefined {
  if (f.type === 'DATE') return undefined
  if (f.normalizationRaw === 'DIGITS_ONLY') return 'Digits only'
  if (f.mode === 'EXACT') return 'Any'
  if (f.mode === 'PREFIX') return 'The first letters'
  if (f.mode === 'PARTIAL') return 'Any part'
  return undefined
}

/**
 * Other identifier fields that accept the same typed number (DIGITS_ONLY, like the field the box used).
 * Offered as one-click suggestions when the box finds nothing; never searched automatically.
 */
export function alternativeFields(used: Field, value: string, fields: Field[]): Field[] {
  if (!looksLikeNumber(value)) return []
  return fields.filter((f) => f.searchable && f.identifier && f.key !== used.key && f.normalizationRaw === 'DIGITS_ONLY')
}

// ---- Date ranges (yyyy-mm-dd, the person's own calendar days) ----

export type Range = { from: string; to: string }
const pad = (n: number) => String(n).padStart(2, '0')
export const isoDay = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const daysAgo = (today: Date, n: number) => { const d = new Date(today); d.setDate(d.getDate() - n); return d }

export const QUICK_RANGES = ['Today', 'Last 7 days', 'Last 30 days', 'This month'] as const
export type QuickRange = (typeof QUICK_RANGES)[number]

export function quickRange(kind: QuickRange, today: Date = new Date()): Range {
  const to = isoDay(today)
  if (kind === 'Today') return { from: to, to }
  if (kind === 'Last 7 days') return { from: isoDay(daysAgo(today, 6)), to }
  if (kind === 'Last 30 days') return { from: isoDay(daysAgo(today, 29)), to }
  return { from: isoDay(new Date(today.getFullYear(), today.getMonth(), 1)), to }
}

/** "1 Nov", or "1 Nov 2025" when not in the current year. */
export function shortDay(day: string, today: Date = new Date()): string {
  const [y, m, d] = day.split('-').map(Number)
  const date = new Date(y, m - 1, d)
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', ...(y !== today.getFullYear() ? { year: 'numeric' } : {}) }).format(date)
}

/** Chip text for a range, e.g. "Received 1 Nov – 15 Nov"; null when the range is empty. */
export function rangeLabel(name: string, r: Range, today: Date = new Date()): string | null {
  if (r.from && r.to) return r.from === r.to ? `${name} ${shortDay(r.from, today)}` : `${name} ${shortDay(r.from, today)} – ${shortDay(r.to, today)}`
  if (r.from) return `${name} from ${shortDay(r.from, today)}`
  if (r.to) return `${name} until ${shortDay(r.to, today)}`
  return null
}
