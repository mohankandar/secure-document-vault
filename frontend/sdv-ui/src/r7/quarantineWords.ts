import type { SensitivityLevel } from '../api'
import type { QuarantineFilter, QuarantinedDocument } from './liveApi'

/**
 * Task 0049: the words and small rules of the quarantine screens, kept apart so they can be tested. Nothing here names
 * a level to someone who can't open it: the server leaves those documents out and only counts them.
 */

export const UNAVAILABLE = 'Temporarily unavailable'
/** What everyone but a reviewer reads, in search rows, the record panel and the viewer: never the level or why. */
export const UNAVAILABLE_SENTENCE = 'This document is temporarily unavailable.'

const LEVEL_SHORT: Record<SensitivityLevel, string> = { STANDARD: 'Standard', CONFIDENTIAL: 'Confidential', HIGHLY_CONFIDENTIAL: 'Highly Confidential' }

export const plural = (n: number, word: string, many = word + 's') => `${n} ${n === 1 ? word : many}`

/** "3 Standard, 1 Confidential": the levels with a count, in level order; "None" when all are zero. */
export function levelsLine(standard: number, confidential: number, highlyConfidential: number): string {
  const parts: [number, SensitivityLevel][] = [[standard, 'STANDARD'], [confidential, 'CONFIDENTIAL'], [highlyConfidential, 'HIGHLY_CONFIDENTIAL']]
  const shown = parts.filter(([n]) => n > 0).map(([n, l]) => `${n} ${LEVEL_SHORT[l]}`)
  return shown.length ? shown.join(', ') : 'None'
}

export const scopeWords = (scope: 'DOCUMENT' | 'FILTER') => (scope === 'FILTER' ? 'By filter' : 'One by one')

/** The review list: oldest received first; documents without a received date last; then by when they were quarantined. */
export function oldestFirst(documents: QuarantinedDocument[]): QuarantinedDocument[] {
  return [...documents].sort((a, b) => {
    if (a.receivedAt !== b.receivedAt) {
      if (!a.receivedAt) return 1
      if (!b.receivedAt) return -1
      return a.receivedAt.localeCompare(b.receivedAt)
    }
    return a.quarantinedAt.localeCompare(b.quarantinedAt)
  })
}

const DAY = /^\d{4}-\d{2}-\d{2}$/
/**
 * Why the filter can't be counted yet, or null when it can. Both received days are needed (whole UTC days, as the
 * server counts them); a field value needs a sending system (task 0048, decision 4).
 */
export function filterProblem(f: QuarantineFilter): string | null {
  if (!f.receivedFrom || !f.receivedTo) return 'Choose both received dates.'
  if (!DAY.test(f.receivedFrom) || !DAY.test(f.receivedTo)) return 'Enter the dates as day, month and year.'
  if (f.receivedFrom > f.receivedTo) return 'The first received date comes after the last one.'
  const fields = Object.values(f.fields ?? {}).filter((v) => v.trim())
  if (fields.length && !f.sourceId) return 'Choose the sending system the fields belong to.'
  return null
}

/** Name and reason as the server takes them: a name of 1 to 200 characters, a reason of 3 to 500. */
export const NAME_MAX = 200
export const REASON_MIN = 3
export const REASON_MAX = 500
export const nameReady = (name: string) => name.trim().length > 0 && name.trim().length <= NAME_MAX
export const reasonReady = (reason: string) => reason.trim().length >= REASON_MIN && reason.trim().length <= REASON_MAX

/** The export note: the server's reference and SHA-256 fingerprint, so the file can be matched to the audit. */
export function exportNote(reference: string | null, fingerprint: string | null): string {
  const parts = ['The file is saved. The export is recorded in the audit.']
  if (reference) parts.push(`Reference ${reference}.`)
  if (fingerprint) parts.push(`SHA-256 fingerprint ${fingerprint}.`)
  return parts.join(' ')
}
