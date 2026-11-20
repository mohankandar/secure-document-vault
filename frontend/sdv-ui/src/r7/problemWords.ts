// Task 0011 Part A (findings 12 and 18): every problem in plain words. The one place for these sentences.
// The server's processing error codes come from DocumentFinalizationService.classify; a code not listed here
// falls back to "Vero couldn't store this file (code X)."

import type { SensitivityLevel } from '../api'

export type ProblemKind = 'store' | 'stuck' | 'missing'

/** Code → what happened (short, for the list) and the cause (for the detail). */
export const PROCESSING_ERROR_WORDS: Record<string, { short: string; cause: string }> = {
  DOCUMENT_FINALIZATION_FAILED: {
    short: 'Vero couldn’t finish storing the file',
    cause: 'Vero could not finish storing and protecting the file.',
  },
  DURABLE_OBJECT_VALIDATION_FAILED: {
    short: 'The stored copy couldn’t be read back',
    cause: 'Vero wrote the file, but could not read a complete copy back from storage, so it will not show it.',
  },
  STORAGE_SECURITY_VALIDATION_FAILED: {
    short: 'The stored copy failed the security check',
    cause: 'The stored copy did not pass Vero’s security check, so Vero will not show it.',
  },
}
export function processingWords(code: string | null | undefined): { short: string; cause: string } {
  if (code && PROCESSING_ERROR_WORDS[code]) return PROCESSING_ERROR_WORDS[code]
  const text = `Vero couldn't store this file (code ${code || 'unknown'}).`
  return { short: text, cause: text }
}

/** How long, in words: "2 days", "5 hours", "20 minutes". */
export function duration(fromIso: string, now = Date.now()): string {
  const ms = Math.max(0, now - new Date(fromIso).getTime())
  const minutes = Math.round(ms / 60000), hours = Math.round(ms / 3600000), days = Math.round(ms / 86400000)
  if (days >= 1 && hours >= 36) return `${days} day${days === 1 ? '' : 's'}`
  if (hours >= 1 && minutes >= 90) return `${hours} hour${hours === 1 ? '' : 's'}`
  return `${minutes} minute${minutes === 1 ? '' : 's'}`
}

/** A stuck document's state, in words. */
export function stuckWords(status: string, since: string): string {
  const d = duration(since)
  if (status === 'REDACTION_PROCESSING') return `A permanent correction is still running after ${d}`
  if (status === 'RETENTION_DELETING') return `Still being deleted after ${d}`
  return `Still being stored after ${d}`
}

const dayWords = (iso: string) => new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' }).format(new Date(iso))
const times = (n: number) => (n === 1 ? 'once' : n === 2 ? 'twice' : `${n} times`)

export type ProblemFacts = {
  kind: ProblemKind
  /** Task 0027 (0.4): never a file name. The document is described by these instead. */
  documentId?: string
  receivedAt?: string
  pages?: number | null
  level?: SensitivityLevel | null
  source?: string | null
  recordNumber?: string | null
  recordLabel?: string | null
  at: string
  errorCode?: string | null
  attempts?: number
  status?: string
  expected?: number
  received?: number
}

/** The three blocks of the detail panel and the line for the list. */
export function problemSentences(p: ProblemFacts): { what: string; happened: string; means: string; todo: string } {
  const file = p.pages ? `a ${p.pages}-page document` : 'a document'
  const from = p.source ? p.source : 'the sending system'
  const team = p.source ? `the team that runs ${p.source}` : 'the team that runs the sending system'
  if (p.kind === 'store') {
    const w = processingWords(p.errorCode)
    return {
      what: w.short,
      happened: `Vero received ${file} from ${from} on ${dayWords(p.at)} and tried ${times(p.attempts ?? 1)}. ${w.cause}`,
      means: 'This document is not stored, and nobody can open it in Vero. The record’s other documents are not affected.',
      todo: `Ask ${team} to send this document again. It arrives as a new delivery; there is nothing to retry here.`,
    }
  }
  if (p.kind === 'stuck') {
    const what = stuckWords(p.status ?? 'PROCESSING', p.at)
    return {
      what,
      happened: `Vero received ${file} from ${from} and has been working on it since ${dayWords(p.at)}. ${what}.`,
      means: 'Nobody can open this document in Vero until Vero finishes.',
      todo: `If it doesn’t finish, ask ${team} to send this document again as a new delivery, and give your IT team the technical details below.`,
    }
  }
  const received = p.received ?? 0, expected = p.expected ?? 0
  return {
    what: `${received} of ${expected} received`,
    happened: `${p.source ?? 'The sending system'} said it would send ${expected} document${expected === 1 ? '' : 's'} for this delivery; ${received} arrived. The last one came on ${dayWords(p.at)}.`,
    means: `People can open the ${received} that arrived. The record shows that documents are missing.`,
    todo: `Ask ${team} to send the missing documents. They arrive as new deliveries; there is nothing to retry here.`,
  }
}

/**
 * The note to copy for the sending system's team: record number, the document (received date, pages, Vero's document
 * ID), what happened and what to send. Never document content, file names (task 0027) or record titles.
 */
export function problemNote(p: ProblemFacts): string {
  const s = problemSentences(p)
  const record = p.recordNumber ? `${p.recordLabel ?? 'Record'} ${p.recordNumber}` : 'not known'
  const ask = p.kind === 'missing'
    ? `Please send the missing documents for this record (${s.what}).`
    : `Please send this document again for this record.`
  return [
    'Vero: a document needs to be sent again',
    `Record: ${record}`,
    ...(p.kind !== 'missing' ? [`Document: ${documentWords(p)}`] : []),
    `What happened: ${s.what}.`,
    ask,
  ].join('\n')
}

/** Task 0027 (0.4): a document without its file name: received date, pages and Vero's document ID. */
export function documentWords(p: ProblemFacts): string {
  const parts = [p.receivedAt ? `received ${dayWords(p.receivedAt)}` : null, p.pages ? `${p.pages} pages` : null,
    p.documentId ? `Vero document ID ${p.documentId}` : null].filter(Boolean)
  return parts.length ? parts.join(', ') : 'not known'
}
