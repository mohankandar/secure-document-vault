import { bearerHeaders, expireOidcSession, isLocalSecurityMode, isOidcSecurityMode } from './auth'

const LOCAL_TENANT_ID = '11111111-1111-1111-1111-111111111111'

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
  const effectivePersona: LocalPersona = selected ?? (localTestRole === 'BUSINESS_USER' ? 'business' : localTestRole === 'BUSINESS_ADMIN' ? 'businessAdmin' : localTestRole === 'TENANT_IT_ADMIN' ? 'tenantIt' : localTestRole === 'PLATFORM_OPERATIONS_ADMIN' ? 'platform' : localTestRole === 'DOCUMENT_STEWARD' ? 'steward' : localTestRole === 'RESTRICTED_STEWARD' ? 'restricted' : persona)
  const identity = effectivePersona === 'steward'
    ? { subject: 'local-document-steward', roles: 'DOCUMENT_STEWARD' }
    : effectivePersona === 'restricted'
    ? { subject: 'local-restricted-steward', roles: 'RESTRICTED_STEWARD' }
    : effectivePersona === 'business'
      ? { subject: 'local-business-user', roles: 'BUSINESS_USER' }
      : effectivePersona === 'businessAdmin'
      ? { subject: 'local-business-admin', roles: 'BUSINESS_ADMIN' }
      : effectivePersona === 'tenantIt'
        ? { subject: 'local-tenant-it-admin', roles: 'TENANT_IT_ADMIN' }
        : effectivePersona === 'platform'
          ? { subject: 'local-platform-operations-admin', roles: 'PLATFORM_OPERATIONS_ADMIN' }
          : { subject: 'local-administrator', roles: 'TENANT_IT_ADMIN,BUSINESS_ADMIN' }
  return {
    'X-SDV-Tenant-Id': LOCAL_TENANT_ID,
    'X-SDV-Subject': identity.subject,
    'X-SDV-Roles': identity.roles,
  }
}

/**
 * correlationId: the server's X-Correlation-Id for the failed request, when it sent one (shown to people as a reference for IT).
 * code: the refusal's stable code (docs/api/error-codes.md), e.g. SDV-SEN-008, when the server sent one.
 */
export class ApiRequestError extends Error { constructor(message: string, public readonly status: number, public readonly correlationId?: string, public readonly code?: string) { super(message); this.name = 'ApiRequestError' } }
const codeOf = (body: { code?: unknown } | null) => (typeof body?.code === 'string' && /^SDV-[A-Z]{3}-\d{3}$/.test(body.code) ? body.code : undefined)

async function request<T>(path: string, persona: LocalPersona, init?: RequestInit): Promise<T> {
  const enterpriseHeaders = await bearerHeaders()
  const response = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...localHeaders(persona),
      ...(init?.headers ?? {}),
      ...enterpriseHeaders,
    },
    cache: 'no-store',
  })
  if (!response.ok) {
    if (response.status === 401 && isOidcSecurityMode()) expireOidcSession()
    const body = await response.json().catch(() => null) as { message?: string; code?: unknown } | null
    throw new ApiRequestError(body?.message ?? `HTTP ${response.status}`, response.status, response.headers.get('X-Correlation-Id') ?? undefined, codeOf(body))
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const roleApi = <T>(path: string, persona: LocalPersona, init?: RequestInit) => request<T>(path, persona, init)

export async function contentBinary(path: string, persona: LocalPersona): Promise<Blob> {
  const enterpriseHeaders = await bearerHeaders()
  const response = await fetch(path, {
    method: 'GET',
    headers: { ...localHeaders(persona), ...enterpriseHeaders },
    cache: 'no-store',
    credentials: 'same-origin',
  })
  if (!response.ok) {
    if (response.status === 401 && isOidcSecurityMode()) expireOidcSession()
    const body = await response.json().catch(() => null) as { message?: string; code?: unknown } | null
    throw new ApiRequestError(body?.message ?? `HTTP ${response.status}`, response.status, response.headers.get('X-Correlation-Id') ?? undefined, codeOf(body))
  }
  return response.blob()
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
}
