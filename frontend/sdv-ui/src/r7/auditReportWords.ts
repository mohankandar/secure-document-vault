import type { AuditReport } from './liveApi'

/**
 * Task 0050: the words and checks of the sealed audit report screen, kept apart so they can be tested. The fingerprint
 * of a file is worked out here in the browser, so a file being checked never leaves the person's computer: only its
 * fingerprint is sent.
 */

/** The SHA-256 of these bytes, as 64 lowercase hexadecimal characters (the server's fingerprint). */
export async function sha256Hex(bytes: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/** A whole fingerprint as pasted: spaces and line breaks ignored, any case. Null when it isn't 64 hex characters. */
export function pastedFingerprint(text: string): string | null {
  const value = text.replace(/\s+/g, '').toLowerCase()
  return /^[0-9a-f]{64}$/.test(value) ? value : null
}

const DAY_MS = 24 * 60 * 60 * 1000
const day = (iso: string) => iso.slice(0, 10)

/** The report's days, both included: its end is the start of the day after the last day. */
export function periodDays(report: Pick<AuditReport, 'from' | 'to'>): { first: string; last: string } {
  return { first: day(report.from), last: day(new Date(new Date(report.to).getTime() - DAY_MS).toISOString()) }
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
/** "1 Nov 2026" from "2026-11-01" (a UTC day, shown as written). */
export const dayWords = (d: string) => `${Number(d.slice(8, 10))} ${MONTHS[Number(d.slice(5, 7)) - 1]} ${d.slice(0, 4)}`

/** "1 Nov 2026 to 22 Nov 2026", or one day. */
export function periodWords(report: Pick<AuditReport, 'from' | 'to'>): string {
  const { first, last } = periodDays(report)
  return first === last ? dayWords(first) : `${dayWords(first)} to ${dayWords(last)}`
}

const RESULT_WORDS: Record<string, string> = { SUCCESS: 'Done', DENIED: 'Refused', FAILED: 'Failed' }
const LEVEL_WORDS: Record<string, string> = { STANDARD: 'Standard', CONFIDENTIAL: 'Confidential', HIGHLY_CONFIDENTIAL: 'Highly Confidential' }

/** "Result: Refused; Record: 300000000001", or "None", as the report's stamp says it. */
export function filterWords(filters: Record<string, string>): string {
  const parts: string[] = []
  if (filters.action) parts.push(`Action code: ${filters.action}`)
  if (filters.outcome) parts.push(`Result: ${RESULT_WORDS[filters.outcome] ?? filters.outcome}`)
  if (filters.recordNumber) parts.push(`Record: ${filters.recordNumber}`)
  if (filters.sensitivityLevel) parts.push(`Level: ${LEVEL_WORDS[filters.sensitivityLevel] ?? filters.sensitivityLevel}`)
  return parts.length ? parts.join('; ') : 'None'
}

/** Why the form can't be sent yet, or null. Days are YYYY-MM-DD; the first can't come after the last. */
export function periodProblem(first: string, last: string): string | null {
  if (!first || !last) return 'Choose the first and last day.'
  if (!/^\d{4}-\d{2}-\d{2}$/.test(first) || !/^\d{4}-\d{2}-\d{2}$/.test(last)) return 'Enter the days as day, month and year.'
  if (first > last) return 'The first day comes after the last day.'
  return null
}

/** The first eight and last four characters, for a narrow column; the whole value goes in the title. */
export const shortFingerprint = (f: string) => (f.length > 16 ? `${f.slice(0, 8)}…${f.slice(-4)}` : f)
