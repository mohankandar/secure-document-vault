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
  QUARANTINE_DOCUMENTS: 'quarantine documents', QUARANTINE_BY_FILTER: 'quarantine documents by filter',
}

type Sentence = (who: string, file: string, d: Record<string, unknown>, e: OpsAuditEvent) => string
/** Task 0043: why a download failed, by its code (docs/api/error-codes.md). */
const DOWNLOAD_FAILURE_WORDS: Record<string, string> = {
  'SDV-DOC-007': 'Vero couldn’t verify it, so nothing was sent',
  'SDV-DOC-008': 'it had just been changed',
  'SDV-DOC-009': 'it stopped part-way',
}
/** One pattern per action code. */
export const AUDIT_SENTENCES: Record<string, Sentence> = {
  DOCUMENT_VIEW_OPENED: (w, f) => `${w} opened ${f}`,
  DOCUMENT_PAGE_VIEWED: (w, f, d) => (n(d.page) != null ? `${w} loaded page ${n(d.page)} of ${f}` : `${w} loaded a page of ${f}`),
  DOCUMENT_DOWNLOADED: (w, f) => `${w} downloaded ${f}`,
  // Task 0043: the failure's code says why (SDV-DOC-007 nothing was sent, -008 it had just changed, -009 it stopped part-way).
  DOCUMENT_DOWNLOAD_FAILED: (w, f, d) => {
    const why = DOWNLOAD_FAILURE_WORDS[String(d.code)]
    return why ? `${w} tried to download ${f}, but ${why} (${String(d.code)})` : `${w} tried to download ${f}, but it failed`
  },
  // Task 0028 / 0043: a Highly Confidential approval used, or given back because nothing left Vero.
  SENSITIVE_ACCESS_GRANT_USED: (w, f, d) => `${w} used an approval to ${d.kind === 'PRINT' ? 'print' : 'download'} ${f}`,
  SENSITIVE_ACCESS_GRANT_RETURNED: (w, f, d) => `${w}’s approval to ${d.kind === 'PRINT' ? 'print' : 'download'} ${f} was given back, because nothing left Vero`,
  DOCUMENT_PRINTED: (w, f) => `${w} printed ${f}`,
  DOCUMENT_TAG_ADDED: (w, f) => `${w} tagged ${f}`,
  DOCUMENT_COMMENT_ADDED: (w, f) => `${w} commented on ${f}`,
  DOCUMENT_PERMANENTLY_REDACTED: (w, f) => `${w} permanently redacted part of ${f}`,
  DOCUMENT_REDACTION_FAILED: (w, f) => `${w} tried to redact part of ${f}, but it failed`,
  DOCUMENT_RETENTION_DELETED: (_w, f) => `Vero deleted ${f} for good, as the keeping rule says`,
  DOCUMENT_RETENTION_DELETE_FAILED: (_w, f) => `Vero couldn't delete ${f} when its time was up`,
  LEGAL_HOLD_PLACED: (w, f) => `${w} put ${f} on legal hold`,
  LEGAL_HOLD_RELEASED: (w, f) => `${w} released the legal hold on ${f}`,
  // Task 0048: quarantine. The incident name and reasons stay in the quarantine tables, never in the audit.
  QUARANTINE_INCIDENT_CREATED: (w, _f, d) => (n(d.documentCount) != null ? `${w} started a quarantine incident of ${docs(n(d.documentCount))}` : `${w} started a quarantine incident`),
  DOCUMENT_QUARANTINED: (w, f) => `${w} quarantined ${f}`,
  DOCUMENT_QUARANTINE_RELEASED: (w, f, d) => (d.requestId != null ? `${w} released ${f} from quarantine, as approved` : `${w} released ${f} from quarantine`),
  QUARANTINE_INCIDENT_CLOSED: () => 'A quarantine incident ended: every document in it was released',
  QUARANTINE_RELEASE_REQUESTED: (w) => `${w} asked to release a whole quarantine incident`,
  QUARANTINE_RELEASE_APPROVED: (w) => `${w} approved releasing a whole quarantine incident`,
  QUARANTINE_RELEASE_DECLINED: (w) => `${w} declined releasing a whole quarantine incident`,
  QUARANTINE_RELEASE_EXPIRED: () => 'A request to release a quarantine incident expired',
  QUARANTINE_EXPOSURE_EXPORTED: (w) => `${w} exported a quarantine exposure report`,
  // Task 0050: the sealed audit report.
  AUDIT_REPORT_GENERATED: (w, _f, d) => (n(d.rowCount) != null ? `${w} generated a sealed audit report of ${n(d.rowCount)} event${n(d.rowCount) === 1 ? '' : 's'}` : `${w} generated a sealed audit report`),
  AUDIT_REPORT_DOWNLOADED: (w, _f, d) => `${w} downloaded a copy of a sealed audit report${d.format === 'PDF' ? ' (PDF)' : d.format === 'CSV' ? ' (CSV)' : ''}`,
  AUDIT_REPORT_VERIFIED: (w, _f, d) => (d.matches === true ? `${w} verified a file: it matches a sealed audit report` : `${w} checked a file: it matches no sealed audit report`),
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
  SENSITIVITY_REPORT_EXPORTED: (w, _f, d) => (n(d.rowCount) != null ? `${w} exported the Sensitivity report (${n(d.rowCount)} line${n(d.rowCount) === 1 ? '' : 's'})` : `${w} exported the Sensitivity report`),
  STEWARD_NOTICE_READ: (w, _f, d) => (n(d.markedCount) != null && n(d.markedCount) !== 1 ? `${w} marked ${n(d.markedCount)} notices of corrected documents sent again as read` : `${w} marked a notice of a corrected document sent again as read`),
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
  ENTITY_ACTIVATED: (w) => `${w} activated this entity`,
  ENTITY_SWITCHED: (w) => `${w} switched to this entity`,
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
  { id: 'downloads', label: 'Downloads, prints and exports', actions: ['DOCUMENT_DOWNLOADED', 'DOCUMENT_DOWNLOAD_FAILED', 'DOCUMENT_PRINTED', 'SENSITIVE_ACCESS_GRANT_USED', 'SENSITIVE_ACCESS_GRANT_RETURNED','AUDIT_EXPORTED', 'INTAKE_REPORT_EXPORTED', 'SENSITIVITY_REPORT_EXPORTED'] },
  { id: 'changes', label: 'Changes to documents', actions: ['DOCUMENT_TAG_ADDED', 'DOCUMENT_COMMENT_ADDED', 'DOCUMENT_PERMANENTLY_REDACTED', 'DOCUMENT_REDACTION_FAILED', 'DOCUMENT_RETENTION_DELETED', 'DOCUMENT_RETENTION_DELETE_FAILED', 'LEGAL_HOLD_PLACED', 'LEGAL_HOLD_RELEASED', 'DOCUMENT_QUARANTINED', 'DOCUMENT_QUARANTINE_RELEASED'] },
  { id: 'settings', label: 'Settings changes', actions: ['ROLE_CAPABILITIES_UPDATED', 'RETENTION_POLICY_UPDATED', 'METADATA_BEHAVIOR_REVISED', 'METADATA_DISPLAY_ORDER_UPDATED', 'RECORD_TITLE_FIELDS_UPDATED', 'METADATA_FIELD_CREATED', 'METADATA_FIELD_REVISED', 'SOURCE_FIELD_DEFINED', 'SOURCE_FIELD_REVISED', 'INGESTION_SOURCE_CREATED', 'INGESTION_SOURCE_UPDATED', 'STORAGE_VOLUME_CREATED', 'STORAGE_VOLUME_UPDATED', 'STORAGE_FOLDER_CHECKED', 'ENTITY_ACTIVATED'] },
  { id: 'deliveries', label: 'Deliveries', actions: ['SUBMISSION_CREATED', 'DOCUMENT_RECEIVED', 'DOCUMENT_DUPLICATE_RECEIVED', 'DOCUMENT_UNCORRECTED_COPY_RECEIVED', 'INTAKE_REFUSED', 'STEWARD_NOTICE_READ', 'DOCUMENT_AVAILABLE', 'DOCUMENT_PROCESSING_FAILED'] },
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
