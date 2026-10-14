import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react'
import { PdfViewer } from './PdfViewer'
import BusinessWorkspace from './BusinessWorkspace'
import Icon, { type IconName } from './Icon'
import { beginOidcLogin, clearOidcSession, endOidcSession, getAccessToken, handleOidcCallback, isLocalSecurityMode, OIDC_SESSION_EXPIRED_EVENT } from './auth'
import {
  roleApi,
  contentApi,
  BusinessRecord,
  IngestionSource,
  IngestionSourceSummary,
  MetadataField,
  RecordSummary,
  SearchField,
  SearchSchema,
  SessionInfo,
  RetentionPolicy,
  CapacityBanner,
  OperationsSummary,
  AuditPage,
  AuditEventView,
  StorageVolumeStatus,
  StorageVolumeAdminView,
  LocalPersona,
  EnterpriseIdentityOverview,
  EnterpriseIdentityConnection,
  ApiRequestError,
  RoleCapabilityView,
  SourceOperationalSummary,
  RetentionSweepStatus,
} from './api'

type SystemInfo = {
  productCode: string
  productName: string
  version: string
  status: string
  activeProfiles: string[]
}

type View = 'search' | 'admin' | 'operations' | 'audit'
type BusinessAdminTab = 'access' | 'roles' | 'retention' | 'metadata'
type TenantItTab = 'storage' | 'authentication' | 'integrations' | 'environment'
type PlatformTab = 'overview' | 'storage' | 'health' | 'incidents' | 'audit' | 'configuration'
type AdminDrawerMode = 'retention-create' | 'metadata-edit' | 'storage-create' | 'storage-edit' | 'integration-create' | 'integration-edit' | 'source-field-create' | 'source-field-edit' | null

type DemoRoleOption = { value: LocalPersona; label: string; defaultView: View }
const DEMO_ROLES: DemoRoleOption[] = [
  { value: 'business', label: 'Business User', defaultView: 'search' },
  { value: 'steward', label: 'Document Steward', defaultView: 'search' },
  { value: 'businessAdmin', label: 'Business Administrator', defaultView: 'admin' },
  { value: 'tenantIt', label: 'Tenant IT Administrator', defaultView: 'admin' },
  { value: 'platform', label: 'Platform Operations Administrator', defaultView: 'operations' },
]

const personaForRoles = (roles: string[]): LocalPersona => {
  if (roles.includes('PLATFORM_OPERATIONS_ADMIN')) return 'platform'
  if (roles.includes('TENANT_IT_ADMIN')) return 'tenantIt'
  if (roles.includes('BUSINESS_ADMIN')) return 'businessAdmin'
  if (roles.includes('DOCUMENT_STEWARD')) return 'steward'
  return 'business'
}

function humanize(value: string | null | undefined): string {
  if (!value) return '—'
  if (!/^[A-Z0-9_]+$/.test(value)) return value
  const words = value.toLowerCase().split('_').filter(Boolean).join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

type StatusTone = 'ok' | 'warn' | 'danger' | 'neutral'

function statusTone(value: string | null | undefined): StatusTone {
  const key = (value ?? '').toUpperCase()
  if (['UP', 'HEALTHY', 'SUCCESS', 'ACTIVE', 'PASSED', 'VALIDATED', 'COMPLETE', 'COMPLETED'].includes(key)) return 'ok'
  if (['DEGRADED', 'WARNING', 'READ_ONLY', 'INCOMPLETE', 'PROCESSING', 'PARTIAL', 'STAGED', 'DRAFT'].includes(key)) return 'warn'
  if (['DOWN', 'FAILED', 'FAILURE', 'CRITICAL', 'OFFLINE', 'ERROR', 'DENIED', 'UNHEALTHY'].includes(key)) return 'danger'
  return 'neutral'
}

function StatusPill({ value }: { value: string | null | undefined }) {
  return <span className={`status-pill status-pill-${statusTone(value)}`}><span className="status-pill-dot" aria-hidden="true" />{humanize(value)}</span>
}

const VIEW_META: Record<View, { eyebrow: string; title: string; description: string }> = {
  search: { eyebrow: 'Work', title: 'Search documents', description: 'Find source-aware business records and securely open the documents associated with them.' },
  admin: { eyebrow: 'Administration', title: 'Administration', description: 'Configure retention, ingestion sources, searchable metadata, and tenant storage.' },
  operations: { eyebrow: 'Operations', title: 'Operational readiness', description: 'Monitor storage, processing health, workflow attention, and infrastructure availability.' },
  audit: { eyebrow: 'Support', title: 'Audit history', description: 'Review append-oriented technical audit evidence without exposing document content.' },
}

type NavItem = { view: View; label: string; icon: IconName; show: boolean }

const searchHint = (field: SearchField) => {
  if (field.searchMode === 'PREFIX') return 'Starts with'
  if (field.searchMode === 'PARTIAL') return 'Contains'
  return 'Exact match'
}

const formatDate = (value?: string | null) => {
  if (!value) return '—'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}


const CAPABILITY_LABELS: Record<string, string> = {
  SEARCH_RECORDS: 'Search business records',
  VIEW_PDF: 'View documents',
  PERMANENT_REDACTION: 'Permanent corrective redaction',
  DOWNLOAD_CURRENT_PDF: 'Download current PDF',
  PRINT_DOCUMENT: 'Print document',
  ADD_DOCUMENT_TAG: 'Add document tag',
  ADD_DOCUMENT_COMMENT: 'Add document comment',
  CONFIGURE_ROLE_PERMISSIONS: 'Configure role permissions',
  CONFIGURE_METADATA_SEARCH: 'Configure metadata & search',
  CONFIGURE_RETENTION: 'Configure retention policy',
  CONFIGURE_BUSINESS_LIMITS: 'Configure business limits',
}

const capabilityLabel = (capability: string) => CAPABILITY_LABELS[capability] ?? capability

const volumeNeedsAttention = (volume: StorageVolumeStatus) =>
  volume.observationError != null ||
  volume.warningThresholdReached ||
  volume.stopWriteThresholdReached ||
  (volume.state === 'ACTIVE' && (volume.health !== 'UP' || volume.writable === false))

function App() {
  const localMode = isLocalSecurityMode()
  const [authLoading, setAuthLoading] = useState(!localMode)
  const [authError, setAuthError] = useState('')
  const [authDenied, setAuthDenied] = useState(false)
  const [system, setSystem] = useState<SystemInfo | null>(null)
  const [view, setView] = useState<View>('search')
  const [session, setSession] = useState<SessionInfo | null>(null)
  const [selectedPersona, setSelectedPersona] = useState<LocalPersona>('business')
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => localStorage.getItem('sdv.ui.sidebarCollapsed') === 'true')
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [businessAdminTab, setBusinessAdminTab] = useState<BusinessAdminTab>('access')
  const [tenantItTab, setTenantItTab] = useState<TenantItTab>('storage')
  const [platformTab, setPlatformTab] = useState<PlatformTab>('overview')
  const [identityOverview, setIdentityOverview] = useState<EnterpriseIdentityOverview | null>(null)
  const [identityDraft, setIdentityDraft] = useState({ providerId: 'customer-oidc', displayName: 'Corporate identity provider', issuerUri: '', browserClientId: 'sdv-web', apiAudience: 'sdv-api' })
  const [savingIdentity, setSavingIdentity] = useState(false)
  const [testingIdentity, setTestingIdentity] = useState(false)
  const [activatingIdentity, setActivatingIdentity] = useState(false)
  const [rollingBackIdentity, setRollingBackIdentity] = useState('')
  const [roleCapabilities, setRoleCapabilities] = useState<RoleCapabilityView | null>(null)
  const [savingRoleCapabilities, setSavingRoleCapabilities] = useState(false)

  // Business experience state
  const [schema, setSchema] = useState<SearchSchema>({ sources: [] })
  const [businessSourceId, setBusinessSourceId] = useState('')
  const [criteria, setCriteria] = useState<Record<string, string>>({})
  const [results, setResults] = useState<RecordSummary[]>([])
  const [truncated, setTruncated] = useState(false)
  const [record, setRecord] = useState<BusinessRecord | null>(null)
  const [searching, setSearching] = useState(false)
  const [openingRecord, setOpeningRecord] = useState(false)
  const [viewerDocument, setViewerDocument] = useState<{ documentId: string; filename: string } | null>(null)

  // Local administration state retained from frozen Foundation.
  const [sources, setSources] = useState<IngestionSourceSummary[]>([])
  const [selectedSource, setSelectedSource] = useState('')
  const [fields, setFields] = useState<MetadataField[]>([])
  const [retentionPolicy, setRetentionPolicy] = useState<RetentionPolicy | null>(null)
  const [retentionDaysInput, setRetentionDaysInput] = useState('')
  const [retentionNameInput, setRetentionNameInput] = useState('')
  const [retentionDescriptionInput, setRetentionDescriptionInput] = useState('')
  const [selectedMetadataFieldKey, setSelectedMetadataFieldKey] = useState('')
  const [sourceDetail, setSourceDetail] = useState<IngestionSource | null>(null)
  const [sourceFields, setSourceFields] = useState<MetadataField[]>([])
  const [sourceOperations, setSourceOperations] = useState<SourceOperationalSummary | null>(null)
  const [storageHelpOpen, setStorageHelpOpen] = useState(false)
  const [storageVolumes, setStorageVolumes] = useState<StorageVolumeAdminView[]>([])
  const [adminDrawerMode, setAdminDrawerMode] = useState<AdminDrawerMode>(null)
  const [selectedStorageVolumeId, setSelectedStorageVolumeId] = useState('')
  const [selectedSourceFieldKey, setSelectedSourceFieldKey] = useState('')

  // Enterprise / Operational Readiness state (Units 19-20)
  const [capacityBanner, setCapacityBanner] = useState<CapacityBanner | null>(null)
  const [operations, setOperations] = useState<OperationsSummary | null>(null)
  const [retentionSweepStatus, setRetentionSweepStatus] = useState<RetentionSweepStatus | null>(null)
  const [auditEvents, setAuditEvents] = useState<AuditEventView[]>([])
  const [auditCursor, setAuditCursor] = useState<string | null>(null)
  const [auditHasMore, setAuditHasMore] = useState(false)
  const [auditAction, setAuditAction] = useState('')
  const [auditOutcome, setAuditOutcome] = useState('')
  const [auditActorType, setAuditActorType] = useState('')
  const [auditActorId, setAuditActorId] = useState('')
  const [auditTargetType, setAuditTargetType] = useState('')
  const [auditTargetId, setAuditTargetId] = useState('')
  const [auditCorrelationId, setAuditCorrelationId] = useState('')
  const [auditFrom, setAuditFrom] = useState('')
  const [auditTo, setAuditTo] = useState('')
  const [loadingOperations, setLoadingOperations] = useState(false)
  const [loadingAudit, setLoadingAudit] = useState(false)
  const [includeAuditViews, setIncludeAuditViews] = useState(false)

  const businessSource = useMemo(
    () => schema.sources.find((source) => source.sourceId === businessSourceId),
    [schema.sources, businessSourceId],
  )
  const selectedAdminSource = useMemo(
    () => sources.find((source) => source.sourceId === selectedSource),
    [sources, selectedSource],
  )
  const selectedMetadataField = useMemo(
    () => fields.find((field) => field.fieldKey === selectedMetadataFieldKey) ?? null,
    [fields, selectedMetadataFieldKey],
  )
  const selectedStorageVolume = useMemo(
    () => storageVolumes.find((item) => item.volume.volumeId === selectedStorageVolumeId) ?? null,
    [storageVolumes, selectedStorageVolumeId],
  )
  const selectedSourceField = useMemo(
    () => sourceFields.find((field) => field.fieldKey === selectedSourceFieldKey) ?? null,
    [sourceFields, selectedSourceFieldKey],
  )

  const canSearchRecords = session?.capabilities.includes('SEARCH_RECORDS') ?? false
  const canViewPdf = session?.capabilities.includes('VIEW_PDF') ?? false
  const canRedact = session?.capabilities.includes('PERMANENT_REDACTION') ?? false
  const canDownload = session?.capabilities.includes('DOWNLOAD_CURRENT_PDF') ?? false
  const canConfigureRetention = session?.capabilities.includes('CONFIGURE_RETENTION') ?? false
  const canConfigureMetadata = session?.capabilities.includes('CONFIGURE_METADATA_SEARCH') ?? false
  const canConfigureSources = session?.capabilities.includes('CONFIGURE_INGESTION_SOURCES') ?? false
  const canViewTechnicalFailures = session?.capabilities.includes('VIEW_TECHNICAL_FAILURES') ?? false
  const canConfigureStorage = session?.capabilities.includes('CONFIGURE_STORAGE_KEY_PROVIDER') ?? false
  const canConfigureTenantAuth = session?.capabilities.includes('CONFIGURE_TENANT_AUTH') ?? false
  const canConfigureRolePermissions = session?.capabilities.includes('CONFIGURE_ROLE_PERMISSIONS') ?? false
  const canViewAdministration = canConfigureRetention || canConfigureMetadata || canConfigureSources || canConfigureStorage || canConfigureTenantAuth
  const contentPersona: 'business' | 'steward' = selectedPersona === 'steward' ? 'steward' : 'business'

  const loadBusinessSchema = async () => {
    const data = await contentApi<SearchSchema>('/api/v1/business/search-schema', contentPersona)
    setSchema(data)
    setBusinessSourceId((current) => current || data.sources[0]?.sourceId || '')
  }

  const loadSources = async (metadataOnly = false) => {
    const path = metadataOnly ? '/api/v1/admin/metadata-sources' : '/api/v1/admin/ingestion-sources'
    const data = await roleApi<IngestionSourceSummary[]>(path, selectedPersona)
    setSources(data)
    if (!selectedSource && data.length > 0) setSelectedSource(data[0].sourceId)
  }

  const loadFields = async (sourceId: string) => {
    if (!sourceId) {
      setFields([])
      return
    }
    const loaded = await roleApi<MetadataField[]>(`/api/v1/admin/ingestion-sources/${sourceId}/metadata-fields`, selectedPersona)
    setFields(loaded)
    setSelectedMetadataFieldKey((current) => loaded.some((field) => field.fieldKey === current) ? current : (loaded[0]?.fieldKey ?? ''))
  }

  const loadSourceAdministration = async (sourceId: string) => {
    if (!sourceId) {
      setSourceDetail(null)
      setSourceFields([])
      setSourceOperations(null)
      return
    }
    const [detail, technicalFields, operational] = await Promise.all([
      roleApi<IngestionSource>(`/api/v1/admin/ingestion-sources/${sourceId}`, selectedPersona),
      roleApi<MetadataField[]>(`/api/v1/admin/ingestion-sources/${sourceId}/source-fields`, selectedPersona),
      roleApi<SourceOperationalSummary>(`/api/v1/admin/ingestion-sources/${sourceId}/operations`, selectedPersona),
    ])
    setSourceDetail(detail)
    setSourceFields(technicalFields)
    setSourceOperations(operational)
  }

  const loadStorageVolumes = async () => {
    setStorageVolumes(await roleApi<StorageVolumeAdminView[]>('/api/v1/admin/storage-volumes', selectedPersona))
  }

  const loadIdentityAdministration = async () => {
    const overview = await roleApi<EnterpriseIdentityOverview>('/api/v1/admin/enterprise-identity', selectedPersona)
    setIdentityOverview(overview)
    const editable = overview.staged ?? overview.active
    if (editable) setIdentityDraft({ providerId: editable.providerId, displayName: editable.displayName, issuerUri: editable.issuerUri, browserClientId: editable.browserClientId ?? '', apiAudience: editable.apiAudience })
    return overview
  }

  async function saveIdentityDraft(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setSavingIdentity(true); setError(''); setMessage('')
    try {
      const overview = await roleApi<EnterpriseIdentityOverview>('/api/v1/admin/enterprise-identity/draft', selectedPersona, { method: 'PUT', body: JSON.stringify(identityDraft) })
      setIdentityOverview(overview); setMessage('Authentication draft saved. The ACTIVE connection was not changed.')
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not save authentication draft.') }
    finally { setSavingIdentity(false) }
  }

  async function testIdentityDraft() {
    setTestingIdentity(true); setError(''); setMessage('')
    try {
      const overview = await roleApi<EnterpriseIdentityOverview>('/api/v1/admin/enterprise-identity/draft/test', selectedPersona, { method: 'POST' })
      setIdentityOverview(overview)
      if (overview.staged?.lastTestStatus === 'PASSED') setMessage('OIDC connection test passed. The configuration is VALIDATED and can be activated for 15 minutes.')
      else setError(overview.staged?.lastTestMessage ?? 'OIDC connection test failed.')
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not test authentication draft.') }
    finally { setTestingIdentity(false) }
  }

  async function activateIdentityDraft() {
    const staged = identityOverview?.staged
    if (!staged) return
    if (!window.confirm(`Activate version ${staged.configurationVersion}? This changes the trusted server-side OIDC binding immediately. Confirm the deployed browser/client OIDC settings already match this configuration.`)) return
    setActivatingIdentity(true); setError(''); setMessage('')
    try {
      const overview = await roleApi<EnterpriseIdentityOverview>('/api/v1/admin/enterprise-identity/draft/activate', selectedPersona, { method: 'POST', body: JSON.stringify({ connectionId: staged.connectionId, expectedConfigurationVersion: staged.configurationVersion, confirmation: 'ACTIVATE' }) })
      setIdentityOverview(overview); setMessage('Authentication configuration activated. The previous ACTIVE configuration is retained as a known-good rollback target.')
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not activate authentication configuration.') }
    finally { setActivatingIdentity(false) }
  }

  async function rollbackIdentity(candidate: EnterpriseIdentityConnection) {
    if (!window.confirm(`Rollback to ${candidate.displayName} version ${candidate.configurationVersion}? This replaces the current ACTIVE trusted binding immediately.`)) return
    setRollingBackIdentity(candidate.connectionId); setError(''); setMessage('')
    try {
      const overview = await roleApi<EnterpriseIdentityOverview>('/api/v1/admin/enterprise-identity/rollback', selectedPersona, { method: 'POST', body: JSON.stringify({ connectionId: candidate.connectionId, expectedConfigurationVersion: candidate.configurationVersion, confirmation: 'ROLLBACK' }) })
      setIdentityOverview(overview); setMessage('Authentication configuration rolled back to the selected known-good version.')
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not roll back authentication configuration.') }
    finally { setRollingBackIdentity('') }
  }

  const loadRoleCapabilities = async () => {
    setRoleCapabilities(await roleApi<RoleCapabilityView>('/api/v1/admin/role-capabilities', selectedPersona))
  }

  async function saveRoleCapabilities() {
    if (!roleCapabilities) return
    setSavingRoleCapabilities(true)
    setError('')
    setMessage('')
    try {
      const updated = await roleApi<RoleCapabilityView>('/api/v1/admin/role-capabilities', selectedPersona, { method: 'PUT', body: JSON.stringify({ roles: roleCapabilities.roles }) })
      setRoleCapabilities(updated)
      setMessage('Role permissions updated. New sessions use the updated document-action capabilities.')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not update role permissions.')
    } finally {
      setSavingRoleCapabilities(false)
    }
  }

  const loadCapacityBanner = async () => {
    setCapacityBanner(await roleApi<CapacityBanner>('/api/v1/operations/capacity-banner', selectedPersona))
  }

  const loadOperations = async () => {
    setLoadingOperations(true)
    try {
      const [summary, retentionStatus] = await Promise.all([
        roleApi<OperationsSummary>('/api/v1/operations/summary', selectedPersona),
        roleApi<RetentionSweepStatus>('/api/v1/operations/retention-status', selectedPersona),
      ])
      setOperations(summary)
      setRetentionSweepStatus(retentionStatus)
    } finally {
      setLoadingOperations(false)
    }
  }

  const loadAudit = async (reset = true) => {
    setLoadingAudit(true)
    try {
      const params = new URLSearchParams({ limit: '50' })
      if (auditAction.trim()) params.set('action', auditAction.trim())
      if (auditOutcome) params.set('outcome', auditOutcome)
      if (auditActorType.trim()) params.set('actorType', auditActorType.trim())
      if (auditActorId.trim()) params.set('actorId', auditActorId.trim())
      if (auditTargetType.trim()) params.set('targetType', auditTargetType.trim())
      if (auditTargetId.trim()) params.set('targetId', auditTargetId.trim())
      if (auditCorrelationId.trim()) params.set('correlationId', auditCorrelationId.trim())
      if (auditFrom) params.set('from', new Date(auditFrom).toISOString())
      if (auditTo) params.set('to', new Date(auditTo).toISOString())
      if (!reset && auditCursor) params.set('cursor', auditCursor)
      const page = await roleApi<AuditPage>(`/api/v1/operations/audit-events?${params.toString()}`, selectedPersona)
      setAuditEvents((current) => reset ? page.events : [...current, ...page.events])
      setAuditCursor(page.nextCursor)
      setAuditHasMore(page.hasMore)
    } finally {
      setLoadingAudit(false)
    }
  }

  async function updateVolumeState(volume: StorageVolumeStatus, state: StorageVolumeStatus['state']) {
    setError('')
    setMessage('')
    try {
      await roleApi(`/api/v1/admin/storage-volumes/${volume.volumeId}`, selectedPersona, {
        method: 'PUT',
        body: JSON.stringify({
          displayName: volume.displayName,
          state,
          priority: volume.priority,
          warningPercent: volume.warningPercent,
          stopWritePercent: volume.stopWritePercent,
        }),
      })
      setMessage(`${volume.displayName} is now ${state}.`)
      await Promise.all([loadOperations(), loadCapacityBanner(), loadStorageVolumes()])
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not update storage volume.')
    }
  }

  useEffect(() => {
    if (localMode) return
    const onSessionExpired = () => {
      setSession(null)
      setAuthDenied(false)
      setAuthError('Your organization session has expired. Sign in again to continue.')
    }
    window.addEventListener(OIDC_SESSION_EXPIRED_EVENT, onSessionExpired)
    return () => window.removeEventListener(OIDC_SESSION_EXPIRED_EVENT, onSessionExpired)
  }, [localMode])

  useEffect(() => {
    if (localMode) return
    let active = true
    const initializeOidc = async () => {
      try {
        await handleOidcCallback()
      } catch (reason) {
        clearOidcSession()
        if (active) setAuthError(reason instanceof Error ? reason.message : 'Your SDV session could not be verified. Please sign in again.')
      } finally {
        if (active) setAuthLoading(false)
      }
    }
    void initializeOidc()
    return () => { active = false }
  }, [localMode])

  useEffect(() => {
    fetch('/api/v1/system/info', { cache: 'no-store' })
      .then((response) => response.json() as Promise<SystemInfo>)
      .then(setSystem)
      .catch(() => setError('Backend is not reachable.'))
  }, [])

  useEffect(() => {
    if (!localMode && (authLoading || !getAccessToken())) {
      setSession(null)
      return
    }
    setError('')
    setMessage('')
    setViewerDocument(null)
    setRecord(null)
    setResults([])
    roleApi<SessionInfo>('/api/v1/session', selectedPersona)
      .then(async (current) => {
        setSession(current)
        if (!localMode) {
          const resolvedPersona = personaForRoles(current.roles)
          if (resolvedPersona !== selectedPersona) {
            setSelectedPersona(resolvedPersona)
            const option = DEMO_ROLES.find((item) => item.value === resolvedPersona)
            setView(option?.defaultView ?? 'search')
            return
          }
        }
        await loadCapacityBanner().catch(() => undefined)
        if (current.capabilities.includes('SEARCH_RECORDS')) {
          await loadBusinessSchema()
        } else {
          setSchema({ sources: [] })
          setBusinessSourceId('')
        }
        if (current.capabilities.includes('CONFIGURE_INGESTION_SOURCES')) {
          await loadSources(false)
        } else if (current.capabilities.includes('CONFIGURE_METADATA_SEARCH')) {
          await loadSources(true)
        } else {
          setSources([])
          setSelectedSource('')
          setFields([])
        }
        if (current.capabilities.includes('CONFIGURE_RETENTION')) {
          const policy = await roleApi<RetentionPolicy>('/api/v1/admin/retention-policy', selectedPersona)
          setRetentionPolicy(policy)
          setRetentionDaysInput(policy.defaultRetentionDays == null ? '' : String(policy.defaultRetentionDays))
          setRetentionNameInput('')
          setRetentionDescriptionInput('')
        } else {
          setRetentionPolicy(null)
          setRetentionDaysInput('')
          setRetentionNameInput('')
          setRetentionDescriptionInput('')
        }
        if (current.capabilities.includes('CONFIGURE_ROLE_PERMISSIONS')) {
          await loadRoleCapabilities()
        } else {
          setRoleCapabilities(null)
        }
        if (current.capabilities.includes('CONFIGURE_STORAGE_KEY_PROVIDER')) {
          await loadStorageVolumes()
        } else {
          setStorageVolumes([])
        }
        if (current.capabilities.includes('CONFIGURE_TENANT_AUTH')) {
          await loadIdentityAdministration().catch(() => setIdentityOverview(null))
        } else {
          setIdentityOverview(null)
        }
      })
      .catch((reason: unknown) => { setSession(null); if (!localMode && reason instanceof ApiRequestError) { if (reason.status === 403) { setAuthDenied(true); setAuthError('You are signed in, but this account does not have access to Secure Document Vault. Contact your administrator if you believe you should have access.'); } else if (reason.status === 401) { clearOidcSession(); setAuthDenied(false); setAuthError('Your organization session has expired or could not be verified. Please sign in again.'); } } })
  }, [selectedPersona, localMode, authLoading])

  useEffect(() => {
    if (!canConfigureMetadata || !selectedSource) {
      setFields([])
      return
    }
    loadFields(selectedSource).catch((reason: unknown) =>
      setError(reason instanceof Error ? reason.message : 'Could not load metadata.'))
  }, [selectedSource, canConfigureMetadata, selectedPersona])


  useEffect(() => {
    if (!canConfigureSources || !selectedSource) {
      setSourceDetail(null)
      setSourceFields([])
      setSourceOperations(null)
      return
    }
    loadSourceAdministration(selectedSource).catch((reason: unknown) =>
      setError(reason instanceof Error ? reason.message : 'Could not load integration details.'))
  }, [selectedSource, canConfigureSources, selectedPersona])

  useEffect(() => {
    if (view !== 'operations' || !canViewTechnicalFailures) return
    loadOperations().catch((reason: unknown) => setError(reason instanceof Error ? reason.message : 'Could not load operations.'))
    const timer = window.setInterval(() => {
      loadOperations().catch(() => undefined)
      loadCapacityBanner().catch(() => undefined)
    }, 30000)
    return () => window.clearInterval(timer)
  }, [view, canViewTechnicalFailures, selectedPersona])

  useEffect(() => {
    if ((view !== 'audit' && !(view === 'operations' && platformTab === 'audit')) || !canViewTechnicalFailures) return
    loadAudit(true).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : 'Could not load audit events.'))
  }, [view, platformTab, canViewTechnicalFailures, selectedPersona])

  useEffect(() => {
    setCriteria({})
    setResults([])
    setRecord(null)
    setTruncated(false)
  }, [businessSourceId])

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!businessSourceId) return
    const filtered = Object.fromEntries(
      Object.entries(criteria).filter(([, value]) => value.trim().length > 0),
    )
    if (Object.keys(filtered).length === 0) {
      setError('Enter at least one search value.')
      return
    }

    setSearching(true)
    setError('')
    setMessage('')
    setRecord(null)
    try {
      const response = await contentApi<{
        sourceId: string
        records: RecordSummary[]
        truncated: boolean
      }>('/api/v1/business/search', contentPersona, {
        method: 'POST',
        body: JSON.stringify({ sourceId: businessSourceId, criteria: filtered, limit: 25 }),
      })
      setResults(response.records)
      setTruncated(response.truncated)
      if (response.records.length === 0) setMessage('No business records matched the configured search fields.')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Search failed.')
    } finally {
      setSearching(false)
    }
  }

  async function openRecord(recordRef: string) {
    setOpeningRecord(true)
    setError('')
    try {
      setRecord(await contentApi<BusinessRecord>(
        `/api/v1/business/records/${encodeURIComponent(recordRef)}`,
        contentPersona,
      ))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not open business record.')
    } finally {
      setOpeningRecord(false)
    }
  }


  async function saveRetentionPolicy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError('')
    setMessage('')
    const trimmed = retentionDaysInput.trim()
    const days = trimmed === '' ? null : Number.parseInt(trimmed, 10)
    if (days !== null && (!Number.isFinite(days) || days <= 0)) {
      setError('Retention days must be a positive whole number, or blank for indefinite retention.')
      return
    }
    if (!retentionNameInput.trim()) {
      setError('Policy name is required so administrators can identify this retention rule later.')
      return
    }
    try {
      const policy = await roleApi<RetentionPolicy>('/api/v1/admin/retention-policy', selectedPersona, {
        method: 'PUT',
        body: JSON.stringify({
          policyName: retentionNameInput.trim(),
          description: retentionDescriptionInput.trim() || null,
          defaultRetentionDays: days,
        }),
      })
      setRetentionPolicy(policy)
      setRetentionDaysInput(policy.defaultRetentionDays == null ? '' : String(policy.defaultRetentionDays))
      setRetentionNameInput('')
      setRetentionDescriptionInput('')
      setAdminDrawerMode(null)
      setMessage(policy.indefinite
        ? 'Future documents will use indefinite retention.'
        : `Future documents will snapshot ${policy.defaultRetentionDays} retention days.`)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not update retention policy.')
    }
  }

  async function createSource(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError('')
    setMessage('')
    const formElement = event.currentTarget
    const form = new FormData(formElement)
    try {
      const created = await roleApi<IngestionSource>('/api/v1/admin/ingestion-sources', selectedPersona, {
        method: 'POST',
        body: JSON.stringify({
          sourceKey: String(form.get('sourceKey') ?? ''),
          displayName: String(form.get('displayName') ?? ''),
          authType: String(form.get('authType') ?? 'OAUTH2_CLIENT_CREDENTIALS'),
          credentialReference: String(form.get('credentialReference') ?? `local://${String(form.get('sourceKey') ?? '')}`),
          limits: {},
        }),
      })
      formElement.reset()
      await Promise.all([loadSources(false), loadBusinessSchema()])
      setSelectedSource(created.sourceId)
      setAdminDrawerMode(null)
      setMessage(`Created ingestion source ${created.sourceKey}.`)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not create source.')
    }
  }

  async function saveField(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedSource || !selectedMetadataFieldKey) return
    setError('')
    setMessage('')
    const form = new FormData(event.currentTarget)
    try {
      const saved = await roleApi<MetadataField>(
        `/api/v1/admin/metadata-sources/${selectedSource}/fields/${encodeURIComponent(selectedMetadataFieldKey)}`,
        selectedPersona,
        {
          method: 'PUT',
          body: JSON.stringify({
            displayLabel: String(form.get('displayLabel') ?? ''),
            businessIdentifier: form.get('businessIdentifier') === 'on',
            searchable: form.get('searchable') === 'on',
            normalization: String(form.get('normalization') ?? 'TRIM'),
            searchMode: form.get('searchable') === 'on' ? String(form.get('searchMode') ?? 'EXACT') : 'EXACT',
          }),
        },
      )
      await Promise.all([loadFields(selectedSource), loadBusinessSchema()])
      setSelectedMetadataFieldKey(saved.fieldKey)
      setAdminDrawerMode(null)
      setMessage(`Updated SDV behavior for ${saved.displayLabel}. The source field itself was not changed.`)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not update metadata behavior.')
    }
  }

  async function saveSourceIntegration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!sourceDetail) return
    const form = new FormData(event.currentTarget)
    setError('')
    setMessage('')
    try {
      await roleApi(`/api/v1/admin/ingestion-sources/${sourceDetail.sourceId}`, selectedPersona, {
        method: 'PUT',
        body: JSON.stringify({
          displayName: String(form.get('displayName') ?? sourceDetail.displayName),
          status: String(form.get('status') ?? sourceDetail.status),
          authType: String(form.get('authType') ?? sourceDetail.authType),
          credentialReference: String(form.get('credentialReference') ?? sourceDetail.credentialReference),
          limits: sourceDetail.limits ?? {},
        }),
      })
      await Promise.all([loadSources(false), loadSourceAdministration(sourceDetail.sourceId)])
      setAdminDrawerMode(null)
      setMessage(`Updated integration ${sourceDetail.sourceKey}.`)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not update integration.')
    }
  }

  async function saveSourceField(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedSource) return
    const formElement = event.currentTarget
    const form = new FormData(formElement)
    const fieldKey = String(form.get('fieldKey') ?? '').trim()
    setError('')
    setMessage('')
    try {
      await roleApi(`/api/v1/admin/ingestion-sources/${selectedSource}/source-fields/${encodeURIComponent(fieldKey)}`, selectedPersona, {
        method: 'PUT',
        body: JSON.stringify({
          displayLabel: String(form.get('displayLabel') ?? fieldKey),
          fieldType: String(form.get('fieldType') ?? 'TEXT'),
          required: form.get('required') === 'on',
          normalization: String(form.get('normalization') ?? 'TRIM'),
          validationRules: {},
        }),
      })
      formElement.reset()
      await loadSourceAdministration(selectedSource)
      setAdminDrawerMode(null)
      setSelectedSourceFieldKey('')
      setMessage(`Defined source field ${fieldKey}. Business Administration can now configure how SDV searches and displays it.`)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not define source field.')
    }
  }

  async function createStorageVolume(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const formElement = event.currentTarget
    const form = new FormData(formElement)
    setError('')
    setMessage('')
    try {
      const created = await roleApi<StorageVolumeAdminView>('/api/v1/admin/storage-volumes', selectedPersona, {
        method: 'POST',
        body: JSON.stringify({
          displayName: String(form.get('displayName') ?? ''),
          rootPath: String(form.get('rootPath') ?? ''),
          state: String(form.get('state') ?? 'ACTIVE'),
          priority: Number.parseInt(String(form.get('priority') ?? '1'), 10),
          warningPercent: Number.parseInt(String(form.get('warningPercent') ?? '90'), 10),
          stopWritePercent: Number.parseInt(String(form.get('stopWritePercent') ?? '95'), 10),
        }),
      })
      formElement.reset()
      await Promise.all([loadStorageVolumes(), loadCapacityBanner()])
      setAdminDrawerMode(null)
      setMessage(`Created storage volume ${created.volume.displayName}.`)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not create storage volume.')
    }
  }

  async function saveStorageVolume(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedStorageVolume) return
    const form = new FormData(event.currentTarget)
    setError('')
    setMessage('')
    try {
      await roleApi(`/api/v1/admin/storage-volumes/${selectedStorageVolume.volume.volumeId}`, selectedPersona, {
        method: 'PUT',
        body: JSON.stringify({
          displayName: String(form.get('displayName') ?? selectedStorageVolume.volume.displayName),
          state: String(form.get('state') ?? selectedStorageVolume.volume.state),
          priority: Number.parseInt(String(form.get('priority') ?? selectedStorageVolume.volume.priority), 10),
          warningPercent: Number.parseInt(String(form.get('warningPercent') ?? selectedStorageVolume.volume.warningPercent), 10),
          stopWritePercent: Number.parseInt(String(form.get('stopWritePercent') ?? selectedStorageVolume.volume.stopWritePercent), 10),
        }),
      })
      await Promise.all([loadStorageVolumes(), loadCapacityBanner()])
      setAdminDrawerMode(null)
      setMessage(`Updated storage volume ${selectedStorageVolume.volume.displayName}.`)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not update storage volume.')
    }
  }

  const navigate = (next: View) => {
    setView(next)
    setDrawerOpen(false)
    setAdminDrawerMode(null)
    setError('')
    setMessage('')
  }

  const changeDemoRole = (persona: LocalPersona) => {
    const option = DEMO_ROLES.find((item) => item.value === persona)
    // Clear the previous principal before changing personas so capability-driven
    // effects cannot issue one request with the new persona and stale capabilities.
    setSession(null)
    setError('')
    setMessage('')
    setSelectedPersona(persona)
    setView(option?.defaultView ?? 'search')
    setBusinessAdminTab('access')
    setTenantItTab('storage')
    setPlatformTab('overview')
    setDrawerOpen(false)
    setAdminDrawerMode(null)
  }

  const toggleSidebar = () => {
    setSidebarCollapsed((current) => {
      const next = !current
      localStorage.setItem('sdv.ui.sidebarCollapsed', String(next))
      return next
    })
  }

  const handleBusinessViewerState = useCallback((active: boolean) => {
    if (active) {
      setSidebarCollapsed(true)
      return
    }
    setSidebarCollapsed(localStorage.getItem('sdv.ui.sidebarCollapsed') === 'true')
  }, [])

  const signOut = () => {
    setSession(null)
    if (localMode) {
      clearOidcSession()
      return
    }
    void endOidcSession()
  }

  if (!localMode && authLoading) {
    return <div className="auth-shell"><div className="auth-card"><span className="brand-lettermark auth-brand">S</span><h1>Secure Document Vault</h1><p>Verifying your organization session…</p></div></div>
  }

  if (!localMode && (!getAccessToken() || authDenied)) {
    return <div className="auth-shell"><div className="auth-card"><span className="brand-lettermark auth-brand">S</span><h1>Secure Document Vault</h1><p>{authDenied ? 'Access denied' : 'Sign in with your organization identity to continue.'}</p>{authError && <div className="banner error">{authError}</div>}<button className="primary-action auth-signin" onClick={() => void beginOidcLogin(authDenied)}>{authDenied ? 'Sign in with a different account' : 'Sign in'}</button></div></div>
  }

  const navGroups: { title: string; items: NavItem[] }[] = [
    { title: 'Work', items: [{ view: 'search', label: 'Search', icon: 'search', show: canSearchRecords }] },
    { title: 'Administration', items: [{ view: 'admin', label: 'Administration', icon: 'admin', show: canViewAdministration }] },
    { title: 'Operations', items: [{ view: 'operations', label: 'Operations', icon: 'operations', show: canViewTechnicalFailures }] },
    { title: 'Support', items: [{ view: 'audit', label: 'Audit', icon: 'audit', show: canViewTechnicalFailures }] },
  ]

  const pageMeta = view === 'admin' && selectedPersona === 'businessAdmin'
    ? { eyebrow: 'Administration', title: 'Business Administration', description: 'Manage business policy, metadata, access design, and document governance for this SDV environment.' }
    : view === 'admin' && selectedPersona === 'tenantIt'
      ? { eyebrow: 'Administration', title: 'Tenant IT Administration', description: 'Manage customer-environment storage, authentication, integrations, and technical readiness.' }
      : view === 'operations' && selectedPersona === 'platform'
        ? { eyebrow: 'Operations', title: 'Platform Operations', description: "Monitor and manage this customer's SDV deployment, health, storage, incidents, and operational evidence." }
        : VIEW_META[view]
  const initials = (session?.subject || 'Local user').split(/[.@\s_-]+/).filter(Boolean).slice(0, 2).map((part) => part[0]?.toUpperCase()).join('') || 'LU'

  return (
    <div className={`modern-app-shell ${sidebarCollapsed ? 'sidebar-collapsed' : ''} ${view === 'search' ? 'bw-shell' : ''}`}>
      <header className="mobile-app-bar">
        <button className="shell-icon-button" type="button" onClick={() => setDrawerOpen(true)} aria-label="Open navigation"><Icon name="menu" /></button>
        <div className="mobile-brand"><span className="brand-lettermark">S</span><strong>Secure Document Vault</strong></div>
        <div className="mobile-user-avatar" title={session?.subject || 'Local user'}>{initials}</div>
      </header>

      {drawerOpen && <button className="nav-drawer-backdrop" aria-label="Close navigation" onClick={() => setDrawerOpen(false)} />}
      <aside className={`modern-sidebar ${drawerOpen ? 'drawer-open' : ''}`}>
        <div className="sidebar-brand-row">
          <div className="sidebar-brand" title="Secure Document Vault">
            <span className="brand-lettermark">S</span>
            <div className="sidebar-brand-copy">
              <strong>Secure Document Vault</strong>
              <span>{localMode ? 'Local / Demo' : 'Enterprise'}</span>
            </div>
          </div>
          <button className="sidebar-collapse-button" type="button" onClick={toggleSidebar} aria-label={sidebarCollapsed ? 'Expand navigation' : 'Collapse navigation'}>
            <Icon name={sidebarCollapsed ? 'chevronRight' : 'chevronLeft'} />
          </button>
        </div>

        <nav className="modern-sidebar-nav" aria-label="Primary navigation">
          {navGroups.map((group) => {
            const visibleItems = group.items.filter((item) => item.show)
            if (visibleItems.length === 0) return null
            return <div className="app-nav-group" key={group.title}>
              <div className="app-nav-group-title">{group.title}</div>
              {visibleItems.map((item) => (
                <button key={item.view} type="button" className={`app-nav-link ${view === item.view ? 'active' : ''}`} onClick={() => navigate(item.view)}>
                  <Icon name={item.icon} />
                  <span className="app-nav-label">{item.label}</span>
                </button>
              ))}
            </div>
          })}
        </nav>

        <div className="sidebar-footer">
          {localMode && (
            <label className="demo-role-control">
              <span>Demo role</span>
              <select value={selectedPersona} onChange={(event) => changeDemoRole(event.target.value as LocalPersona)}>
                {DEMO_ROLES.map((role) => <option key={role.value} value={role.value}>{role.label}</option>)}
              </select>
            </label>
          )}
          <div className="sidebar-identity">
            <div className="sidebar-avatar">{initials}</div>
            <div className="sidebar-identity-copy">
              <strong>{session?.subject || 'Local user'}</strong>
              <span>{localMode ? 'Local Demo Tenant' : 'Enterprise tenant'}</span>
            </div>
          </div>
          {!localMode && <button type="button" className="sidebar-signout" onClick={signOut}>Sign out</button>}
          {localMode && (
            <div className="sidebar-environment" title={system?.activeProfiles?.join(', ') || 'local'}>
              <span className={`environment-dot ${(system?.status || '').toUpperCase() === 'UP' ? 'up' : ''}`} />
              <span>{system?.status ?? 'Checking'} · {system?.version ?? 'development'}</span>
            </div>
          )}
        </div>
      </aside>

      <main className="modern-main-content">
        <div className="sdv-page">
          {view !== 'search' && <header className="ui-page-header">
            <div className="ui-page-header-copy">
              {view === 'audit' && <p className="ui-eyebrow">{pageMeta.eyebrow}</p>}
              <h1>{pageMeta.title}</h1>
              <p>{pageMeta.description}</p>
            </div>
            {view === 'operations' && canViewTechnicalFailures && (
              <div className="ui-page-header-actions">
                <button className="secondary" onClick={() => loadOperations()} disabled={loadingOperations}>{loadingOperations ? 'Refreshing…' : 'Refresh'}</button>
              </div>
            )}
          </header>}

      {capacityBanner?.visible && (
        <div className={`banner capacity-banner ${capacityBanner.severity.toLowerCase()}`}>
          <strong>Storage capacity {capacityBanner.severity.toLowerCase()}</strong>
          <span>{capacityBanner.message}</span>
        </div>
      )}

      {error && <div className="banner error">{error}</div>}
      {message && <div className="banner success">{message}</div>}

      {view === 'search' && (
        !session ? (
          <section className="panel top-gap"><span className="kicker">Secure Session</span><h2>Authentication required</h2><p className="muted">Business records are fail-closed until a trusted identity is available.</p></section>
        ) : !canSearchRecords ? (
          <section className="panel top-gap"><span className="kicker">Permission Boundary</span><h2>Business record search is not assigned</h2><p className="muted">Your current roles do not grant business-record search.</p></section>
        ) : (
          <BusinessWorkspace
            persona={selectedPersona}
            capabilities={session.capabilities}
            onViewerStateChange={handleBusinessViewerState}
          />
        )
      )}

      {view === 'admin' && canViewAdministration && (
        <div className={`admin-workspace ${selectedPersona === 'tenantIt' ? 'tenant-it-workspace' : 'business-admin-workspace'}`}>
          {selectedPersona === 'businessAdmin' ? (
            <>
              <nav className="workspace-tabs" aria-label="Business administration sections">
                <button className={businessAdminTab === 'access' ? 'active' : ''} onClick={() => setBusinessAdminTab('access')}>Users & Access</button>
                <button className={businessAdminTab === 'roles' ? 'active' : ''} onClick={() => setBusinessAdminTab('roles')}>Roles & Permissions</button>
                <button className={businessAdminTab === 'retention' ? 'active' : ''} onClick={() => setBusinessAdminTab('retention')}>Retention Policies</button>
                <button className={businessAdminTab === 'metadata' ? 'active' : ''} onClick={() => setBusinessAdminTab('metadata')}>Metadata Configuration</button>
              </nav>
              {businessAdminTab === 'access' && <section className="panel top-gap"><div className="panel-heading"><div><span className="kicker">Business Administration</span><h2>Users & access</h2></div></div><p className="muted">Sign-in and user-to-role assignment are managed in your identity provider. SDV reads each user&apos;s role from their sign-in token, so assignments are changed there, not here.</p><div className="admin-summary-grid"><div className="summary-tile"><strong>Business User</strong><small>Search and view authorized documents.</small><code className="role-code">BUSINESS_USER</code></div><div className="summary-tile"><strong>Document Steward</strong><small>Controlled download and permanent corrective redaction.</small><code className="role-code">DOCUMENT_STEWARD</code></div><div className="summary-tile"><strong>Business Administrator</strong><small>Business policy and metadata administration.</small><code className="role-code">BUSINESS_ADMIN</code></div></div></section>}
              {businessAdminTab === 'roles' && <section className="panel top-gap"><div className="panel-heading"><div><span className="kicker">Authorization</span><h2>Roles & permissions</h2><p className="muted">Business Administrators control document actions for business content roles. Search and View are baseline access and stay enabled.</p></div></div>{roleCapabilities ? <><div className="permission-matrix"><div className="permission-matrix-row permission-matrix-head"><strong>Permission</strong><strong>Business User</strong><strong>Document Steward</strong></div><div className="permission-matrix-row"><span>Search business records</span><span className="permission-locked">Locked on</span><span className="permission-locked">Locked on</span></div><div className="permission-matrix-row"><span>View documents</span><span className="permission-locked">Locked on</span><span className="permission-locked">Locked on</span></div>{roleCapabilities.configurableCapabilities.map((capability) => <div className="permission-matrix-row" key={capability}><span>{capabilityLabel(capability)}</span>{(['BUSINESS_USER','DOCUMENT_STEWARD'] as const).map((role) => <label className="permission-switch" key={`${role}-${capability}`}><input type="checkbox" role="switch" aria-label={`${capabilityLabel(capability)} for ${role === 'BUSINESS_USER' ? 'Business User' : 'Document Steward'}`} checked={Boolean(roleCapabilities.roles[role]?.[capability])} disabled={!canConfigureRolePermissions} onChange={(event) => setRoleCapabilities((current) => current ? { ...current, roles: { ...current.roles, [role]: { ...current.roles[role], [capability]: event.target.checked } } } : current)} /><span aria-hidden="true" /></label>)}</div>)}</div><div className="form-actions permission-save-actions"><button className="primary-action" onClick={saveRoleCapabilities} disabled={savingRoleCapabilities || !canConfigureRolePermissions}>{savingRoleCapabilities ? 'Saving…' : 'Save permissions'}</button></div></> : <p className="muted">Loading role permissions…</p>}</section>}
              {businessAdminTab === 'retention' && canConfigureRetention && (
                <section className="panel top-gap">
                  <div className="panel-heading">
                    <div><span className="kicker">Business Policy</span><h2>Retention policies</h2><p className="muted">Each activation creates an immutable policy version. Documents snapshot the retention days in force when they are ingested; older documents are never rewritten.</p></div>
                    <button type="button" className="primary-action admin-heading-action" onClick={() => { setRetentionNameInput(''); setRetentionDescriptionInput(''); setRetentionDaysInput(''); setAdminDrawerMode('retention-create') }}><Icon name="plus" /> Activate new policy</button>
                  </div>
                  {retentionPolicy && <div className="current-policy-card"><div><span className="label">Current policy</span><strong>{retentionPolicy.policyName}</strong><small>Version {retentionPolicy.version} · Effective {formatDate(retentionPolicy.effectiveFrom)}</small></div><span className="pill up">{retentionPolicy.indefinite ? 'Indefinite' : `${retentionPolicy.defaultRetentionDays} days`}</span></div>}
                  <div className="retention-history retention-history-full"><div className="section-heading-inline"><h3>Policy history</h3><span className="count">{retentionPolicy?.history?.length ?? 0}</span></div>{retentionPolicy?.history?.length ? retentionPolicy.history.map((policy) => <div className="retention-history-row" key={policy.policyVersionId}><div><strong>{policy.policyName}</strong><small>v{policy.version} · {policy.indefinite ? 'Indefinite' : `${policy.defaultRetentionDays} days`}</small>{policy.description && <small>{policy.description}</small>}<small>Created by {policy.createdBy}</small></div><div><span>{formatDate(policy.effectiveFrom)}</span><small>{policy.effectiveUntil ? `to ${formatDate(policy.effectiveUntil)}` : 'Current'}</small></div></div>) : <p className="muted">No policy history available.</p>}</div>
                </section>
              )}
              {businessAdminTab === 'metadata' && canConfigureMetadata && (
                <section className="panel top-gap">
                  <div className="panel-heading"><div><span className="kicker">Business Configuration</span><h2>Metadata configuration</h2><p className="muted">Tenant IT defines the fields supplied by each integration. Business Administration controls how those existing fields are labeled and searched in SDV; fields cannot be created or deleted here.</p></div><span className="count">{fields.length}</span></div>
                  <div className="source-context-inline"><label>Source context<select value={selectedSource} onChange={(event) => { setSelectedSource(event.target.value); setSelectedMetadataFieldKey(''); setAdminDrawerMode(null) }}>{sources.map((source) => <option key={source.sourceId} value={source.sourceId}>{source.displayName}</option>)}</select></label></div>
                  {!selectedAdminSource ? <div className="empty-state compact"><strong>Select a source</strong><span>Choose a source to configure its business search behavior.</span></div> : fields.length === 0 ? <div className="empty-state compact"><strong>No source fields defined</strong><span>Tenant IT must define the integration metadata contract first.</span></div> : <div className="admin-row-table metadata-row-table">
                    <div className="admin-row admin-row-head"><span>Field</span><span>Type</span><span>Search</span><span>Business ID</span><span>Action</span></div>
                    {fields.map((field) => <div className="admin-row" key={field.definitionId}><span className="admin-row-primary"><strong>{field.displayLabel}</strong><small>{field.fieldKey}{field.required ? ' · required by source' : ''}</small></span><span>{field.fieldType === 'STRING' ? 'Text' : humanize(field.fieldType)}</span><span>{field.searchable ? <span className="search-mode-chip">{humanize(field.searchMode)} match</span> : <span className="muted">Not searchable</span>}</span><span>{field.businessIdentifier ? <span className="status-pill status-pill-ok"><span className="status-pill-dot" aria-hidden="true" />Business ID</span> : <span className="muted">—</span>}</span><span className="admin-row-actions"><button type="button" className="row-icon-button" aria-label={`Edit ${field.displayLabel}`} title="Edit" onClick={() => { setSelectedMetadataFieldKey(field.fieldKey); setAdminDrawerMode('metadata-edit') }}><Icon name="edit" /></button></span></div>)}
                  </div>}
                </section>
              )}
              
            </>
          ) : (
            <>
              <nav className="workspace-tabs tenant-it-tabs" aria-label="Tenant IT administration sections">
                <button className={tenantItTab === 'storage' ? 'active' : ''} onClick={() => setTenantItTab('storage')}>Storage</button>
                <button className={tenantItTab === 'authentication' ? 'active' : ''} onClick={() => setTenantItTab('authentication')}>Authentication</button>
                <button className={tenantItTab === 'integrations' ? 'active' : ''} onClick={() => setTenantItTab('integrations')}>Integrations</button>
                <button className={tenantItTab === 'environment' ? 'active' : ''} onClick={() => setTenantItTab('environment')}>Environment</button>
              </nav>
              {tenantItTab === 'storage' && canConfigureStorage && (
                <section className="panel top-gap tenant-it-panel tenant-it-storage-panel">
                  <div className="panel-heading"><div><span className="kicker">Tenant IT</span><h2>Storage volumes</h2></div><div className="panel-heading-actions"><span className="count">{storageVolumes.length}</span><button type="button" className="info-button" aria-label="About storage volumes" aria-expanded={storageHelpOpen} onClick={() => setStorageHelpOpen((current) => !current)}>i</button><button type="button" className="primary-action admin-heading-action" onClick={() => setAdminDrawerMode('storage-create')}><Icon name="plus" /> Add storage volume</button></div></div>
                  {storageHelpOpen && <div className="context-help"><strong>How SDV storage works</strong><p>Storage volumes contain encrypted SDV document objects. Priority controls preferred placement. Warning % raises capacity attention; Stop-write % blocks new writes before the filesystem becomes critically full. Changing a path does not relocate documents already stored there.</p></div>}
                  {storageVolumes.length === 0 ? <div className="empty-state compact"><Icon name="storage" /><strong>No storage volume configured</strong><span>Add an ACTIVE volume before ingesting production documents.</span></div> : <div className="admin-row-table storage-row-table">
                    <div className="admin-row admin-row-head"><span>Volume</span><span>State</span><span>Priority</span><span>Capacity</span><span>Health</span><span>Action</span></div>
                    {storageVolumes.map((item) => <div className="admin-row" key={item.volume.volumeId}><span className="admin-row-primary"><strong>{item.volume.displayName}</strong><small className="mono">{item.volume.rootPath}</small></span><span>{item.volume.state}</span><span>{item.volume.priority}</span><span>{item.capacity ? `${item.capacity.usedPercent}% used` : 'Unavailable'}</span><span><span className={`pill ${item.observationError || item.stopWriteThresholdReached ? 'critical' : item.warningThresholdReached ? 'warning' : 'up'}`}>{item.observationError ? 'UNAVAILABLE' : item.stopWriteThresholdReached ? 'CRITICAL' : item.warningThresholdReached ? 'WARNING' : 'HEALTHY'}</span></span><span className="admin-row-actions"><button type="button" className="row-icon-button" aria-label={`Edit ${item.volume.displayName}`} title="Edit" onClick={() => { setSelectedStorageVolumeId(item.volume.volumeId); setAdminDrawerMode('storage-edit') }}><Icon name="edit" /></button></span></div>)}
                  </div>}
                </section>
              )}
              {tenantItTab === 'authentication' && canConfigureTenantAuth && <div className="drawer-section-stack top-gap tenant-it-auth-stack">
                <section className="panel">
                  <div className="panel-heading"><div><span className="kicker">Tenant IT</span><h2>Enterprise authentication</h2><p className="muted">Stage, validate, activate, and roll back the trusted OIDC binding for this tenant. Tokens, private keys, and client secrets are never returned here.</p></div><span className="pill">{identityOverview?.active?.status ?? 'Unavailable'}</span></div>
                  {identityOverview?.active ? <div className="auth-config-grid"><div><span className="label">Provider</span><strong>{identityOverview.active.displayName}</strong></div><div><span className="label">Type</span><strong>{identityOverview.active.providerType}</strong></div><div><span className="label">Issuer</span><strong>{identityOverview.active.issuerUri}</strong></div><div><span className="label">Browser client</span><strong>{identityOverview.active.browserClientId ?? 'Not configured'}</strong></div><div><span className="label">API audience</span><strong>{identityOverview.active.apiAudience}</strong></div><div><span className="label">Version</span><strong>{identityOverview.active.configurationVersion}</strong></div></div> : <p className="muted">No ACTIVE enterprise identity connection is available. Automatic bootstrap will not overwrite existing administered identity records.</p>}
                </section>
                <section className="panel">
                  <div className="panel-heading"><div><span className="kicker">Staged configuration</span><h2>OIDC draft</h2><p className="muted">Activation is allowed only for the exact VALIDATED version with a successful connection test from the last 15 minutes. The previous ACTIVE binding is retained for rollback.</p></div>{identityOverview?.staged && <span className={`pill ${identityOverview.staged.lastTestStatus === 'PASSED' ? 'healthy' : ''}`}>{identityOverview.staged.status}</span>}</div>
                  <form className="form-stack" onSubmit={saveIdentityDraft}><label>Provider ID<input value={identityDraft.providerId} onChange={(event) => setIdentityDraft({...identityDraft, providerId:event.target.value})} required /></label><label>Display name<input value={identityDraft.displayName} onChange={(event) => setIdentityDraft({...identityDraft, displayName:event.target.value})} required /></label><label>Issuer URI<input value={identityDraft.issuerUri} onChange={(event) => setIdentityDraft({...identityDraft, issuerUri:event.target.value})} placeholder="https://login.example.com/tenant/v2.0" required /></label><label>Browser client ID<input value={identityDraft.browserClientId} onChange={(event) => setIdentityDraft({...identityDraft, browserClientId:event.target.value})} required /></label><label>API audience<input value={identityDraft.apiAudience} onChange={(event) => setIdentityDraft({...identityDraft, apiAudience:event.target.value})} required /></label><div className="panel-heading-actions identity-action-row"><button type="submit" className="secondary identity-save-action" disabled={savingIdentity || activatingIdentity}>{savingIdentity ? 'Saving…' : 'Save draft'}</button><button type="button" className="secondary identity-test-action" disabled={testingIdentity || activatingIdentity || !identityOverview?.staged} onClick={() => void testIdentityDraft()}>{testingIdentity ? 'Testing…' : 'Test connection'}</button><button type="button" className="primary-action identity-activate-action" disabled={activatingIdentity || testingIdentity || savingIdentity || identityOverview?.staged?.status !== 'VALIDATED' || identityOverview?.staged?.lastTestStatus !== 'PASSED'} onClick={() => void activateIdentityDraft()}>{activatingIdentity ? 'Activating…' : 'Activate validated version'}</button></div></form>
                  {identityOverview?.staged?.lastTestStatus && <div className={`banner ${identityOverview.staged.lastTestStatus === 'PASSED' ? 'notice' : 'error'}`}><strong>Last test: {identityOverview.staged.lastTestStatus}</strong><span>{identityOverview.staged.lastTestMessage}</span></div>}
                </section>
                <section className="panel">
                  <div className="panel-heading"><div><span className="kicker">Recovery</span><h2>Known-good rollback</h2><p className="muted">Only tenant-scoped configurations that were previously ACTIVE are eligible. Rollback atomically replaces the current ACTIVE binding.</p></div><span className="pill">{identityOverview?.rollbackCandidates?.length ?? 0} available</span></div>
                  {identityOverview?.rollbackCandidates?.length ? <div className="admin-list">{identityOverview.rollbackCandidates.map((candidate) => <div className="admin-row" key={candidate.connectionId}><span><strong>{candidate.displayName}</strong><small>{candidate.issuerUri}</small></span><span>Version {candidate.configurationVersion}</span><span>{candidate.status}</span><button type="button" className="secondary identity-rollback-action" disabled={Boolean(rollingBackIdentity) || activatingIdentity} onClick={() => void rollbackIdentity(candidate)}>{rollingBackIdentity === candidate.connectionId ? 'Rolling back…' : 'Rollback'}</button></div>)}</div> : <div className="empty-state compact"><strong>No rollback target yet</strong><span>The previously ACTIVE configuration will appear here after the first activation.</span></div>}
                </section>
              </div>}
              {tenantItTab === 'integrations' && canConfigureSources && (
                <section className="panel top-gap tenant-it-panel tenant-it-integrations-panel">
                  <div className="panel-heading"><div><span className="kicker">Tenant IT</span><h2>Ingestion integrations</h2><p className="muted">Define the producer identity, authentication reference, and technical metadata contract. Business Administration separately decides how those fields are exposed for search.</p></div><div className="panel-heading-actions"><span className="count">{sources.length}</span><button type="button" className="primary-action admin-heading-action" onClick={() => setAdminDrawerMode('integration-create')}><Icon name="plus" /> Add source</button></div></div>
                  {sources.length === 0 ? <div className="empty-state compact"><strong>No integrations configured</strong><span>Add a producer source to define its technical contract.</span></div> : <div className="admin-row-table integration-row-table">
                    <div className="admin-row admin-row-head"><span>Integration</span><span>Source key</span><span>Status</span><span>Authentication</span><span>Action</span></div>
                    {sources.map((source) => <div className="admin-row" key={source.sourceId}><span className="admin-row-primary"><strong>{source.displayName}</strong></span><span className="mono">{source.sourceKey}</span><span><span className={`pill ${source.status === 'ACTIVE' ? 'up' : ''}`}>{source.status}</span></span><span>{source.authType ?? '—'}</span><span className="admin-row-actions"><button type="button" className="row-icon-button" aria-label={`Edit ${source.displayName}`} title="Edit" onClick={() => { setSelectedSource(source.sourceId); setAdminDrawerMode('integration-edit') }}><Icon name="edit" /></button></span></div>)}
                  </div>}
                </section>
              )}
              {tenantItTab === 'environment' && <section className="panel top-gap tenant-it-panel tenant-it-environment-panel"><div className="panel-heading"><div><span className="kicker">Customer Environment</span><h2>Environment</h2></div><button className="secondary" onClick={() => navigate('operations')}>Open operational readiness</button></div><div className="auth-config-grid"><div><span className="label">Product</span><strong>{system?.productName ?? 'Secure Document Vault'}</strong></div><div><span className="label">Version</span><strong>{system?.version ?? 'Unknown'}</strong></div><div><span className="label">Status</span><strong>{system?.status ?? 'Unknown'}</strong></div><div><span className="label">Profiles</span><strong>{system?.activeProfiles?.join(', ') || 'Not exposed'}</strong></div></div></section>}
            </>
          )}
        </div>
      )}

      {view === 'operations' && canViewTechnicalFailures && (
        <div className="admin-workspace">
          <nav className="workspace-tabs" aria-label="Platform operations sections">
            <button className={platformTab === 'overview' ? 'active' : ''} onClick={() => setPlatformTab('overview')}>Overview</button>
            <button className={platformTab === 'storage' ? 'active' : ''} onClick={() => setPlatformTab('storage')}>Storage</button>
            <button className={platformTab === 'health' ? 'active' : ''} onClick={() => setPlatformTab('health')}>Platform Health</button>
            <button className={platformTab === 'incidents' ? 'active' : ''} onClick={() => setPlatformTab('incidents')}>Incidents</button>
            <button className={platformTab === 'audit' ? 'active' : ''} onClick={() => setPlatformTab('audit')}>Audit</button>
            <button className={platformTab === 'configuration' ? 'active' : ''} onClick={() => setPlatformTab('configuration')}>Configuration</button>
          </nav>
          {platformTab === 'overview' && <div className="operations-layout top-gap"><section className="panel operations-wide"><div className="panel-heading"><div><span className="kicker">Customer Deployment</span><h2>Operational overview</h2></div></div>{!operations ? <p className="muted">Loading operational health…</p> : <><div className="health-grid">{[operations.application, operations.database, operations.keyService].map((item) => <div className="health-card" key={item.component}><span className="label">{humanize(item.component)}</span><StatusPill value={item.status} /><small>{item.message}</small></div>)}<div className={`health-card health-card-overall health-card-${statusTone(operations.overallStatus)}`}><span className="label">Overall</span><StatusPill value={operations.overallStatus} /><small>Observed {formatDate(operations.observedAt)}</small></div><div className="health-card"><span className="label">Retention sweep</span>{retentionSweepStatus ? <StatusPill value={retentionSweepStatus.outcome} /> : <strong>Loading</strong>}<small>{retentionSweepStatus?.completedAt ? `${retentionSweepStatus.deleted} deleted · ${retentionSweepStatus.failed} failed · ${formatDate(retentionSweepStatus.completedAt)}` : 'No sweep evidence yet'}</small></div></div></>}</section><section className="panel"><div className="panel-heading"><div><span className="kicker">Storage</span><h2>Volume readiness</h2></div><span className="count">{operations?.storageVolumes.filter(volumeNeedsAttention).length ?? 0}</span></div>{operations && operations.storageVolumes.filter(volumeNeedsAttention).length === 0 ? <p className="muted">All active storage volumes are healthy.</p> : <p className="muted">{operations?.storageVolumes.filter(volumeNeedsAttention).length ?? 0} volume(s) require attention.</p>}</section><section className="panel"><div className="panel-heading"><div><span className="kicker">Incidents</span><h2>Processing failures</h2></div><span className="count">{operations ? (operations.processingFailureCount ?? '—') : '—'}</span></div><p className="muted">Open Incidents for failure, stuck-transition, and incomplete-submission detail.</p></section></div>}
          {platformTab === 'storage' && <section className="panel top-gap"><div className="panel-heading"><div><span className="kicker">Platform Operations</span><h2>Processing storage</h2></div><span className="count">{operations?.storageVolumes.length ?? 0}</span></div><div className="ops-table-wrap"><table className="ops-table"><thead><tr><th>Volume</th><th>State</th><th>Health</th><th>Used</th><th>Warn / stop writes</th><th>Change state</th></tr></thead><tbody>{operations?.storageVolumes.map((volume) => <tr key={volume.volumeId}><td><strong>{volume.displayName}</strong><small className="mono">{volume.rootPath}</small></td><td><StatusPill value={volume.state} /></td><td><StatusPill value={volume.health} /></td><td>{volume.usedPercent == null ? 'Unavailable' : `${volume.usedPercent}%`}</td><td>{volume.warningPercent}% / {volume.stopWritePercent}%</td><td>{canConfigureStorage ? <select className="ops-state-select" aria-label={`Change state for ${volume.displayName}`} value={volume.state} onChange={(event) => updateVolumeState(volume, event.target.value as StorageVolumeStatus['state'])}><option value="ACTIVE">Active</option><option value="READ_ONLY">Read only</option><option value="OFFLINE">Offline</option></select> : <span className="muted">No permission</span>}</td></tr>)}</tbody></table></div></section>}
          {platformTab === 'health' && <section className="panel top-gap"><div className="panel-heading"><div><span className="kicker">Platform Health</span><h2>Core services</h2></div></div>{!operations ? <p className="muted">Loading health…</p> : <div className="health-grid">{[operations.application, operations.database, operations.keyService].map((item) => <div className="health-card" key={item.component}><span className="label">{humanize(item.component)}</span><StatusPill value={item.status} /><small>{item.message}</small></div>)}</div>}</section>}
          {platformTab === 'incidents' && <div className="operations-layout top-gap"><section className="panel"><div className="panel-heading"><div><span className="kicker">Processing</span><h2>Failures</h2></div><span className="count">{operations ? (operations.processingFailureCount ?? 'Unavailable') : '—'}</span></div>{operations?.processingFailures.length ? operations.processingFailures.map((item) => <div className="ops-item" key={item.documentId}><span className="ops-item-title"><strong>{humanize(item.errorCode || 'PROCESSING_FAILED')}</strong><code className="ops-code">{item.errorCode || 'PROCESSING_FAILED'}</code></span><small className="mono">Document {item.documentId}</small><small>{item.processingAttemptCount} attempt(s) · {formatDate(item.ingestedAt)}</small></div>) : <p className="muted">No processing failures.</p>}</section><section className="panel"><div className="panel-heading"><div><span className="kicker">Workflow</span><h2>Stuck transitions</h2></div><span className="count">{operations ? (operations.stuckDocumentCount ?? 'Unavailable') : '—'}</span></div>{operations?.stuckDocuments.length ? operations.stuckDocuments.map((item) => <div className="ops-item" key={item.documentId}><span className="ops-item-title"><strong>Stuck in {humanize(item.status).toLowerCase()}</strong><StatusPill value={item.status} /></span><small className="mono">Document {item.documentId}</small><small>Since {formatDate(item.stateSince)}</small></div>) : <p className="muted">No stuck transitions.</p>}</section><section className="panel operations-wide"><div className="panel-heading"><div><span className="kicker">Submissions</span><h2>Incomplete / attention</h2></div><span className="count">{operations ? (operations.attentionSubmissionCount ?? 'Unavailable') : '—'}</span></div>{operations?.attentionSubmissions.length ? operations.attentionSubmissions.map((item) => <div className="ops-item" key={item.submissionId}><span className="ops-item-title"><strong>{item.receivedDocumentCount} of {item.expectedDocumentCount} documents received</strong><StatusPill value={item.status} /></span><small>{item.sourceSubmissionId}</small><small>Last activity {formatDate(item.lastActivityAt)}</small></div>) : <p className="muted">No incomplete submissions need attention.</p>}</section></div>}
          {platformTab === 'audit' && <section className="panel top-gap audit-panel"><div className="panel-heading"><div><span className="kicker">Platform Operations</span><h2>Audit</h2></div><span className="count">{auditEvents.length}</span></div><form className="audit-filters audit-filters-grid" onSubmit={(event) => { event.preventDefault(); loadAudit(true).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : 'Audit query failed.')) }}><label>Action<input value={auditAction} onChange={(event) => setAuditAction(event.target.value)} placeholder="e.g. DOCUMENT_DOWNLOADED" /></label><label>Outcome<select value={auditOutcome} onChange={(event) => setAuditOutcome(event.target.value)}><option value="">All outcomes</option><option>SUCCESS</option><option>DENIED</option><option>FAILED</option></select></label><label>Resource type<input value={auditTargetType} onChange={(event) => setAuditTargetType(event.target.value)} placeholder="e.g. DOCUMENT" /></label><label>Resource ID<input value={auditTargetId} onChange={(event) => setAuditTargetId(event.target.value)} placeholder="Exact resource ID" /></label><label>Actor type<input value={auditActorType} onChange={(event) => setAuditActorType(event.target.value)} placeholder="e.g. USER" /></label><label>Actor ID<input value={auditActorId} onChange={(event) => setAuditActorId(event.target.value)} placeholder="Exact actor ID" /></label><label>Correlation ID<input value={auditCorrelationId} onChange={(event) => setAuditCorrelationId(event.target.value)} placeholder="Exact correlation ID" /></label><label>From<input type="datetime-local" value={auditFrom} onChange={(event) => setAuditFrom(event.target.value)} /></label><label>To<input type="datetime-local" value={auditTo} onChange={(event) => setAuditTo(event.target.value)} /></label><label className="audit-toggle"><input type="checkbox" checked={includeAuditViews} onChange={(event) => setIncludeAuditViews(event.target.checked)} /> Include view events</label><button type="submit" disabled={loadingAudit}>{loadingAudit ? 'Loading…' : 'Apply filters'}</button></form><div className="ops-table-wrap"><table className="ops-table audit-table"><thead><tr><th>Time</th><th>Action</th><th>Outcome</th><th>Actor</th><th>Resource</th><th>Correlation ID</th><th>Safe details</th></tr></thead><tbody>{auditEvents.filter((event) => includeAuditViews || event.action !== 'AUDIT_VIEWED').map((event) => <tr key={event.auditEventId}><td>{formatDate(event.occurredAt)}</td><td><strong>{event.action}</strong></td><td>{event.outcome}</td><td>{event.actorType}<small>{event.actorId}</small></td><td>{event.targetType}<small className="mono">{event.targetId}</small></td><td><small className="mono">{event.correlationId || '—'}</small></td><td><code>{event.safeDetailsJson}</code></td></tr>)}</tbody></table></div>{auditHasMore && <button className="secondary" onClick={() => loadAudit(false)} disabled={loadingAudit}>{loadingAudit ? 'Loading…' : 'Load older events'}</button>}</section>}
          {platformTab === 'configuration' && <section className="panel top-gap"><div className="panel-heading"><div><span className="kicker">Customer Deployment</span><h2>Configuration</h2></div></div><p className="muted">Settings here apply only to this customer&apos;s SDV environment. Licensing and branding are not editable yet.</p></section>}
        </div>
      )}

      {view === 'audit' && canViewTechnicalFailures && (
        <section className="panel top-gap audit-panel">
          <div className="panel-heading"><div><span className="kicker">Audit</span><h2>Tenant audit history</h2></div><span className="count">{auditEvents.length}</span></div>
          <p className="muted">Append-oriented tenant audit. Safe details contain technical evidence only; document content and removed sensitive text are never part of this view.</p>
          <form className="audit-filters audit-filters-grid" onSubmit={(event) => { event.preventDefault(); loadAudit(true).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : 'Audit query failed.')) }}>
            <label>Action<input value={auditAction} onChange={(event) => setAuditAction(event.target.value)} placeholder="e.g. DOCUMENT_DOWNLOADED" /></label>
            <label>Outcome<select value={auditOutcome} onChange={(event) => setAuditOutcome(event.target.value)}><option value="">All outcomes</option><option>SUCCESS</option><option>DENIED</option><option>FAILED</option></select></label>
            <label>Resource type<input value={auditTargetType} onChange={(event) => setAuditTargetType(event.target.value)} placeholder="e.g. DOCUMENT" /></label>
            <label>Resource ID<input value={auditTargetId} onChange={(event) => setAuditTargetId(event.target.value)} placeholder="Exact resource ID" /></label>
            <label>Actor type<input value={auditActorType} onChange={(event) => setAuditActorType(event.target.value)} placeholder="e.g. USER" /></label>
            <label>Actor ID<input value={auditActorId} onChange={(event) => setAuditActorId(event.target.value)} placeholder="Exact actor ID" /></label>
            <label>Correlation ID<input value={auditCorrelationId} onChange={(event) => setAuditCorrelationId(event.target.value)} placeholder="Exact correlation ID" /></label>
            <label>From<input type="datetime-local" value={auditFrom} onChange={(event) => setAuditFrom(event.target.value)} /></label>
            <label>To<input type="datetime-local" value={auditTo} onChange={(event) => setAuditTo(event.target.value)} /></label>
            <label className="audit-toggle"><input type="checkbox" checked={includeAuditViews} onChange={(event) => setIncludeAuditViews(event.target.checked)} /> Include view events</label>
            <button type="submit" disabled={loadingAudit}>{loadingAudit ? 'Loading…' : 'Apply filters'}</button>
          </form>
          <div className="ops-table-wrap"><table className="ops-table audit-table"><thead><tr><th>Time</th><th>Action</th><th>Outcome</th><th>Actor</th><th>Resource</th><th>Correlation ID</th><th>Safe details</th></tr></thead><tbody>
            {auditEvents.filter((event) => includeAuditViews || event.action !== 'AUDIT_VIEWED').map((event) => <tr key={event.auditEventId}><td>{formatDate(event.occurredAt)}</td><td><strong>{event.action}</strong></td><td>{event.outcome}</td><td>{event.actorType}<small>{event.actorId}</small></td><td>{event.targetType}<small className="mono">{event.targetId}</small></td><td><small className="mono">{event.correlationId || '—'}</small></td><td><code>{event.safeDetailsJson}</code></td></tr>)}
          </tbody></table></div>
          {auditHasMore && <button className="secondary" onClick={() => loadAudit(false)} disabled={loadingAudit}>{loadingAudit ? 'Loading…' : 'Load older events'}</button>}
        </section>
      )}

      {adminDrawerMode && (
        <>
          <button type="button" className="admin-drawer-backdrop" aria-label="Close administration editor" onClick={() => setAdminDrawerMode(null)} />
          <aside className="admin-drawer" role="dialog" aria-modal="true" aria-label="Administration editor">
            <div className="admin-drawer-header">
              <div>
                <span className="kicker">Administration</span>
                <h2>{adminDrawerMode === 'retention-create' ? 'Activate retention policy' : adminDrawerMode === 'metadata-edit' ? 'Edit metadata behavior' : adminDrawerMode === 'storage-create' ? 'Add storage volume' : adminDrawerMode === 'storage-edit' ? 'Edit storage volume' : adminDrawerMode === 'integration-create' ? 'Add integration' : adminDrawerMode === 'integration-edit' ? 'Edit integration' : adminDrawerMode === 'source-field-create' ? 'Add source field' : 'Edit source field'}</h2>
              </div>
              <button type="button" className="drawer-close-button" aria-label="Close" onClick={() => setAdminDrawerMode(null)}><Icon name="close" /></button>
            </div>
            <div className="admin-drawer-body">
              {adminDrawerMode === 'retention-create' && <form onSubmit={saveRetentionPolicy} className="form-stack"><label>Policy name<input value={retentionNameInput} onChange={(event) => setRetentionNameInput(event.target.value)} placeholder="Standard 1 Year" maxLength={200} required /></label><label>Description<textarea value={retentionDescriptionInput} onChange={(event) => setRetentionDescriptionInput(event.target.value)} placeholder="Optional reason or business purpose" maxLength={1000} /></label><label>Retention days<input inputMode="numeric" value={retentionDaysInput} onChange={(event) => setRetentionDaysInput(event.target.value)} placeholder="Leave blank for indefinite" /></label><div className="context-help compact-help"><strong>Applies to future ingestion only</strong><p>Activation starts when you save. Existing documents keep the retention snapshot captured when they were ingested.</p></div><button type="submit" className="primary-action">Activate retention policy</button></form>}

              {adminDrawerMode === 'metadata-edit' && (selectedMetadataField ? <form key={selectedMetadataField.definitionId} onSubmit={saveField} className="form-stack"><div className="readonly-field"><span>Source field</span><strong>{selectedMetadataField.fieldKey}</strong><small>{selectedMetadataField.fieldType} · {selectedMetadataField.required ? 'Required by source contract' : 'Optional in source contract'} · identity managed by Tenant IT</small></div><label>Display label<input name="displayLabel" defaultValue={selectedMetadataField.displayLabel} required /></label><label>Normalization<select name="normalization" defaultValue={selectedMetadataField.normalization}><option>NONE</option><option>TRIM</option><option>UPPERCASE</option><option>LOWERCASE</option><option>DIGITS_ONLY</option></select></label><label>Search mode<select name="searchMode" defaultValue={selectedMetadataField.searchMode}><option>EXACT</option><option>PREFIX</option><option>PARTIAL</option></select></label><label className="check"><input type="checkbox" name="searchable" defaultChecked={selectedMetadataField.searchable} /> Searchable</label><label className="check"><input type="checkbox" name="businessIdentifier" defaultChecked={selectedMetadataField.businessIdentifier} /> Business identifier</label><button type="submit" className="primary-action">Save metadata behavior</button></form> : <p className="muted">Loading field…</p>)}

              {adminDrawerMode === 'storage-create' && <form onSubmit={createStorageVolume} className="form-stack"><label>Display name<input name="displayName" placeholder="Primary Document Storage" required /></label><label>Root path<input name="rootPath" placeholder="C:\sdv-data\documents" required /></label><label>State<select name="state" defaultValue="ACTIVE"><option>ACTIVE</option><option>READ_ONLY</option><option>OFFLINE</option></select></label><label>Priority<input name="priority" type="number" min="0" defaultValue="1" required /></label><label>Warning %<input name="warningPercent" type="number" min="1" max="99" defaultValue="90" required /></label><label>Stop-write %<input name="stopWritePercent" type="number" min="2" max="100" defaultValue="95" required /></label><div className="context-help compact-help"><strong>Path behavior</strong><p>Changing storage configuration does not relocate documents already written to another path.</p></div><button type="submit" className="primary-action">Add storage volume</button></form>}

              {adminDrawerMode === 'storage-edit' && (selectedStorageVolume ? <form key={selectedStorageVolume.volume.updatedAt} onSubmit={saveStorageVolume} className="form-stack"><label>Display name<input name="displayName" defaultValue={selectedStorageVolume.volume.displayName} required /></label><div className="readonly-field"><span>Root path</span><strong className="mono">{selectedStorageVolume.volume.rootPath}</strong><small>The existing volume path is not changed from this editor.</small></div><label>State<select name="state" defaultValue={selectedStorageVolume.volume.state}><option>ACTIVE</option><option>READ_ONLY</option><option>OFFLINE</option></select></label><label>Priority<input name="priority" type="number" min="0" defaultValue={selectedStorageVolume.volume.priority} required /></label><label>Warning %<input name="warningPercent" type="number" min="1" max="99" defaultValue={selectedStorageVolume.volume.warningPercent} required /></label><label>Stop-write %<input name="stopWritePercent" type="number" min="2" max="100" defaultValue={selectedStorageVolume.volume.stopWritePercent} required /></label><button type="submit" className="primary-action">Save storage volume</button></form> : <p className="muted">Loading storage volume…</p>)}

              {adminDrawerMode === 'integration-create' && <form onSubmit={createSource} className="form-stack"><label>Source key<input name="sourceKey" placeholder="producer-system" required /></label><p className="field-help">The source key becomes the immutable technical identity after creation.</p><label>Display name<input name="displayName" placeholder="Producer System" required /></label><label>Authentication<select name="authType" defaultValue="OAUTH2_CLIENT_CREDENTIALS"><option>OAUTH2_CLIENT_CREDENTIALS</option><option>JWT_BEARER</option><option>MTLS</option></select></label><label>Credential reference<input name="credentialReference" placeholder="vault://sdv/source/producer-system" required /></label><p className="field-help">Reference only. Secret values are never displayed by SDV.</p><button type="submit" className="primary-action">Add source</button></form>}

              {adminDrawerMode === 'integration-edit' && (sourceDetail && sourceDetail.sourceId === selectedSource ? <div className="drawer-section-stack"><form key={sourceDetail.updatedAt} onSubmit={saveSourceIntegration} className="form-stack"><div className="readonly-field"><span>Source key</span><strong>{sourceDetail.sourceKey}</strong><small>Immutable source identity</small></div><label>Display name<input name="displayName" defaultValue={sourceDetail.displayName} required /></label><label>Status<select name="status" defaultValue={sourceDetail.status}><option>ACTIVE</option><option>DISABLED</option></select></label><label>Authentication<select name="authType" defaultValue={sourceDetail.authType}><option>OAUTH2_CLIENT_CREDENTIALS</option><option>JWT_BEARER</option><option>MTLS</option></select></label><label>Credential reference<input name="credentialReference" defaultValue={sourceDetail.credentialReference} required /><small className="field-help">Reference only. SDV never displays the secret value.</small></label><button type="submit" className="primary-action">Save integration</button></form><div className="drawer-subsection"><h3>Operational summary</h3><div className="integration-ops-grid"><div><span>Created</span><strong>{formatDate(sourceDetail.createdAt)}</strong></div><div><span>Updated</span><strong>{formatDate(sourceDetail.updatedAt)}</strong></div><div><span>Submissions</span><strong>{sourceOperations?.submissionCount ?? '—'}</strong></div><div><span>Documents</span><strong>{sourceOperations?.documentCount ?? '—'}</strong></div><div><span>Last activity</span><strong>{formatDate(sourceOperations?.lastActivityAt)}</strong></div><div><span>Last available document</span><strong>{formatDate(sourceOperations?.lastDocumentAvailableAt)}</strong></div></div></div><div className="drawer-subsection"><div className="section-heading-inline"><div><h3>Source metadata contract</h3><p className="muted">Field keys originate here. Business Administration only controls how existing fields are used.</p></div><button type="button" className="secondary compact-action" onClick={() => { setSelectedSourceFieldKey(''); setAdminDrawerMode('source-field-create') }}><Icon name="plus" /> Add field</button></div><div className="source-contract-list">{sourceFields.length ? sourceFields.map((field) => <div className="source-contract-row source-contract-row-editable" key={field.definitionId}><span><strong>{field.displayLabel}</strong><small>{field.fieldKey}</small></span><span>{field.fieldType}</span><span>{field.required ? 'Required' : 'Optional'}</span><button type="button" className="row-icon-button" aria-label={`Edit ${field.displayLabel}`} title="Edit" onClick={() => { setSelectedSourceFieldKey(field.fieldKey); setAdminDrawerMode('source-field-edit') }}><Icon name="edit" /></button></div>) : <div className="empty-state compact"><strong>No source fields defined</strong><span>Add the producer fields this integration is allowed to send.</span></div>}</div></div></div> : <p className="muted">Loading integration…</p>)}

              {adminDrawerMode === 'source-field-create' && <form onSubmit={saveSourceField} className="form-stack"><label>Field key<input name="fieldKey" placeholder="contractNumber" required /></label><p className="field-help">The field key is the producer-facing identity. Treat it as immutable once integrations depend on it.</p><label>Display label<input name="displayLabel" placeholder="Contract Number" required /></label><label>Type<select name="fieldType" defaultValue="TEXT"><option>TEXT</option><option>INTEGER</option><option>DECIMAL</option><option>DATE</option><option>DATETIME</option><option>BOOLEAN</option><option>EMAIL</option><option>UUID</option></select></label><label>Normalization<select name="normalization" defaultValue="TRIM"><option>NONE</option><option>TRIM</option><option>UPPERCASE</option><option>LOWERCASE</option><option>DIGITS_ONLY</option></select></label><label className="check"><input type="checkbox" name="required" /> Required by source contract</label><button type="submit" className="primary-action">Add source field</button></form>}

              {adminDrawerMode === 'source-field-edit' && (selectedSourceField ? <form key={selectedSourceField.definitionId} onSubmit={saveSourceField} className="form-stack"><input type="hidden" name="fieldKey" value={selectedSourceField.fieldKey} /><div className="readonly-field"><span>Field key</span><strong>{selectedSourceField.fieldKey}</strong><small>Immutable producer-facing identity</small></div><label>Display label<input name="displayLabel" defaultValue={selectedSourceField.displayLabel} required /></label><label>Type<select name="fieldType" defaultValue={selectedSourceField.fieldType}><option>TEXT</option><option>INTEGER</option><option>DECIMAL</option><option>DATE</option><option>DATETIME</option><option>BOOLEAN</option><option>EMAIL</option><option>UUID</option></select></label><label>Normalization<select name="normalization" defaultValue={selectedSourceField.normalization}><option>NONE</option><option>TRIM</option><option>UPPERCASE</option><option>LOWERCASE</option><option>DIGITS_ONLY</option></select></label><label className="check"><input type="checkbox" name="required" defaultChecked={selectedSourceField.required} /> Required by source contract</label><button type="submit" className="primary-action">Save source field</button></form> : <p className="muted">Loading source field…</p>)}
            </div>
          </aside>
        </>
      )}



        </div>
      </main>
    </div>
  )
}

export default App
