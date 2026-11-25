import type { Field } from './model'

// The search box searches every searchable field (task 0022, quickSearchRules.ts and QuickSearch.tsx). Its
// placeholder still names the two fields people use most: the first digits-only identifier, and the first text
// field matched by Contains or Starts with ("Contract number or borrower").

type BoxTargets = { identifier?: Field; text?: Field }

function boxTargets(fields: Field[]): BoxTargets {
  const searchable = fields.filter((f) => f.searchable && f.type !== 'DATE')
  return {
    identifier: searchable.find((f) => f.identifier && f.normalizationRaw === 'DIGITS_ONLY'),
    text: searchable.find((f) => f.normalizationRaw !== 'DIGITS_ONLY' && (f.mode === 'PARTIAL' || f.mode === 'PREFIX')),
  }
}

/** Names the placeholder's fields, from the source's own labels. */
export function boxLabel(fields: Field[]): string | null {
  const { identifier, text } = boxTargets(fields)
  if (identifier && text) return `${identifier.label} or ${text.label}`
  return identifier?.label ?? text?.label ?? null
}

export const MODE_WORD = { EXACT: 'is', PREFIX: 'starts with', PARTIAL: 'contains' } as const
const MODE_HINT = { EXACT: 'Exact', PREFIX: 'Starts with', PARTIAL: 'Contains' } as const

/** How a field is found, beside its label. Task 0078: a sensitive field only by its whole value; Contains without its index is Starts with. */
export function modeHint(f: Field): string {
  if (f.sensitive) return 'Sensitive · whole value only'
  return f.partialAsPrefix ? 'Starts with (Contains isn’t set up)' : MODE_HINT[f.mode]
}

/** Task 0079: the longest value a field can have (the server's limit). */
export const FIELD_VALUE_MAX = 512

/** Where only whole values match (quarantine by filter): a sensitive field is marked, never offered by a part. */
export const wholeHint = (f: Field) => (f.sensitive ? 'Sensitive · whole value' : 'Exact')

/** What searching everywhere leaves out, in the source's own labels; null when it leaves out nothing. */
export function notEverywhere(fields: Field[]): string | null {
  const labels = fields.filter((f) => f.searchable && f.sensitive).map((f) => f.label)
  return labels.length ? `Only by the whole value, in Filters or below: ${labels.join(', ')}` : null
}

/**
 * Task 0020 (item 6): the hint inside an empty Filters box, from the field's settings only (never its name).
 * The settings hold no length, so a digits-only field says "Digits only", not a digit count. Dates show the
 * browser's own date pattern.
 */
export function filterHint(f: Field): string | undefined {
  if (f.type === 'DATE') return undefined
  if (f.normalizationRaw === 'DIGITS_ONLY') return 'Digits only'
  if (f.sensitive) return 'The whole value'
  if (f.mode === 'EXACT') return 'Any'
  if (f.mode === 'PREFIX') return 'The first letters'
  if (f.mode === 'PARTIAL') return 'Any part'
  return undefined
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
