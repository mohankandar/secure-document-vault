// R7 prototype sample data. Shapes mirror src/api.ts so the design maps onto real
// contracts, but nothing here is fetched from or written to a backend.

export const NOW = new Date('2026-10-15T09:00:00Z').getTime()
export const ago = (days: number) => new Date(NOW - days * 86400000).toISOString()
export const ahead = (days: number) => new Date(NOW + days * 86400000).toISOString()

export type RoleId = 'business' | 'steward' | 'businessAdmin' | 'tenantIt' | 'platform'
export type AreaId = 'workspace' | 'governance' | 'tenant' | 'operations' | 'system'

export type Capability =
  | 'SEARCH_RECORDS' | 'VIEW_PDF' | 'PERMANENT_REDACTION' | 'DOWNLOAD_CURRENT_PDF' | 'PRINT_DOCUMENT'
  | 'ADD_DOCUMENT_TAG' | 'ADD_DOCUMENT_COMMENT'

export const CAPABILITY_LABELS: Record<Capability, string> = {
  SEARCH_RECORDS: 'Search business records',
  VIEW_PDF: 'View documents',
  DOWNLOAD_CURRENT_PDF: 'Download current PDF',
  PRINT_DOCUMENT: 'Print document',
  ADD_DOCUMENT_TAG: 'Add document tag',
  ADD_DOCUMENT_COMMENT: 'Add document comment',
  PERMANENT_REDACTION: 'Permanent corrective redaction',
}
export const BASELINE: Capability[] = ['SEARCH_RECORDS', 'VIEW_PDF']
export const CONFIGURABLE: Capability[] = ['DOWNLOAD_CURRENT_PDF', 'PRINT_DOCUMENT', 'ADD_DOCUMENT_TAG', 'ADD_DOCUMENT_COMMENT', 'PERMANENT_REDACTION']

export type RoleDef = { id: RoleId; code: string; name: string; short: string; person: string; initials: string; areas: AreaId[]; responsibility: string }
export const ROLES: RoleDef[] = [
  { id: 'business', code: 'BUSINESS_USER', name: 'Business User', short: 'Business', person: 'Maya Okafor', initials: 'MO', areas: ['workspace'], responsibility: 'Search and view authorized documents.' },
  { id: 'steward', code: 'DOCUMENT_STEWARD', name: 'Document Steward', short: 'Steward', person: 'Daniel Reyes', initials: 'DR', areas: ['workspace'], responsibility: 'Controlled download and permanent corrective redaction.' },
  { id: 'businessAdmin', code: 'BUSINESS_ADMIN', name: 'Business Administrator', short: 'Business admin', person: 'Priya Natarajan', initials: 'PN', areas: ['governance'], responsibility: 'Business policy and metadata administration.' },
  { id: 'tenantIt', code: 'TENANT_IT_ADMIN', name: 'Tenant IT Administrator', short: 'Tenant IT', person: 'Jonas Weber', initials: 'JW', areas: ['tenant'], responsibility: 'Storage, identity, integrations and environment.' },
  { id: 'platform', code: 'PLATFORM_OPERATIONS_ADMIN', name: 'Platform Operations', short: 'Platform ops', person: 'Aiko Tanaka', initials: 'AT', areas: ['operations'], responsibility: 'Health, incidents, intake, audit and configuration.' },
]

export const DEFAULT_ROLE_CAPS: Record<'BUSINESS_USER' | 'DOCUMENT_STEWARD', Capability[]> = {
  BUSINESS_USER: ['SEARCH_RECORDS', 'VIEW_PDF', 'PRINT_DOCUMENT', 'ADD_DOCUMENT_TAG', 'ADD_DOCUMENT_COMMENT'],
  DOCUMENT_STEWARD: ['SEARCH_RECORDS', 'VIEW_PDF', 'PRINT_DOCUMENT', 'ADD_DOCUMENT_TAG', 'ADD_DOCUMENT_COMMENT', 'DOWNLOAD_CURRENT_PDF', 'PERMANENT_REDACTION'],
}

export type SearchMode = 'EXACT' | 'PREFIX' | 'PARTIAL'
export type Field = { key: string; label: string; type: 'STRING' | 'DATE'; identifier: boolean; searchable: boolean; mode: SearchMode; normalization: 'NONE' | 'DIGITS_ONLY' | 'UPPERCASE'; required: boolean }
export type Source = { id: string; key: string; name: string; status: 'ACTIVE' | 'DISABLED'; authType: 'OAUTH2_CLIENT_CREDENTIALS' | 'JWT_BEARER' | 'MTLS'; credentialRef: string; createdAt: string; updatedAt: string; submissions: number; documents: number; lastActivityAt: string; fields: Field[] }

const contractFields: Field[] = [
  { key: 'contractNumber', label: 'Contract number', type: 'STRING', identifier: true, searchable: true, mode: 'EXACT', normalization: 'DIGITS_ONLY', required: true },
  { key: 'memberNumber', label: 'Member number', type: 'STRING', identifier: true, searchable: true, mode: 'EXACT', normalization: 'DIGITS_ONLY', required: true },
  { key: 'lastName', label: 'Member last name', type: 'STRING', identifier: false, searchable: true, mode: 'PREFIX', normalization: 'UPPERCASE', required: false },
  { key: 'serviceEntity', label: 'Service entity', type: 'STRING', identifier: false, searchable: true, mode: 'PARTIAL', normalization: 'NONE', required: false },
  { key: 'region', label: 'Region', type: 'STRING', identifier: false, searchable: true, mode: 'EXACT', normalization: 'NONE', required: false },
  { key: 'effectiveDate', label: 'Effective date', type: 'DATE', identifier: false, searchable: false, mode: 'EXACT', normalization: 'NONE', required: false },
]
const claimFields: Field[] = [
  { key: 'claimNumber', label: 'Claim number', type: 'STRING', identifier: true, searchable: true, mode: 'EXACT', normalization: 'UPPERCASE', required: true },
  { key: 'memberNumber', label: 'Member number', type: 'STRING', identifier: true, searchable: true, mode: 'EXACT', normalization: 'DIGITS_ONLY', required: true },
  { key: 'providerName', label: 'Provider', type: 'STRING', identifier: false, searchable: true, mode: 'PARTIAL', normalization: 'NONE', required: false },
  { key: 'claimType', label: 'Claim type', type: 'STRING', identifier: false, searchable: true, mode: 'EXACT', normalization: 'NONE', required: false },
]
const credFields: Field[] = [
  { key: 'npi', label: 'NPI', type: 'STRING', identifier: true, searchable: true, mode: 'EXACT', normalization: 'DIGITS_ONLY', required: true },
  { key: 'providerName', label: 'Provider name', type: 'STRING', identifier: false, searchable: true, mode: 'PREFIX', normalization: 'NONE', required: false },
  { key: 'specialty', label: 'Specialty', type: 'STRING', identifier: false, searchable: false, mode: 'EXACT', normalization: 'NONE', required: false },
]

export const SOURCES: Source[] = [
  { id: 'src-contracts', key: 'member-contracts', name: 'Member Contracts', status: 'ACTIVE', authType: 'OAUTH2_CLIENT_CREDENTIALS', credentialRef: 'vault://northwind/ingest/member-contracts', createdAt: ago(412), updatedAt: ago(18), submissions: 48211, documents: 131904, lastActivityAt: ago(0.01), fields: contractFields },
  { id: 'src-claims', key: 'claims-intake', name: 'Claims Intake', status: 'ACTIVE', authType: 'MTLS', credentialRef: 'pki://northwind/claims-gateway', createdAt: ago(301), updatedAt: ago(4), submissions: 202447, documents: 388120, lastActivityAt: ago(0.003), fields: claimFields },
  { id: 'src-cred', key: 'provider-credentialing', name: 'Provider Credentialing', status: 'DISABLED', authType: 'JWT_BEARER', credentialRef: 'vault://northwind/ingest/credentialing', createdAt: ago(190), updatedAt: ago(33), submissions: 3120, documents: 9804, lastActivityAt: ago(33), fields: credFields },
]

export type Tag = { id: string; tag: string; by: string; at: string }
export type Comment = { id: string; text: string; by: string; at: string }
export type Activity = { id: string; action: string; actor: string; at: string; outcome: 'SUCCESS' | 'DENIED' | 'FAILED'; simulated?: boolean }
export type Redaction = { page: number; x: number; y: number; w: number; h: number }
export type Doc = { id: string; filename: string; kind: string; pages: number; ingestedAt: string; availableAt: string; expiresAt: string; submissionId: string; sizeKb: number; tags: Tag[]; comments: Comment[]; activity: Activity[]; redactions: Redaction[] }
export type RecordItem = { ref: string; sourceId: string; identifiers: { label: string; value: string }[]; metadata: { label: string; value: string }[]; title: string; updatedAt: string; submissions: number; incomplete: null | { submissionId: string; expected: number; received: number; since: string }; docs: Doc[] }

const KINDS = ['Member Agreement', 'Enrollment Form', 'Coverage Amendment', 'Premium Notice', 'Consent to Release', 'Claim Form', 'Explanation of Benefits', 'Credentialing Packet']
let docSeq = 0
const mkDoc = (kind: string, year: number, ageDays: number, pages: number, extra: Partial<Doc> = {}): Doc => {
  docSeq += 1
  const id = `doc-${(0x1a2b00 + docSeq * 7919).toString(16)}`
  return {
    id, kind, pages, filename: `${kind.replace(/ /g, '_')}_${year}.pdf`, ingestedAt: ago(ageDays), availableAt: ago(ageDays - 0.002),
    expiresAt: ahead(2555 - ageDays), submissionId: `sub-${(0x5c00 + docSeq * 131).toString(16)}`, sizeKb: 180 + ((docSeq * 97) % 900),
    tags: [], comments: [], activity: [{ id: `${id}-a0`, action: 'DOCUMENT_AVAILABLE', actor: 'ingestion-worker', at: ago(ageDays - 0.002), outcome: 'SUCCESS' }], redactions: [], ...extra,
  }
}

const people = [
  ['100245', '88123401', 'Alvarez', 'Northwind Health Plan', 'West'],
  ['100246', '88123402', 'Brennan', 'Northwind Health Plan', 'West'],
  ['100251', '88123477', 'Chen', 'Contoso Benefits', 'Central'],
  ['100263', '88124019', 'Dubois', 'Northwind Health Plan', 'East'],
  ['100270', '88124188', 'Eriksen', 'Fabrikam Mutual', 'North'],
  ['100288', '88125230', 'Alvarez', 'Contoso Benefits', 'South'],
  ['100301', '88126012', 'Fofanah', 'Fabrikam Mutual', 'Central'],
  ['100317', '88126549', 'Gallagher', 'Northwind Health Plan', 'East'],
] as const

export const RECORDS: RecordItem[] = people.map(([contract, member, last, entity, region], i) => {
  const count = [4, 2, 3, 1, 2, 3, 1, 2][i]
  const docs = Array.from({ length: count }, (_, d) => mkDoc(KINDS[d % 5], 2026 - Math.floor(d / 2), 6 + i * 9 + d * 37, [6, 2, 3, 1, 2][d % 5]))
  if (i === 0) {
    docs[0].tags = [{ id: 't1', tag: 'Renewal', by: 'Maya Okafor', at: ago(3) }, { id: 't2', tag: 'Signature verified', by: 'Daniel Reyes', at: ago(2) }]
    docs[0].comments = [{ id: 'c1', text: 'Signature verified against the enrollment form on file.', by: 'Daniel Reyes', at: ago(2) }, { id: 'c2', text: 'Member requested a copy for the renewal call on Thursday.', by: 'Maya Okafor', at: ago(1) }]
    docs[0].activity.unshift(
      { id: 'a3', action: 'DOCUMENT_PRINTED', actor: 'Maya Okafor', at: ago(0.8), outcome: 'SUCCESS' },
      { id: 'a2', action: 'DOCUMENT_DOWNLOADED', actor: 'Daniel Reyes', at: ago(1.2), outcome: 'SUCCESS' },
      { id: 'a1', action: 'DOCUMENT_VIEWED', actor: 'Maya Okafor', at: ago(1.3), outcome: 'SUCCESS' },
    )
  }
  return {
    ref: `rec-${contract}`, sourceId: 'src-contracts', title: `${last} · ${entity}`,
    identifiers: [{ label: 'Contract number', value: contract }, { label: 'Member number', value: member }],
    metadata: [{ label: 'Member last name', value: last }, { label: 'Service entity', value: entity }, { label: 'Region', value: region }, { label: 'Effective date', value: `2026-0${(i % 9) + 1}-01` }],
    updatedAt: docs[0].ingestedAt, submissions: count, docs,
    incomplete: i === 2 ? { submissionId: 'sub-77a1', expected: 4, received: 3, since: ago(0.4) } : null,
  }
})

const claims = [['CLM-20931', '88123401', 'Riverside Medical Group', 'Professional'], ['CLM-20944', '88124188', 'Lakeview Hospital', 'Institutional'], ['CLM-21002', '88126549', 'Riverside Medical Group', 'Pharmacy']] as const
claims.forEach(([claim, member, provider, type], i) => {
  const docs = [mkDoc('Claim Form', 2026, 2 + i * 4, 2), mkDoc('Explanation of Benefits', 2026, 1 + i * 4, 1)]
  RECORDS.push({ ref: `rec-${claim}`, sourceId: 'src-claims', title: `${provider} · ${type}`, identifiers: [{ label: 'Claim number', value: claim }, { label: 'Member number', value: member }], metadata: [{ label: 'Provider', value: provider }, { label: 'Claim type', value: type }], updatedAt: docs[1].ingestedAt, submissions: 1, docs, incomplete: null })
})

export const PAGE_TEXT: Record<string, string[]> = {
  'Member Agreement': ['This Member Agreement ("Agreement") is entered into between the Plan and the Member identified below and governs the coverage described in the attached Schedule of Benefits.', '1. Eligibility. Coverage begins on the effective date shown, provided the first premium has been received and the enrollment form is complete.', '2. Premiums. Premiums are due on the first day of each month. A grace period of thirty-one days applies before coverage is suspended.', '3. Member responsibilities. The Member agrees to provide accurate information and to notify the Plan of any change of address within thirty days.', '4. Confidentiality. Member identifiers including Social Security Number 412-55-0193 are held under the Plan privacy notice.', '5. Signatures. By signing, the Member confirms receipt of the Summary of Benefits and Coverage.'],
  default: ['This document was submitted by an authorized producer source and stored encrypted at rest in the tenant document vault.', 'Section A. Summary of the request, including identifiers and the effective period of the requested change.', 'Section B. Supporting detail as provided by the submitting party. Reviewer notes are recorded separately as document comments.', 'Section C. Attestation. The submitting party attests that the information provided is complete and accurate.'],
}

export const RETENTION = {
  active: { version: 3, days: 2555, effectiveAt: ago(40), activatedBy: 'Priya Natarajan', note: 'Seven-year retention per records schedule RS-14.' },
  history: [
    { version: 3, days: 2555, effectiveAt: ago(40), activatedBy: 'Priya Natarajan', status: 'ACTIVE' },
    { version: 2, days: 2190, effectiveAt: ago(400), activatedBy: 'Priya Natarajan', status: 'SUPERSEDED' },
    { version: 1, days: 1825, effectiveAt: ago(780), activatedBy: 'Platform bootstrap', status: 'SUPERSEDED' },
  ],
}

export type Volume = { id: string; name: string; path: string; state: 'ACTIVE' | 'READ_ONLY' | 'DISABLED'; health: 'UP' | 'DEGRADED' | 'DOWN'; writable: boolean; usedPct: number; totalGb: number; warnPct: number; stopPct: number; observedAt: string; error: string | null }
export const VOLUMES: Volume[] = [
  { id: 'vol-01', name: 'Primary vault', path: '/mnt/sdv/vault-01', state: 'ACTIVE', health: 'UP', writable: true, usedPct: 61, totalGb: 4096, warnPct: 80, stopPct: 95, observedAt: ago(0.002), error: null },
  { id: 'vol-02', name: 'Secondary vault', path: '/mnt/sdv/vault-02', state: 'ACTIVE', health: 'UP', writable: true, usedPct: 86, totalGb: 2048, warnPct: 80, stopPct: 95, observedAt: ago(0.002), error: null },
  { id: 'vol-00', name: 'Legacy archive', path: '/mnt/sdv/archive-00', state: 'READ_ONLY', health: 'UP', writable: false, usedPct: 98, totalGb: 1024, warnPct: 80, stopPct: 95, observedAt: ago(0.002), error: null },
]

export type IdentityConfig = { version: number; name: string; type: 'OIDC'; issuer: string; clientId: string; audience: string; rolesClaim: string; updatedAt: string; updatedBy: string }
export const IDENTITY = {
  active: { version: 4, name: 'Northwind Entra ID', type: 'OIDC', issuer: 'https://login.microsoftonline.com/7f3c…/v2.0', clientId: 'sdv-web-7c21', audience: 'api://sdv-northwind', rolesClaim: 'roles', updatedAt: ago(26), updatedBy: 'Jonas Weber' } as IdentityConfig,
  draft: { version: 5, name: 'Northwind Entra ID', type: 'OIDC', issuer: 'https://login.microsoftonline.com/7f3c…/v2.0', clientId: 'sdv-web-9d40', audience: 'api://sdv-northwind', rolesClaim: 'roles', updatedAt: ago(0.2), updatedBy: 'Jonas Weber' } as IdentityConfig | null,
  rollback: [
    { version: 3, name: 'Northwind Entra ID', type: 'OIDC', issuer: 'https://login.microsoftonline.com/7f3c…/v2.0', clientId: 'sdv-web-55aa', audience: 'api://sdv-northwind', rolesClaim: 'roles', updatedAt: ago(140), updatedBy: 'Jonas Weber' },
    { version: 2, name: 'Northwind Okta', type: 'OIDC', issuer: 'https://northwind.okta.com/oauth2/default', clientId: '0oa8sdv', audience: 'api://sdv', rolesClaim: 'groups', updatedAt: ago(390), updatedBy: 'Platform bootstrap' },
  ] as IdentityConfig[],
}

export const SYSTEM = { product: 'Secure Document Vault', tenant: 'Northwind Health', environment: 'Production', version: '1.8.2', build: '2026.10.09-4f1c2a', profiles: ['prod', 'oidc', 'postgres', 'fs-vault'], region: 'eu-central', observedAt: ago(0.002) }

export type Service = { name: string; status: 'UP' | 'DEGRADED' | 'DOWN'; detail: string; latencyMs: number | null }
export const SERVICES: Service[] = [
  { name: 'Application API', status: 'UP', detail: '3 of 3 instances ready', latencyMs: 42 },
  { name: 'Metadata database', status: 'UP', detail: 'Primary + 1 replica, lag 0.4s', latencyMs: 6 },
  { name: 'Document vault storage', status: 'DEGRADED', detail: 'Secondary vault above warning threshold', latencyMs: 11 },
  { name: 'Ingestion worker', status: 'UP', detail: 'Queue depth 14, oldest 38s', latencyMs: null },
  { name: 'Identity provider', status: 'UP', detail: 'JWKS refreshed 4 min ago', latencyMs: 88 },
  { name: 'Retention sweep', status: 'UP', detail: 'Last run succeeded', latencyMs: null },
]

export const FAILURES = [
  { id: 'doc-7f21a0', source: 'Claims Intake', filename: 'Claim_Form_CLM-21877.pdf', errorCode: 'PDF_PARSE_FAILED', attempts: 3, at: ago(0.05), submissionId: 'sub-9a10' },
  { id: 'doc-7f22c4', source: 'Member Contracts', filename: 'Enrollment_Form_2026.pdf', errorCode: 'ENCRYPTION_KEY_UNAVAILABLE', attempts: 1, at: ago(0.3), submissionId: 'sub-9a3e' },
]
export const STUCK = [{ id: 'doc-6e0b19', source: 'Member Contracts', state: 'RECEIVED', since: ago(0.21), filename: 'Coverage_Amendment_2026.pdf', submissionId: 'sub-77a1' }]
export type Submission = { id: string; sourceSubmissionId: string; source: string; status: 'OPEN' | 'RECEIVING' | 'COMPLETE' | 'INCOMPLETE'; createdAt: string; expected: { name: string; state: 'EXPECTED' | 'RECEIVED' }[]; recordRef: string | null }
export const SUBMISSIONS: Submission[] = [
  { id: 'sub-77a1', sourceSubmissionId: 'NW-CT-2026-104552', source: 'Member Contracts', status: 'INCOMPLETE', createdAt: ago(0.4), recordRef: 'rec-100251', expected: [{ name: 'Member Agreement', state: 'RECEIVED' }, { name: 'Enrollment Form', state: 'RECEIVED' }, { name: 'Coverage Amendment', state: 'RECEIVED' }, { name: 'Consent to Release', state: 'EXPECTED' }] },
  { id: 'sub-9a10', sourceSubmissionId: 'CLM-21877', source: 'Claims Intake', status: 'RECEIVING', createdAt: ago(0.06), recordRef: null, expected: [{ name: 'Claim Form', state: 'RECEIVED' }, { name: 'Explanation of Benefits', state: 'EXPECTED' }] },
  { id: 'sub-9a3e', sourceSubmissionId: 'NW-CT-2026-104590', source: 'Member Contracts', status: 'OPEN', createdAt: ago(0.31), recordRef: null, expected: [{ name: 'Enrollment Form', state: 'EXPECTED' }] },
]

export const RETENTION_SWEEP = { outcome: 'SUCCESS', startedAt: ago(0.5), completedAt: ago(0.49), candidates: 14, deleted: 14, failed: 0 }

export type AuditEvent = { id: string; actorType: 'USER' | 'SERVICE'; actor: string; action: string; targetType: string; target: string; at: string; correlationId: string | null; outcome: 'SUCCESS' | 'DENIED' | 'FAILED'; details: Record<string, string | number> }
export const AUDIT: AuditEvent[] = [
  { id: 'e01', actorType: 'USER', actor: 'daniel.reyes', action: 'DOCUMENT_REDACTED', targetType: 'DOCUMENT', target: 'doc-1a2b3c', at: ago(0.05), correlationId: 'corr-8812aa', outcome: 'SUCCESS', details: { pages: '2', regions: 1 } },
  { id: 'e02', actorType: 'USER', actor: 'maya.okafor', action: 'DOCUMENT_DOWNLOADED', targetType: 'DOCUMENT', target: 'doc-1a2b3c', at: ago(0.1), correlationId: 'corr-8811f0', outcome: 'DENIED', details: { reason: 'MISSING_CAPABILITY' } },
  { id: 'e03', actorType: 'SERVICE', actor: 'ingestion-worker', action: 'DOCUMENT_PROCESSING_FAILED', targetType: 'DOCUMENT', target: 'doc-7f21a0', at: ago(0.05), correlationId: 'corr-87aa10', outcome: 'FAILED', details: { errorCode: 'PDF_PARSE_FAILED', attempt: 3 } },
  { id: 'e04', actorType: 'USER', actor: 'jonas.weber', action: 'IDENTITY_DRAFT_TESTED', targetType: 'IDENTITY_CONFIGURATION', target: 'v5', at: ago(0.2), correlationId: 'corr-86f001', outcome: 'SUCCESS', details: { checks: 4 } },
  { id: 'e05', actorType: 'SERVICE', actor: 'retention-sweep', action: 'RETENTION_SWEEP_COMPLETED', targetType: 'TENANT', target: 'northwind', at: ago(0.49), correlationId: 'corr-85e910', outcome: 'SUCCESS', details: { candidates: 14, deleted: 14 } },
  { id: 'e06', actorType: 'USER', actor: 'maya.okafor', action: 'DOCUMENT_PRINTED', targetType: 'DOCUMENT', target: 'doc-1a2b3c', at: ago(0.8), correlationId: 'corr-84d2b1', outcome: 'SUCCESS', details: {} },
  { id: 'e07', actorType: 'USER', actor: 'priya.natarajan', action: 'ROLE_CAPABILITY_UPDATED', targetType: 'ROLE', target: 'BUSINESS_USER', at: ago(5), correlationId: 'corr-80aa02', outcome: 'SUCCESS', details: { capability: 'PRINT_DOCUMENT', enabled: 'true' } },
  { id: 'e08', actorType: 'USER', actor: 'priya.natarajan', action: 'RETENTION_POLICY_ACTIVATED', targetType: 'RETENTION_POLICY', target: 'v3', at: ago(40), correlationId: null, outcome: 'SUCCESS', details: { version: 3, days: 2555 } },
  { id: 'e09', actorType: 'USER', actor: 'jonas.weber', action: 'STORAGE_VOLUME_UPDATED', targetType: 'STORAGE_VOLUME', target: 'vol-00', at: ago(61), correlationId: 'corr-7a1100', outcome: 'SUCCESS', details: { state: 'READ_ONLY' } },
]

export const BUSINESS_LIMITS = { maxResults: 200, pageSize: 25, maxCommentLength: 2000, maxTagLength: 40 }
