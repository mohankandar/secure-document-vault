import { bearerHeaders, expireOidcSession, isLocalSecurityMode, isOidcSecurityMode } from './auth'

/** Task 0041: the local demo entity, slot ENT-VERO-DEMO-0001; its UUID is derived from the slot ID (entityIds.test.ts). */
export const LOCAL_DEMO_ENTITY_ID = 'bd960ee9-9d54-5053-9958-92aaa54893a6'
/** The demo's second entity, slot ENT-VERO-DEMO-0002. */
export const LOCAL_ISOLATION_ENTITY_ID = '9b233c00-fe6e-582c-a9de-c1fa46f87254'

/** Task 0041 (owner answer 5): the entity the person works in, kept in this browser. Not sensitive. */
const ENTITY_KEY = 'sdv.r7.entity'
const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export const getChosenEntity = (): string | null => {
  try { const v = window.localStorage.getItem(ENTITY_KEY); return v && UUID_SHAPE.test(v) ? v : null } catch { return null }
}
export const setChosenEntity = (id: string | null) => {
  try { if (id && UUID_SHAPE.test(id)) window.localStorage.setItem(ENTITY_KEY, id); else window.localStorage.removeItem(ENTITY_KEY) } catch { /* storage unavailable: the chooser shows again */ }
}
/** The entity header: X-SDV-Entity-Id with OIDC sign-in (checked by the server, never trusted). */
const entityHeaders = (): Record<string, string> => {
  if (!isOidcSecurityMode()) return {}
  const chosen = getChosenEntity()
  return chosen ? { 'X-SDV-Entity-Id': chosen } : {}
}

export type LocalPersona = 'admin' | 'business' | 'steward' | 'restricted' | 'businessAdmin' | 'tenantIt' | 'platform'

const LOCAL_ROLE_KEY = 'sdv.r7.local-test-persona'
export const localRoleSwitchEnabled = () => import.meta.env.DEV && window.location.hostname === 'localhost' && isLocalSecurityMode()
export const getLocalTestPersona = (): LocalPersona | null => {
  if (!localRoleSwitchEnabled()) return null
  const value = window.sessionStorage.getItem(LOCAL_ROLE_KEY)
  return value === 'business' || value === 'steward' || value === 'restricted' || value === 'businessAdmin' || value === 'tenantIt' || value === 'platform' || value === 'admin' ? value : null
}
export const setLocalTestPersona = (persona: LocalPersona) => {
  if (!localRoleSwitchEnabled()) return
  window.sessionStorage.setItem(LOCAL_ROLE_KEY, persona)
}

const localHeaders = (persona: LocalPersona): Record<string, string> => {
  if (!isLocalSecurityMode()) return {}
  // Explicitly local-only testing; production and OIDC never accept a selected test identity.
  const selected = getLocalTestPersona()
  const localTestRole = import.meta.env.DEV && window.location.hostname === 'localhost' ? import.meta.env.VITE_LOCAL_TEST_ROLE : ''
  const effectivePersona: LocalPersona = selected ?? (localTestRole === 'BUSINESS_USER' ? 'business' : localTestRole === 'BUSINESS_ADMIN' ? 'businessAdmin' : localTestRole === 'IT_ADMIN' ? 'tenantIt' : localTestRole === 'PLATFORM_OPERATIONS_ADMIN' ? 'platform' : localTestRole === 'DOCUMENT_STEWARD' ? 'steward' : localTestRole === 'RESTRICTED_STEWARD' ? 'restricted' : persona)
  const identity = effectivePersona === 'steward'
    ? { subject: 'local-document-steward', roles: 'DOCUMENT_STEWARD' }
    : effectivePersona === 'restricted'
    ? { subject: 'local-restricted-steward', roles: 'RESTRICTED_STEWARD' }
    : effectivePersona === 'business'
      ? { subject: 'local-business-user', roles: 'BUSINESS_USER' }
      : effectivePersona === 'businessAdmin'
      ? { subject: 'local-business-admin', roles: 'BUSINESS_ADMIN' }
      : effectivePersona === 'tenantIt'
        ? { subject: 'local-tenant-it-admin', roles: 'IT_ADMIN' }
        : effectivePersona === 'platform'
          ? { subject: 'local-platform-operations-admin', roles: 'PLATFORM_OPERATIONS_ADMIN' }
          : { subject: 'local-administrator', roles: 'IT_ADMIN,BUSINESS_ADMIN' }
  return {
    'X-SDV-Tenant-Id': getChosenEntity() ?? LOCAL_DEMO_ENTITY_ID,
    'X-SDV-Subject': identity.subject,
    'X-SDV-Roles': identity.roles,
  }
}

/**
 * correlationId: the failed request's reference for IT: the server's X-Correlation-Id, else the body's `reference`.
 * code: the refusal's stable code (docs/api/error-codes.md), e.g. SDV-SEN-008, when the server sent one.
 * retryAfterSeconds: the Retry-After header in seconds, when the server sent one (only SDV-SYS-018 does).
 * status 0: Vero didn't answer at all (network).
 * Task 0044: the message is never the server's text. Screens take their words from errorWords (problemWords.ts).
 */
export class ApiRequestError extends Error {
  constructor(message: string, public readonly status: number, public readonly correlationId?: string, public readonly code?: string, public readonly retryAfterSeconds?: number) { super(message); this.name = 'ApiRequestError' }
}
const codeOf = (body: { code?: unknown } | null) => (typeof body?.code === 'string' && /^SDV-[A-Z]{3}-\d{3}$/.test(body.code) ? body.code : undefined)
/** Only a value shaped like a reference is kept (letters, digits and dashes), so no other server text can reach the screen. */
const referenceOf = (value: unknown) => (typeof value === 'string' && /^[A-Za-z0-9-]{4,64}$/.test(value) ? value : undefined)
const retryAfterOf = (value: string | null) => (value && /^\d{1,4}$/.test(value.trim()) ? Number(value.trim()) : undefined)

/** Task 0044: the error for an answer that isn't OK. The body's message is not kept. */
async function failureOf(response: Response): Promise<ApiRequestError> {
  if (response.status === 401 && isOidcSecurityMode()) expireOidcSession()
  const body = await response.json().catch(() => null) as { code?: unknown; reference?: unknown } | null
  return new ApiRequestError(`HTTP ${response.status}`, response.status,
    referenceOf(response.headers.get('X-Correlation-Id')) ?? referenceOf(body?.reference), codeOf(body), retryAfterOf(response.headers.get('Retry-After')))
}

/** Task 0044: fetch, with "no answer" (offline, Vero down, connection cut) turned into a network error (status 0). */
async function send(path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(path, init)
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiRequestError('No answer', 0)
  }
}

async function request<T>(path: string, persona: LocalPersona, init?: RequestInit): Promise<T> {
  const enterpriseHeaders = await bearerHeaders()
  const response = await send(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...localHeaders(persona),
      ...entityHeaders(),
      ...(init?.headers ?? {}),
      ...enterpriseHeaders,
    },
    cache: 'no-store',
  })
  if (!response.ok) throw await failureOf(response)
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const roleApi = <T>(path: string, persona: LocalPersona, init?: RequestInit) => request<T>(path, persona, init)

export async function contentBinary(path: string, persona: LocalPersona): Promise<Blob> {
  const response = await binaryResponse(path, persona)
  return response.blob()
}

/** Task 0043: the code a download that stopped part-way gets on screen (the server can't send it once the file has started). */
export const DOWNLOAD_STOPPED = 'SDV-DOC-009'

/**
 * Task 0043: a download, checked for being complete. When the connection breaks, or the file is shorter than the
 * length the server announced, it fails with SDV-DOC-009 ("The download didn't finish."), with the request's reference.
 */
export async function contentDownload(path: string, persona: LocalPersona): Promise<Blob> {
  const response = await binaryResponse(path, persona)
  const reference = referenceOf(response.headers.get('X-Correlation-Id'))
  const announced = Number(response.headers.get('Content-Length'))
  let blob: Blob
  try {
    blob = await response.blob()
  } catch {
    throw new ApiRequestError("The download didn't finish.", response.status, reference, DOWNLOAD_STOPPED)
  }
  if (Number.isFinite(announced) && announced > 0 && blob.size < announced) {
    throw new ApiRequestError("The download didn't finish.", response.status, reference, DOWNLOAD_STOPPED)
  }
  return blob
}

/**
 * Task 0049: an export the server registers: the file, its name from Content-Disposition, and the reference,
 * fingerprint (SHA-256) and row count the server sent in its X-SDV-Export-* headers (null when absent).
 */
export type RegisteredExport = { blob: Blob; filename: string | null; reference: string | null; fingerprint: string | null; rows: number | null }
export async function contentExport(path: string, persona: LocalPersona): Promise<RegisteredExport> {
  const response = await binaryResponse(path, persona)
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const named = /filename="?([A-Za-z0-9._-]{1,120})"?/.exec(disposition)?.[1] ?? null
  const fingerprint = response.headers.get('X-SDV-Export-Fingerprint')
  const rows = response.headers.get('X-SDV-Export-Rows')
  return {
    blob: await response.blob(),
    filename: named,
    reference: referenceOf(response.headers.get('X-SDV-Export-Reference')) ?? null,
    fingerprint: fingerprint && /^[0-9a-f]{64}$/i.test(fingerprint) ? fingerprint.toLowerCase() : null,
    rows: rows && /^\d{1,9}$/.test(rows) ? Number(rows) : null,
  }
}

async function binaryResponse(path: string, persona: LocalPersona): Promise<Response> {
  const enterpriseHeaders = await bearerHeaders()
  const response = await send(path, {
    method: 'GET',
    headers: { ...localHeaders(persona), ...entityHeaders(), ...enterpriseHeaders },
    cache: 'no-store',
    credentials: 'same-origin',
  })
  if (!response.ok) throw await failureOf(response)
  return response
}
export type SessionInfo = {
  tenantId: string
  tenantDisplayName: string
  subject: string
  roles: string[]
  capabilities: string[]
  /** From the sign-in token's name/email claims; absent or null when the token has none. Display only. */
  displayName?: string | null
  email?: string | null
}
export type SearchField = {
  fieldKey: string
  displayLabel: string
  fieldType: string
  businessIdentifier: boolean
  normalization: string
  searchMode: 'EXACT' | 'PREFIX' | 'PARTIAL'
}

export type SearchSource = {
  sourceId: string
  sourceKey: string
  displayName: string
  fields: SearchField[]
  /** Task 0023: whether the system is receiving, whether it holds any record, and when it last delivered. */
  status: 'ACTIVE' | 'PAUSED'
  hasRecords: boolean
  lastDeliveredAt: string | null
}

export type SearchSchema = {
  sources: SearchSource[]
}

export type FieldValue = {
  fieldKey: string
  displayLabel: string
  value: string
  businessIdentifier: boolean
}

export type RecordSummary = {
  recordRef: string
  /** The record's name from the source's Record title setting (or the default); null when it has none. */
  title?: string | null
  identifiers: FieldValue[]
  metadataPreview: FieldValue[]
  submissionCount: number
  availableDocumentCount: number
  latestSubmissionAt: string
  incomplete: boolean
  /** Over the record's incomplete submissions; 0 when complete. */
  incompleteExpectedCount?: number
  incompleteReceivedCount?: number
  /** Task 0022, an "anywhere" search only: every field that matched, best match first, with the record's own value. */
  matchedFields?: { fieldKey: string; displayLabel: string; value: string }[]
  /** Task 0026: the highest level among this record's documents this person can't open; null when none. */
  restrictedLevel?: SensitivityLevel | null
  /** Task 0048: documents of this record that are temporarily unavailable (quarantined) to this person; never a level. */
  unavailableDocumentCount?: number
}

/** Task 0024/0026: a document's sensitivity level. */
export type SensitivityLevel = 'STANDARD' | 'CONFIDENTIAL' | 'HIGHLY_CONFIDENTIAL'

/** Task 0026: a document this person can't open: its reference and level only. Opening it is refused (SDV-SEN-008). */
export type LockedDocument = { documentId: string; effectiveSensitivityLevel: SensitivityLevel }

export type SearchResponse = {
  sourceId: string
  records: RecordSummary[]
  page: number
  pageSize: number
  hasMore: boolean
  truncated: boolean
}

export type RecordDocument = {
  documentId: string
  submissionId: string
  sourceDocumentId: string | null
  originalFilename: string
  ingestedAt: string
  availableAt: string | null
  expiresAt: string | null
  /** Worked out when the document was stored; absent or null for documents stored before that. */
  pageCount?: number | null
  /** A permanent correction (redaction) has been applied. */
  redacted?: boolean
  sensitivityLevel?: SensitivityLevel
  /** The higher of its own level and its record's level. */
  effectiveSensitivityLevel?: SensitivityLevel
  /** Task 0048: quarantined; only a reviewer (a Steward who can open its level) gets it in this list. */
  quarantined?: boolean
}

export type IncompleteSubmission = {
  submissionId: string
  status: string
  expectedCount: number
  receivedCount: number
  missingCount: number
  missingSourceDocumentIds: string[]
}

export type BusinessRecord = {
  recordRef: string
  title?: string | null
  sourceId: string
  sourceKey: string
  sourceDisplayName: string
  identifiers: FieldValue[]
  metadata: FieldValue[]
  submissionCount: number
  latestSubmissionAt: string
  documents: RecordDocument[]
  incompleteAttention: { submissions: IncompleteSubmission[] } | null
  recordSensitivityLevel?: SensitivityLevel | null
  /** Task 0026: documents this person can't open; they are not in `documents`. */
  lockedDocuments?: LockedDocument[]
  restrictedLevel?: SensitivityLevel | null
  /** Task 0048: quarantined documents this person can't open: the reference only, never a level. */
  unavailableDocuments?: { documentId: string }[]
}
