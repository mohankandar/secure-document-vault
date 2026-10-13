import { bearerHeaders, expireOidcSession, isLocalSecurityMode, isOidcSecurityMode } from './auth'

export const LOCAL_TENANT_ID = '11111111-1111-1111-1111-111111111111'

export type LocalPersona = 'admin' | 'business' | 'steward' | 'businessAdmin' | 'tenantIt' | 'platform'

const localHeaders = (persona: LocalPersona): Record<string, string> => {
  if (!isLocalSecurityMode()) return {}
  const identity = persona === 'steward'
    ? { subject: 'local-document-steward', roles: 'DOCUMENT_STEWARD' }
    : persona === 'business'
      ? { subject: 'local-business-user', roles: 'BUSINESS_USER' }
      : persona === 'businessAdmin'
      ? { subject: 'local-business-admin', roles: 'BUSINESS_ADMIN' }
      : persona === 'tenantIt'
        ? { subject: 'local-tenant-it-admin', roles: 'TENANT_IT_ADMIN' }
        : persona === 'platform'
          ? { subject: 'local-platform-operations-admin', roles: 'PLATFORM_OPERATIONS_ADMIN' }
          : { subject: 'local-administrator', roles: 'TENANT_IT_ADMIN,BUSINESS_ADMIN' }
  return {
    'X-SDV-Tenant-Id': LOCAL_TENANT_ID,
    'X-SDV-Subject': identity.subject,
    'X-SDV-Roles': identity.roles,
  }
}

export class ApiRequestError extends Error { constructor(message: string, public readonly status: number) { super(message); this.name = 'ApiRequestError' } }

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
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new ApiRequestError(body?.message ?? `HTTP ${response.status}`, response.status)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const roleApi = <T>(path: string, persona: LocalPersona, init?: RequestInit) => request<T>(path, persona, init)
export const api = <T>(path: string, init?: RequestInit) => request<T>(path, 'admin', init)
export const businessApi = <T>(path: string, init?: RequestInit) => request<T>(path, 'business', init)
export const contentApi = <T>(path: string, persona: 'business' | 'steward', init?: RequestInit) => request<T>(path, persona, init)

export async function contentBinary(path: string, persona: 'business' | 'steward'): Promise<Blob> {
  const enterpriseHeaders = await bearerHeaders()
  const response = await fetch(path, {
    method: 'GET',
    headers: { ...localHeaders(persona), ...enterpriseHeaders },
    cache: 'no-store',
    credentials: 'same-origin',
  })
  if (!response.ok) {
    if (response.status === 401 && isOidcSecurityMode()) expireOidcSession()
    const body = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(body?.message ?? `HTTP ${response.status}`)
  }
  return response.blob()
}

export type AuthenticationStatus = {
  mode: string
  enterpriseConfigured: boolean
  issuer: string | null
  jwksUri: string | null
  audience: string | null
  tenantClaim: string
  rolesClaim: string
  sourceIdClaim: string
  runtimeReloadSupported: boolean
}

export type SessionInfo = {
  tenantId: string
  subject: string
  roles: string[]
  capabilities: string[]
}

export type IngestionSource = {
  sourceId: string
  sourceKey: string
  displayName: string
  status: 'ACTIVE' | 'DISABLED'
  authType: 'OAUTH2_CLIENT_CREDENTIALS' | 'JWT_BEARER' | 'MTLS'
  credentialReference: string
  createdAt: string
  updatedAt: string
  limits?: {
    maxPdfBytes?: number | null
    maxPagesPerPdf?: number | null
    maxDocumentsPerSubmission?: number | null
    maxAggregateSubmissionBytes?: number | null
    incompleteAttentionHours?: number | null
  }
}

export type SourceOperationalSummary = {
  submissionCount: number
  documentCount: number
  lastActivityAt: string | null
  lastDocumentAvailableAt: string | null
  lastProcessingFailureAt: string | null
}

export type IngestionSourceSummary = {
  sourceId: string
  sourceKey: string
  displayName: string
  status: 'ACTIVE' | 'DISABLED'
  authType?: 'OAUTH2_CLIENT_CREDENTIALS' | 'JWT_BEARER' | 'MTLS'
}

export type MetadataField = {
  definitionId: string
  fieldKey: string
  version: number
  displayLabel: string
  fieldType: string
  required: boolean
  businessIdentifier: boolean
  searchable: boolean
  normalization: string
  searchMode: string
  active: boolean
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
  identifiers: FieldValue[]
  metadataPreview: FieldValue[]
  submissionCount: number
  availableDocumentCount: number
  latestSubmissionAt: string
  incomplete: boolean
}

export type SearchResponse = {
  sourceId: string
  records: RecordSummary[]
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
  sourceId: string
  sourceKey: string
  sourceDisplayName: string
  identifiers: FieldValue[]
  metadata: FieldValue[]
  submissionCount: number
  latestSubmissionAt: string
  documents: RecordDocument[]
  incompleteAttention: { submissions: IncompleteSubmission[] } | null
}


export type RetentionPolicyVersion = {
  policyVersionId: string
  version: number
  policyName: string
  description: string | null
  defaultRetentionDays: number | null
  indefinite: boolean
  effectiveFrom: string
  effectiveUntil: string | null
  createdBy: string
  createdAt: string
}

export type RetentionPolicy = {
  defaultRetentionDays: number | null
  indefinite: boolean
  policyVersionId: string
  version: number
  policyName: string
  description: string | null
  effectiveFrom: string
  createdBy: string
  history: RetentionPolicyVersion[]
}


export type CapacityBanner = {
  severity: 'NORMAL' | 'WARNING' | 'CRITICAL' | 'ATTENTION'
  visible: boolean
  message: string | null
  warningVolumeCount: number
  criticalVolumeCount: number
  observedAt: string
}

export type ComponentHealth = {
  component: string
  status: string
  message: string
  observedAt: string
}

export type StorageVolumeStatus = {
  volumeId: string
  displayName: string
  providerId: string
  rootPath: string
  state: 'ACTIVE' | 'READ_ONLY' | 'OFFLINE'
  priority: number
  warningPercent: number
  stopWritePercent: number
  health: string
  totalBytes: number | null
  usableBytes: number | null
  usedPercent: number | null
  writable: boolean
  warningThresholdReached: boolean
  stopWriteThresholdReached: boolean
  observationError: string | null
  observedAt: string | null
}


export type StorageVolumeAdminView = {
  volume: {
    volumeId: string
    tenantId: string
    providerId: string
    displayName: string
    rootPath: string
    state: 'ACTIVE' | 'READ_ONLY' | 'OFFLINE'
    priority: number
    warningPercent: number
    stopWritePercent: number
    createdAt: string
    updatedAt: string
  }
  capacity: {
    totalBytes: number
    usableBytes: number
    usedPercent: number
    writable: boolean
  } | null
  warningThresholdReached: boolean
  stopWriteThresholdReached: boolean
  observationError: string | null
}

export type ProcessingFailure = {
  documentId: string
  submissionId: string
  sourceId: string
  errorCode: string | null
  processingAttemptCount: number
  ingestedAt: string
}

export type StuckDocument = {
  documentId: string
  submissionId: string
  sourceId: string
  status: string
  stateSince: string
  processingErrorCode: string | null
}

export type AttentionSubmission = {
  submissionId: string
  sourceId: string
  sourceSubmissionId: string
  status: string
  expectedDocumentCount: number
  receivedDocumentCount: number
  lastActivityAt: string
  attentionHours: number
}

export type OperationalDataObservation = {
  section: string
  status: 'AVAILABLE' | 'DEGRADED'
  message: string
}

export type OperationsSummary = {
  overallStatus: string
  observedAt: string
  application: ComponentHealth
  database: ComponentHealth
  keyService: ComponentHealth
  dataObservations: OperationalDataObservation[]
  storageVolumes: StorageVolumeStatus[]
  processingFailureCount: number | null
  processingFailures: ProcessingFailure[]
  stuckDocumentCount: number | null
  stuckDocuments: StuckDocument[]
  attentionSubmissionCount: number | null
  attentionSubmissions: AttentionSubmission[]
}


export type RetentionSweepStatus = {
  outcome: 'NEVER_RUN' | 'SUCCESS' | 'FAILED'
  startedAt: string | null
  completedAt: string | null
  candidates: number
  deleted: number
  failed: number
  reasonCode: string | null
}

export type AuditEventView = {
  auditEventId: string
  actorType: string
  actorId: string
  action: string
  targetType: string
  targetId: string
  occurredAt: string
  correlationId: string | null
  outcome: 'SUCCESS' | 'DENIED' | 'FAILED'
  safeDetailsJson: string
}

export type AuditPage = {
  events: AuditEventView[]
  nextCursor: string | null
  hasMore: boolean
}

export type RoleCapabilityView = {
  roles: Record<'BUSINESS_USER' | 'DOCUMENT_STEWARD', Record<string, boolean>>
  configurableCapabilities: string[]
}

export type DocumentTag = {
  tagId: string
  documentId: string
  tag: string
  createdBy: string
  createdAt: string
}

export type DocumentComment = {
  commentId: string
  documentId: string
  comment: string
  createdBy: string
  createdAt: string
}

export type DocumentActivity = {
  auditEventId: string
  actorType: string
  actorId: string
  action: string
  occurredAt: string
  outcome: string
  safeDetailsJson: string
}

export type EnterpriseIdentityConnection = {
  connectionId: string
  tenantId: string
  providerId: string
  providerType: 'OIDC' | 'SAML' | 'LDAP_AD'
  displayName: string
  issuerUri: string
  browserClientId: string | null
  apiAudience: string
  status: 'DRAFT' | 'VALIDATED' | 'ACTIVE' | 'DISABLED'
  configurationVersion: number
  createdAt: string
  updatedAt: string
  lastTestedAt: string | null
  lastTestStatus: 'PASSED' | 'FAILED' | null
  lastTestMessage: string | null
}

export type EnterpriseIdentityOverview = {
  active: EnterpriseIdentityConnection | null
  staged: EnterpriseIdentityConnection | null
  rollbackCandidates: EnterpriseIdentityConnection[]
  activationSupported: boolean
  activationMaxTestAgeSeconds: number
}
