import type { Field, Source } from './model'
import type { RecordSummary } from '../api'
import type { RecentRecord } from './liveApi'

// Task 0022: quick search across all fields. What the search box offers while the person types, and the rules the
// server applies to "anywhere" text (BusinessExperienceService.anywhereCriterion), so the list never offers a field
// the server wouldn't try.

/** "Anywhere" text after trimming: the server refuses anything shorter or longer. */
export const ANYWHERE_MIN = 2
export const ANYWHERE_MAX = 100

/** Why the text can't be searched everywhere, in plain words; null when it can. */
export function anywhereProblem(text: string): string | null {
  const t = text.trim()
  if (t.length < ANYWHERE_MIN) return `Type at least ${ANYWHERE_MIN} characters to search.`
  if (t.length > ANYWHERE_MAX) return `Search text can be at most ${ANYWHERE_MAX} characters.`
  return null
}

/**
 * The fields the text can match, in the sending system's own order: every searchable field except dates, and
 * digits-only fields only when the text has a digit (the same rule the server uses for "anywhere").
 */
export function fieldsFor(text: string, fields: Field[]): Field[] {
  const hasDigit = /\d/.test(text)
  return fields.filter((f) => f.searchable && f.type !== 'DATE' && (f.normalizationRaw !== 'DIGITS_ONLY' || hasDigit))
}

const has = (value: string | null | undefined, needle: string) => !!value && value.toLocaleLowerCase().includes(needle)

/** The person's own recently opened records whose number, title or listed fields contain the text (no server call). */
export function recentMatches(text: string, recent: RecentRecord[] | null, limit = 4): RecentRecord[] {
  const needle = text.trim().toLocaleLowerCase()
  if (!recent || needle.length < ANYWHERE_MIN) return []
  return recent.filter(({ record: r }) => has(r.title, needle)
    || r.identifiers.some((i) => has(i.value, needle))
    || r.metadataPreview.some((m) => has(m.value, needle))).slice(0, limit)
}

/** Criteria that find exactly this record in Records: each of its searchable identifiers, matched exactly. */
export function identifierCriteria(record: RecordSummary, source: Source | undefined): Record<string, string> {
  const searchable = new Set(source?.fields.filter((f) => f.searchable).map((f) => f.key) ?? [])
  return Object.fromEntries(record.identifiers.filter((i) => searchable.has(i.fieldKey) && i.value).map((i) => [i.fieldKey, i.value]))
}

/** The text split around each place the needle occurs (ignoring case), for marking the matching text. */
export function markParts(text: string, needle: string | undefined): { text: string; mark: boolean }[] {
  const n = needle?.trim().toLocaleLowerCase()
  if (!n || !text) return [{ text, mark: false }]
  const lower = text.toLocaleLowerCase()
  const parts: { text: string; mark: boolean }[] = []
  let at = 0
  for (let i = lower.indexOf(n); i >= 0 && n.length > 0; i = lower.indexOf(n, at)) {
    if (i > at) parts.push({ text: text.slice(at, i), mark: false })
    parts.push({ text: text.slice(i, i + n.length), mark: true })
    at = i + n.length
  }
  if (at < text.length) parts.push({ text: text.slice(at), mark: false })
  return parts.length ? parts : [{ text, mark: false }]
}

export type MatchedField = { fieldKey: string; displayLabel: string; value: string }

/**
 * The "Matched …" line under a result's title: the best-ranked matched field that the row doesn't already show
 * (not the number column, not the extra column, not part of the title). Null when every match is visible.
 */
export function hiddenMatch(matched: MatchedField[] | undefined, shown: { numberLabel: string; extraLabel?: string; title?: string | null }): MatchedField | null {
  const title = shown.title?.toLocaleLowerCase() ?? ''
  return (matched ?? []).find((m) => m.displayLabel !== shown.numberLabel && m.displayLabel !== shown.extraLabel
    && !(title && m.value && title.includes(m.value.toLocaleLowerCase()))) ?? null
}
