import type { Field } from './data'

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
