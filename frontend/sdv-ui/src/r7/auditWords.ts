// Task 0011 Part B (finding 17): every audit event as a sentence. The one place for these sentences.
// A code not listed here reads "<person> · <humanized code>".
import { humanize } from './ui'
import type { OpsAuditEvent } from './liveApi'

/** Name from the event (migration 020); older events and background work show the subject ID. */
export const actorName = (e: OpsAuditEvent) => e.actorDisplayName || (e.actorType === 'SYSTEM' ? 'Vero' : e.actorId)

function details(e: OpsAuditEvent): Record<string, unknown> {
  try { return e.safeDetailsJson ? JSON.parse(e.safeDetailsJson) as Record<string, unknown> : {} } catch { return {} }
}
const n = (v: unknown) => (typeof v === 'number' ? v : undefined)
const docs = (k: number | undefined) => (k == null ? 'documents' : `${k} document${k === 1 ? '' : 's'}`)
function ruleWords(d: Record<string, unknown>): string {
  if (d.indefinite === true) return 'keep them forever'
  const days = n(d.defaultRetentionDays)
  if (days == null) return 'a new rule'
  if (days % 365 === 0) return `${days / 365} year${days === 365 ? '' : 's'}`
  if (days % 30 === 0) return `${days / 30} month${days === 30 ? '' : 's'}`
  return `${days.toLocaleString()} days`
}
/** What a refused permission would have allowed, in words. */
const CAPABILITY_WORDS: Record<string, string> = {
  SEARCH_RECORDS: 'search records', VIEW_PDF: 'open a document', DOWNLOAD_PDF: 'download a document', PRINT_DOCUMENT: 'print a document',
  ADD_DOCUMENT_TAG: 'tag a document', ADD_DOCUMENT_COMMENT: 'comment on a document', PERMANENT_REDACTION: 'permanently redact a document',
  CONFIGURE_ROLE_PERMISSIONS: 'change roles & permissions', CONFIGURE_RETENTION: 'change how long documents are kept',
  CONFIGURE_METADATA_SEARCH: 'change search fields', CONFIGURE_INGESTION_SOURCES: 'change sending systems',
  CONFIGURE_STORAGE_KEY_PROVIDER: 'change storage', VIEW_TECHNICAL_FAILURES: 'see problems and the audit', VIEW_INTAKE_REPORTS: 'see the reports',
}

type Sentence = (who: string, file: string, d: Record<string, unknown>, e: OpsAuditEvent) => string
/** One pattern per action code. */
export const AUDIT_SENTENCES: Record<string, Sentence> = {
  DOCUMENT_VIEW_OPENED: (w, f) => `${w} opened ${f}`,
  DOCUMENT_PAGE_VIEWED: (w, f, d) => (n(d.page) != null ? `${w} loaded page ${n(d.page)} of ${f}` : `${w} loaded a page of ${f}`),
  DOCUMENT_DOWNLOADED: (w, f) => `${w} downloaded ${f}`,
  DOCUMENT_DOWNLOAD_FAILED: (w, f) => `${w} tried to download ${f}, but it failed`,
  DOCUMENT_PRINTED: (w, f) => `${w} printed ${f}`,
  DOCUMENT_TAG_ADDED: (w, f) => `${w} tagged ${f}`,
  DOCUMENT_COMMENT_ADDED: (w, f) => `${w} commented on ${f}`,
  DOCUMENT_PERMANENTLY_REDACTED: (w, f) => `${w} permanently redacted part of ${f}`,
  DOCUMENT_REDACTION_FAILED: (w, f) => `${w} tried to redact part of ${f}, but it failed`,
  DOCUMENT_RETENTION_DELETED: (_w, f) => `Vero deleted ${f} for good, as the keeping rule says`,
  DOCUMENT_RETENTION_DELETE_FAILED: (_w, f) => `Vero couldn't delete ${f} when its time was up`,
  LEGAL_HOLD_PLACED: (w, f) => `${w} put ${f} on legal hold`,
  LEGAL_HOLD_RELEASED: (w, f) => `${w} released the legal hold on ${f}`,
  BUSINESS_RECORD_OPENED: (w) => `${w} opened a record`,
  // Task 0022: a search across all fields says so ("searched all fields"); the text itself is never stored.
  BUSINESS_SEARCH: (w, _f, d) => { const what = d.anywhere === true ? 'searched all fields' : 'searched records'; return n(d.resultCount) != null ? `${w} ${what} and found ${n(d.resultCount)}` : `${w} ${what}` },
  BUSINESS_RECENT_RECORDS_LISTED: (w) => `${w} looked at Recently viewed`,
  AUDIT_VIEWED: (w) => `${w} looked at the audit`,
  AUDIT_EXPORTED: (w, _f, d) => (n(d.rowCount) != null ? `${w} exported the audit (${n(d.rowCount)} event${n(d.rowCount) === 1 ? '' : 's'})` : `${w} exported the audit`),
  AUTHORIZATION_DENIED: (w, _f, d) => `${w} tried to ${CAPABILITY_WORDS[String(d.capability)] ?? 'do something'} without permission`,
  SUBMISSION_CREATED: (w, _f, d) => `${w} delivered ${docs(n(d.expectedDocumentCount))}`,
  DOCUMENT_RECEIVED: (w, f) => `${w} sent ${f}`,
  // Task 0031: an exact duplicate; nothing was stored a second time.
  DOCUMENT_DUPLICATE_RECEIVED: (w, f) => `${w} sent ${f} again; Vero kept the copy it already had`,
  // Task 0032: what a document was before a permanent correction; only the corrected version is kept.
  DOCUMENT_UNCORRECTED_COPY_RECEIVED: (w) => `${w} sent an uncorrected copy of a corrected document; nothing was stored`,
  INTAKE_REFUSED: (w, _f, d) => (d.code ? `${w} sent a delivery that Vero refused (${String(d.code)})` : `${w} sent a delivery that Vero refused`),
  INTAKE_REPORT_EXPORTED: (w, _f, d) => (n(d.rowCount) != null ? `${w} exported the Daily intake report (${n(d.rowCount)} line${n(d.rowCount) === 1 ? '' : 's'})` : `${w} exported the Daily intake report`),
  DOCUMENT_AVAILABLE: (_w, f) => `${f} is stored and ready to open`,
  DOCUMENT_PROCESSING_FAILED: (_w, f) => `Vero couldn't store ${f}`,
  ROLE_CAPABILITIES_UPDATED: (w) => `${w} changed what business roles may do`,
  RETENTION_POLICY_UPDATED: (w, _f, d) => (d.indefinite === true ? `${w} changed how long to keep new documents: keep them forever` : `${w} changed how long to keep new documents to ${ruleWords(d)}`),
  METADATA_BEHAVIOR_REVISED: (w) => `${w} changed how a search field works`,
  METADATA_DISPLAY_ORDER_UPDATED: (w) => `${w} changed the order of the search fields`,
  RECORD_TITLE_FIELDS_UPDATED: (w) => `${w} changed what records are called`,
  METADATA_FIELD_CREATED: (w) => `${w} added a field a sending system sends`,
  METADATA_FIELD_REVISED: (w) => `${w} changed a field a sending system sends`,
  SOURCE_FIELD_DEFINED: (w) => `${w} added a field a sending system sends`,
  SOURCE_FIELD_REVISED: (w) => `${w} changed a field a sending system sends`,
  INGESTION_SOURCE_CREATED: (w) => `${w} added a sending system`,
  INGESTION_SOURCE_UPDATED: (w) => `${w} changed a sending system`,
  STORAGE_VOLUME_CREATED: (w) => `${w} added a storage volume`,
  STORAGE_VOLUME_UPDATED: (w) => `${w} changed a storage volume`,
  STORAGE_FOLDER_CHECKED: (w, _f, d) => (d.writable === false ? `${w} checked a folder for a new volume; Vero couldn't write there` : `${w} checked a folder for a new volume`),
  ENTERPRISE_IDENTITY_DRAFT_SAVED: (w) => `${w} saved a draft of the sign-in settings`,
  ENTERPRISE_IDENTITY_CONNECTION_TESTED: (w) => `${w} tested the sign-in settings`,
  ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED: (w) => `${w} switched on new sign-in settings`,
  ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED: (w) => `${w} tried to switch on new sign-in settings, but it failed`,
  ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK: (w) => `${w} went back to earlier sign-in settings`,
  ENTERPRISE_IDENTITY_CONNECTION_ROLLBACK_FAILED: (w) => `${w} tried to go back to earlier sign-in settings, but it failed`,
}

export function auditSentence(e: OpsAuditEvent): string {
  const who = actorName(e)
  // Task 0027 (0.4): admin views never name a file; the Record column and the entry's details say which document.
  const file = 'a document'
  const pattern = AUDIT_SENTENCES[e.action]
  return pattern ? pattern(who, file, details(e), e) : `${who} · ${humanize(e.action)}`
}

export const RESULT_WORDS: Record<string, string> = { SUCCESS: 'Done', DENIED: 'Refused', FAILED: 'Failed' }

/** Everyday activity groups. Refused is every event the server refused, whatever its action. */
export const ACTIVITY_GROUPS: { id: string; label: string; actions?: string[]; outcome?: string }[] = [
  { id: 'reading', label: 'Opening and reading', actions: ['DOCUMENT_VIEW_OPENED', 'DOCUMENT_PAGE_VIEWED', 'BUSINESS_RECORD_OPENED', 'BUSINESS_SEARCH', 'BUSINESS_RECENT_RECORDS_LISTED', 'AUDIT_VIEWED'] },
  { id: 'downloads', label: 'Downloads, prints and exports', actions: ['DOCUMENT_DOWNLOADED', 'DOCUMENT_DOWNLOAD_FAILED', 'DOCUMENT_PRINTED', 'AUDIT_EXPORTED', 'INTAKE_REPORT_EXPORTED'] },
  { id: 'changes', label: 'Changes to documents', actions: ['DOCUMENT_TAG_ADDED', 'DOCUMENT_COMMENT_ADDED', 'DOCUMENT_PERMANENTLY_REDACTED', 'DOCUMENT_REDACTION_FAILED', 'DOCUMENT_RETENTION_DELETED', 'DOCUMENT_RETENTION_DELETE_FAILED', 'LEGAL_HOLD_PLACED', 'LEGAL_HOLD_RELEASED'] },
  { id: 'settings', label: 'Settings changes', actions: ['ROLE_CAPABILITIES_UPDATED', 'RETENTION_POLICY_UPDATED', 'METADATA_BEHAVIOR_REVISED', 'METADATA_DISPLAY_ORDER_UPDATED', 'RECORD_TITLE_FIELDS_UPDATED', 'METADATA_FIELD_CREATED', 'METADATA_FIELD_REVISED', 'SOURCE_FIELD_DEFINED', 'SOURCE_FIELD_REVISED', 'INGESTION_SOURCE_CREATED', 'INGESTION_SOURCE_UPDATED', 'STORAGE_VOLUME_CREATED', 'STORAGE_VOLUME_UPDATED', 'STORAGE_FOLDER_CHECKED', 'ENTERPRISE_IDENTITY_DRAFT_SAVED', 'ENTERPRISE_IDENTITY_CONNECTION_TESTED', 'ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED', 'ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED', 'ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK', 'ENTERPRISE_IDENTITY_CONNECTION_ROLLBACK_FAILED'] },
  { id: 'deliveries', label: 'Deliveries', actions: ['SUBMISSION_CREATED', 'DOCUMENT_RECEIVED', 'DOCUMENT_DUPLICATE_RECEIVED', 'DOCUMENT_UNCORRECTED_COPY_RECEIVED', 'INTAKE_REFUSED', 'DOCUMENT_AVAILABLE', 'DOCUMENT_PROCESSING_FAILED'] },
  { id: 'refused', label: 'Refused', outcome: 'DENIED' },
]

/** Task 0020 (item 2): what Overview › Latest activity shows. Documents opened, downloaded, printed or redacted,
 *  refusals, settings changes and deliveries. Searches, page loads and other routine reads stay in Audit only. */
const MEANINGFUL_ACTIONS = new Set([
  'DOCUMENT_VIEW_OPENED', 'DOCUMENT_DOWNLOADED', 'DOCUMENT_DOWNLOAD_FAILED', 'DOCUMENT_PRINTED',
  'DOCUMENT_PERMANENTLY_REDACTED', 'DOCUMENT_REDACTION_FAILED',
  // Checking a folder changes nothing, so it is not a settings change here.
  ...(ACTIVITY_GROUPS.find((g) => g.id === 'settings')?.actions ?? []).filter((a) => a !== 'STORAGE_FOLDER_CHECKED'),
  ...(ACTIVITY_GROUPS.find((g) => g.id === 'deliveries')?.actions ?? []),
])
export const isMeaningfulActivity = (e: OpsAuditEvent) => e.outcome === 'DENIED' || MEANINGFUL_ACTIONS.has(e.action)
