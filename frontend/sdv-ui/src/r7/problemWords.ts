// Task 0011 Part A (findings 12 and 18): every problem in plain words. The one place for these sentences.
// The server's processing error codes come from DocumentFinalizationService.classify; a code not listed here
// falls back to "Vero couldn't store this file (code X)."

import { ApiRequestError, type SensitivityLevel } from '../api'

export type ProblemKind = 'store' | 'stuck' | 'missing'

/**
 * Task 0043: what the viewer, print and download show for each SDV-DOC code, in the catalog's words
 * (docs/api/error-codes.md, "Viewing, print and download (DOC)"). They replace VIEWER_UNAVAILABLE and
 * DOWNLOAD_UNAVAILABLE. `fault` marks Vero's own failures: the screen adds the reference for IT. The server's own
 * text is never shown.
 */
export const DOCUMENT_PROBLEM_WORDS: Record<string, { text: string; fault?: true }> = {
  'SDV-DOC-001': { text: 'This document isn’t there any more, or the link is wrong. Open the record again from search.' },
  'SDV-DOC-002': { text: 'This document can’t be opened yet. It may still be being stored or corrected. Try again in a few minutes.' },
  'SDV-DOC-003': { text: 'Vero couldn’t read this document from storage. Try again later.', fault: true },
  'SDV-DOC-004': { text: 'This document’s stored copy didn’t pass Vero’s check, so it isn’t shown.', fault: true },
  'SDV-DOC-005': { text: 'Vero can’t draw this page. Download it if your role allows, or tell your IT team.', fault: true },
  'SDV-DOC-006': { text: 'Vero couldn’t show that part of the document. Reload it and try again.' },
  'SDV-DOC-007': { text: 'Vero couldn’t verify this document, so it wasn’t downloaded.', fault: true },
  'SDV-DOC-008': { text: 'This document was just changed. Open it again and download the new version.' },
  'SDV-DOC-009': { text: 'The download didn’t finish.', fault: true },
  'SDV-DOC-010': { text: 'A tag can be up to 80 characters and a comment up to 2,000. Neither can be empty.' },
}

/**
 * Task 0044: one sentence per code a screen can get, from the catalog (docs/api/error-codes.md, "What the user should
 * do"). Written for a person, never the server's own message. For a 401 or 403 it follows "You don’t have access to
 * this."; for a 5xx it follows "Something went wrong in Vero." and the screen adds when to try again and the reference.
 * A code not listed gets its kind's general sentence, with the code. The DOC codes are DOCUMENT_PROBLEM_WORDS above.
 */
export const CODE_WORDS: Record<string, string> = {
  'SDV-SEN-007': 'This change to the identifiers isn’t allowed while records of this sending system have a sensitivity level. Labels, search settings and the other field settings can still be changed.',
  'SDV-SEN-008': 'This document is restricted. If you need it for your work, ask someone who holds the right role.',
  'SDV-SEN-009': 'Printing or downloading a Highly Confidential document needs a Business admin’s approval, once. A Restricted steward can ask for it.',
  'SDV-SEN-010': 'Only a Steward can change a level.',
  'SDV-SEN-011': 'Choose one of the three levels and try again.',
  'SDV-SEN-012': 'Write why the level must change, in 3 to 500 characters.',
  'SDV-SEN-013': 'A raise must go to a higher level. To make it lower, ask for a lowering instead.',
  'SDV-SEN-014': 'A lowering must go to a lower level. If the document is restricted only through its record’s level, ask for the record’s level to be lowered instead.',
  'SDV-SEN-015': 'A Steward can lower a level only from Confidential. Ask a Restricted steward to request the lowering.',
  'SDV-SEN-016': 'This document or record isn’t there any more. Open the record again from search and try again.',
  'SDV-SEN-017': 'Only an available document’s level can be changed. Wait until it is available.',
  'SDV-SEN-018': 'Only a Steward or a Business admin can see the level history.',
  'SDV-SEN-019': 'Choose Any, Confidential or Highly Confidential in the Sensitivity filter.',
  'SDV-ROL-001': 'That permission can never be given to that role. Leave it off.',
  'SDV-ROL-002': 'This role’s permissions are fixed. Only Business User and Document Steward can be changed.',
  'SDV-ROL-003': 'That permission can’t be changed here.',
  'SDV-ROL-004': 'Your sign-in groups give you two roles that can’t be held together. Ask your IT team to take you out of one of them.',
  'SDV-ROL-005': 'Vero couldn’t record a role conflict at sign-in. Try again in a few minutes. If it keeps happening, ask your IT team.',
  'SDV-ROL-006': 'Your role doesn’t include this action. Ask someone who has the role, or ask your IT team whether you should have it.',
  'SDV-AUT-001': 'Sign in again.',
  'SDV-AUT-002': 'This address is for sending systems, not for a person’s sign-in.',
  'SDV-AUT-003': 'Your sign-in wasn’t accepted. Sign in again.',
  'SDV-AUT-004': 'Vero can’t check sign-ins just now.',
  'SDV-AUT-005': 'Vero can’t use your sign-in. Tell your IT team.',
  'SDV-AUT-006': 'Vero doesn’t know this sign-in service. Tell your IT team.',
  'SDV-AUT-007': 'Your sign-in groups give you no Vero role. Ask your IT team to add you to a Vero group.',
  'SDV-AUT-008': 'The local test sign-in isn’t valid. Choose a role from the local guide.',
  'SDV-AUT-009': 'Your organisation isn’t set up or isn’t active in Vero. Contact your IT team.',
  'SDV-AUT-010': 'Choose which entity to work in first.',
  'SDV-AUT-011': 'This client isn’t set up to send documents to Vero. Your IT team can fix this.',
  'SDV-AUT-012': 'The entity or sending system in this token doesn’t match how this client is set up. Your IT team can fix this.',
  'SDV-AUT-013': 'The sending system this client is set up for doesn’t exist in Vero. Your IT team can fix this.',
  'SDV-ENT-001': 'This entity can’t be activated. Check the licence and the name, then try again.',
  'SDV-ENT-002': 'Only a Business Admin of this entity can activate it.',
  'SDV-HLD-001': 'Only a Business admin places or releases legal holds.',
  'SDV-HLD-002': 'Write why the document must be kept, in 3 to 500 characters, and place the hold again.',
  'SDV-HLD-003': 'Enter the record’s identifier and look again.',
  'SDV-HLD-004': 'This document isn’t there any more. Find it again by its record’s identifier and place the hold from there.',
  'SDV-HLD-005': 'This document is already on legal hold. To change the reason, release the hold and place a new one.',
  'SDV-HLD-006': 'This document was deleted or is being deleted, so it can’t be put on hold.',
  'SDV-HLD-007': 'This legal hold isn’t there any more. Open the list of holds again.',
  'SDV-HLD-008': 'This legal hold was already released. Reload the list of holds.',
  'SDV-QUA-001': 'This document is temporarily unavailable.',
  'SDV-QUA-002': 'Only a Steward who can open this document’s level can quarantine, review or release it. Ask a Restricted steward.',
  'SDV-QUA-003': 'Only a Restricted steward can quarantine documents by filter. Quarantine them one at a time, or ask a Restricted steward.',
  'SDV-QUA-004': 'Something in the form isn’t right. Check the name, the reason (3 to 500 characters) and the filter, then try again.',
  'SDV-QUA-005': 'This document isn’t there any more. Open it again from its record.',
  'SDV-QUA-006': 'This document is already quarantined, or it was deleted. Look for it in the quarantine list.',
  'SDV-QUA-007': 'This incident isn’t there any more. Open the quarantine list again.',
  'SDV-QUA-008': 'This incident was released, or is being released, so nothing can be added to it. Start a new incident.',
  'SDV-QUA-009': 'The matching documents changed since they were counted. Count them again, then select them all.',
  'SDV-QUA-010': 'This release request can’t be made or decided: one is already waiting, it was decided or expired, or it is your own.',
  'SDV-AUD-010': 'Only a Business admin or your IT team can generate or verify an audit report. Ask one of them to hand it to you.',
  'SDV-AUD-011': 'Choose the first and last day of the report again. The first day can’t come after the last.',
  'SDV-AUD-012': 'A filter of the report isn’t valid. Change or clear it, then generate the report again.',
  'SDV-AUD-013': 'The report would hold more than 10,000 events. Choose fewer days or more filters.',
  'SDV-AUD-014': 'This audit report isn’t there. Choose it again from the list of audit reports.',
  'SDV-AUD-015': 'That isn’t a whole fingerprint. Choose the CSV file itself, or paste all 64 characters from the report’s PDF.',
  'SDV-AUD-016': 'The report couldn’t be recorded, so it wasn’t handed out. Try again in a few minutes.',
  'SDV-AUD-017': 'Audit records can’t be changed or removed.',
  'SDV-QUA-011':'This document isn’t quarantined any more. Refresh the incident.',
  'SDV-APR-001': 'Only a Restricted steward can ask to print or download.',
  'SDV-APR-002': 'Write why the document must be printed or downloaded, in 3 to 500 characters, and ask again.',
  'SDV-APR-003': 'This document needs no approval. Print or download it directly, if your role allows.',
  'SDV-APR-004': 'The same request is already waiting. Wait for the Business admin’s decision.',
  'SDV-APR-005': 'Only a Business admin decides requests.',
  'SDV-APR-006': 'Nobody decides their own request. Ask another Business admin to decide it.',
  'SDV-APR-007': 'This request isn’t there any more. Reload the approvals list.',
  'SDV-APR-008': 'This request is no longer waiting. Reload the approvals list.',
  'SDV-APR-009': 'A whole-record request is decided on its own. Open it with Review.',
  'SDV-APR-010': 'Choose from 1 to 100 requests and decide them again.',
  'SDV-APR-011': 'The lowering no longer applies, because the level has changed. Decline the request; the Steward can ask again.',
  'SDV-APR-012': 'Choose one of the kinds and ask again.',
  'SDV-APR-013': 'This document isn’t there any more. Open it again from its record and ask from there.',
  'SDV-APR-014': 'This document isn’t available, so nothing can be asked for it yet.',
  'SDV-APR-015': 'Only a Steward has a list of their own requests.',
  'SDV-INT-001': 'Only a Steward gets intake notices.',
  'SDV-INT-002': 'This notice isn’t there any more. Reload Vero.',
  'SDV-INT-003': 'Mark the notices from the list in Vero.',
  'SDV-RPT-001': 'Only a Business admin sees reports.',
  'SDV-RPT-002': 'Choose one day, or a range of up to 31 days (Daily intake) or 366 days (Sensitivity).',
  'SDV-RPT-003': 'Choose a sending system from the list, or All.',
  'SDV-RPT-004': 'The organisation’s time zone isn’t valid. Tell IT.',
  'SDV-RPT-005': 'That is too much for one report. Choose fewer days, or one sending system.',
  'SDV-RST-001': 'Only a Business admin sees the restrictions.',
  'SDV-RST-002': 'This page of the list isn’t known any more. Open the Restrictions page again.',
  'SDV-SYS-001': 'The database is older than this Vero version.',
  'SDV-SYS-002': 'The database is newer than this Vero version.',
  'SDV-SYS-006': 'Sign-in is switched off for this installation. Tell your IT team.',
  'SDV-SYS-014': 'Vero can’t reach its database just now.',
  'SDV-SYS-015': 'Vero couldn’t read this request. Reload the page and try again.',
  'SDV-SYS-016': 'Something inside Vero didn’t add up, so nothing was changed.',
  'SDV-SYS-017': 'Vero couldn’t record this action, so it wasn’t done.',
  'SDV-SYS-018': 'Vero is too busy to take this request just now.',
  'SDV-SYS-019': 'This request is larger than Vero takes. Send less at once.',
  'SDV-STO-008': 'This folder can’t be used for documents. Choose another, or ask your installer which folders are allowed.',
  'SDV-STO-001': 'Vero has no storage space for new documents right now. Nothing was stored.',
  'SDV-STO-004': 'Storage for this organisation isn’t available, so nothing was stored.',
  'SDV-STO-005': 'A storage value is missing or out of range. Correct it and try again.',
  'SDV-SCH-001': 'Vero can’t search with these criteria. Change the search and try again.',
  'SDV-SCH-002': 'This record isn’t there, or the link is wrong. Search for the record again.',
  'SDV-SCH-003': 'Vero couldn’t load Recently viewed. Reload the page.',
}

/** Task 0044: the four kinds of error a screen shows. */
export type ErrorKind = 'refusal' | 'notAllowed' | 'fault' | 'network'
/** What a screen shows for an error: `text` is the whole line (sentence, reference, code). */
export type ErrorWords = { kind: ErrorKind; text: string; code?: string; reference?: string }

export const GENERAL_WORDS = {
  refusal: 'Vero can’t do this as asked. Check what you entered and try again.',
  notAllowed: 'You don’t have access to this.',
  signInAgain: 'Sign in again.',
  askIt: 'If you need it for your work, ask your IT team.',
  fault: 'Something went wrong in Vero.',
  network: 'Vero can’t be reached. Check your connection.',
} as const

const randomReference = () => Array.from(crypto.getRandomValues(new Uint8Array(4)), (b) => b.toString(16).padStart(2, '0')).join('')
const seconds = (n: number) => `${n} second${n === 1 ? '' : 's'}`

/**
 * Task 0044: the one way every screen turns an error into words (the viewer's DOC codes keep task 0043's words).
 * - refusal (a 4xx that isn't 401/403): the code's sentence, or the general one;
 * - notAllowed (401/403): "You don’t have access to this." and what to do (sign in again for a 401);
 * - fault (5xx, or anything else unexpected): "Something went wrong in Vero.", when to try again (503: in a few
 *   minutes, or the Retry-After seconds) and always a reference;
 * - network (no answer): "Vero can’t be reached. Check your connection."
 * The code is shown as "(SDV-XXX-NNN)" whenever there is one. The server's message is never used (ApiRequestError
 * doesn't keep it). The reference is the server's; when it sent none, a new one is made, which the caller logs to the
 * console with the raw error. `withReference` also adds the reference to a refusal or not-allowed line (exports).
 */
export function errorWords(error: unknown, options: { withReference?: boolean; newReference?: () => string } = {}): ErrorWords {
  const newReference = options.newReference ?? randomReference
  if (!(error instanceof ApiRequestError)) {
    const reference = newReference()
    return { kind: 'fault', reference, text: `${GENERAL_WORDS.fault} Try again. If it keeps happening, give your IT team this reference: Ref ${reference}.` }
  }
  const { status, code } = error
  const tail = code ? ` (${code})` : ''
  if (status === 0) return { kind: 'network', text: GENERAL_WORDS.network }
  const doc = code ? DOCUMENT_PROBLEM_WORDS[code] : undefined
  if (doc) {
    const reference = doc.fault ? error.correlationId ?? newReference() : error.correlationId
    const ref = reference && (doc.fault || options.withReference) ? ` Ref ${reference}.` : ''
    return { kind: doc.fault ? 'fault' : status === 401 || status === 403 ? 'notAllowed' : 'refusal', code, reference, text: `${doc.text}${ref}${tail}` }
  }
  const words = code ? CODE_WORDS[code] : undefined
  if (status >= 400 && status < 500) {
    const notAllowed = status === 401 || status === 403
    const reference = error.correlationId
    const ref = options.withReference && reference ? ` Ref ${reference}.` : ''
    const text = notAllowed
      ? `${GENERAL_WORDS.notAllowed} ${words ?? (status === 401 ? GENERAL_WORDS.signInAgain : GENERAL_WORDS.askIt)}`
      : words ?? GENERAL_WORDS.refusal
    return { kind: notAllowed ? 'notAllowed' : 'refusal', code, reference, text: `${text}${ref}${tail}` }
  }
  const reference = error.correlationId ?? newReference()
  const when = status === 503
    ? `Try again in ${error.retryAfterSeconds != null ? seconds(error.retryAfterSeconds) : 'a few minutes'}. If it keeps happening, give your IT team this reference`
    : 'Try again. If it keeps happening, give your IT team this reference'
  return { kind: 'fault', code, reference, text: `${GENERAL_WORDS.fault}${words ? ` ${words}` : ''} ${when}: Ref ${reference}.${tail}` }
}

/**
 * Task 0044: Health's sentence for the database check at start (SDV-SYS-001 to -003), in Vero's own words rather than
 * the server's message. The same sentences as the start-up log (SchemaVersion).
 */
export function schemaWords(state: string): string {
  if (state === 'OLDER') return 'The database is older than this Vero version. Ask your database team to run the schema step (install guide, "Update the database").'
  if (state === 'NEWER') return 'The database is newer than this Vero version. Start the Vero version the database was updated for, or ask your database team which version that is.'
  if (state === 'UNREACHABLE') return 'Vero can’t reach its database. It keeps trying and starts serving as soon as the database answers; check that PostgreSQL is running and that Vero can reach it.'
  return 'Vero couldn’t tell whether the database matches this version. Tell your IT team.'
}

/** The words for one item of a bulk answer (an outcome with a code, no HTTP status): a refusal, or Vero's fault. */
export function itemWords(outcome: 'REFUSED' | 'FAILED', code: string | null): string {
  const tail = code ? ` (${code})` : ''
  if (outcome === 'FAILED') return `${GENERAL_WORDS.fault}${code && CODE_WORDS[code] ? ` ${CODE_WORDS[code]}` : ''} Try again.${tail}`
  return `${(code && CODE_WORDS[code]) || GENERAL_WORDS.refusal}${tail}`
}

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
