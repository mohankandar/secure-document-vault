// Shared R7 types and role definitions. Every value shown on screen comes from the server; nothing here is sample data.
import type { SensitivityLevel } from '../api'

export type RoleId = 'business' | 'steward' | 'restricted' | 'businessAdmin' | 'tenantIt' | 'platform'
export type AreaId = 'home' | 'workspace' | 'governance' | 'tenant' | 'operations' | 'system'

export type Capability =
  | 'SEARCH_RECORDS' | 'VIEW_PDF' | 'PERMANENT_REDACTION' | 'DOWNLOAD_CURRENT_PDF' | 'PRINT_DOCUMENT'
  | 'ADD_DOCUMENT_TAG' | 'ADD_DOCUMENT_COMMENT'
  | 'CONFIGURE_ROLE_PERMISSIONS' | 'CONFIGURE_RETENTION' | 'CONFIGURE_METADATA_SEARCH' | 'CONFIGURE_BUSINESS_LIMITS'
  // Task 0029: sensitivity levels (tasks 0025 and 0028).
  | 'VIEW_CONFIDENTIAL' | 'VIEW_HIGHLY_CONFIDENTIAL' | 'CHANGE_SENSITIVITY_LEVEL' | 'REQUEST_SENSITIVE_ACCESS'
  // Task 0030: the Business admin's Approvals and Legal holds (tasks 0027 and 0028).
  | 'APPROVE_SENSITIVE_ACCESS' | 'MANAGE_LEGAL_HOLD'
  // Task 0032: the Reports tab (Business admin only).
  | 'VIEW_INTAKE_REPORTS'

/** The capabilities the R7 screens know about; the session's other capabilities are ignored by `can`. */
export const KNOWN_CAPABILITIES: readonly Capability[] = [
  'SEARCH_RECORDS', 'VIEW_PDF', 'PERMANENT_REDACTION', 'DOWNLOAD_CURRENT_PDF', 'PRINT_DOCUMENT', 'ADD_DOCUMENT_TAG', 'ADD_DOCUMENT_COMMENT',
  'CONFIGURE_ROLE_PERMISSIONS', 'CONFIGURE_RETENTION', 'CONFIGURE_METADATA_SEARCH', 'CONFIGURE_BUSINESS_LIMITS',
  'VIEW_CONFIDENTIAL', 'VIEW_HIGHLY_CONFIDENTIAL', 'CHANGE_SENSITIVITY_LEVEL', 'REQUEST_SENSITIVE_ACCESS',
  'APPROVE_SENSITIVE_ACCESS', 'MANAGE_LEGAL_HOLD', 'VIEW_INTAKE_REPORTS',
]

/** Each role the server can name (code) and the areas it opens. */
export type RoleDef = { id: RoleId; code: string; areas: AreaId[] }
export const ROLES: RoleDef[] = [
  { id: 'business', code: 'BUSINESS_USER', areas: ['home', 'workspace'] },
  { id: 'steward', code: 'DOCUMENT_STEWARD', areas: ['home', 'workspace'] },
  { id: 'restricted', code: 'RESTRICTED_STEWARD', areas: ['home', 'workspace'] },
  { id: 'businessAdmin', code: 'BUSINESS_ADMIN', areas: ['governance'] },
  { id: 'tenantIt', code: 'TENANT_IT_ADMIN', areas: ['tenant'] },
  { id: 'platform', code: 'PLATFORM_OPERATIONS_ADMIN', areas: ['operations'] },
]

export type SearchMode = 'EXACT' | 'PREFIX' | 'PARTIAL'
export type Field = { key: string; label: string; type: 'STRING' | 'DATE'; identifier: boolean; searchable: boolean; mode: SearchMode; normalization: 'NONE' | 'DIGITS_ONLY' | 'UPPERCASE'; /** The server's normalization as configured (e.g. TRIM, LOWERCASE); used to route search-box input. */ normalizationRaw?: string }
/** status, hasRecords and lastDeliveredAt come from the search schema (task 0023); lastDeliveredAt is null with no record. */
export type Source = { id: string; key: string; name: string; status: 'ACTIVE' | 'PAUSED'; hasRecords: boolean; lastDeliveredAt: string | null; fields: Field[] }

/** A region marked for permanent redaction, in percent of the page. */
export type Redaction = { page: number; x: number; y: number; w: number; h: number }
export type Doc = { id: string; filename: string; kind: string; pages: number; ingestedAt: string; expiresAt: string; submissionId: string; /** the server says a correction was applied */ redacted?: boolean; /** task 0026: the effective level */ level?: SensitivityLevel; /** task 0029: the document's own level (the effective one is the higher of it and its record's) */ ownLevel?: SensitivityLevel }
/** Task 0026: a document this person can't open; only its reference and level. */
export type LockedDoc = { id: string; level: SensitivityLevel }
export type RecordItem = { ref: string; sourceId: string; identifiers: { label: string; value: string }[]; metadata: { label: string; value: string }[]; title: string; updatedAt: string; incomplete: null | { expected: number; received: number; /** Source document IDs still expected (record details only). */ missing?: string[] }; docs: Doc[]; /** task 0026, record details only */ locked?: LockedDoc[]; /** task 0029, record details only: the record's own level; null when it has none, undefined before the record loaded */ recordLevel?: SensitivityLevel | null }

/** Business limits as the screens show them (comment and tag length, page size). */
export const BUSINESS_LIMITS = { pageSize: 25, maxCommentLength: 2000, maxTagLength: 80 }
