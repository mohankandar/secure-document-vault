import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react'
import { PdfViewer } from './PdfViewer'
import BusinessWorkspace from './BusinessWorkspace'
import Icon, { type IconName } from './Icon'
import { AdminSection, AdminTabs, AttentionRow, AttentionTile, CapacityMeter, EmptyState, StateSheet, StatusPill, humanize, statusTone, type StatusTone } from './AdminKit'
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

const BUSINESS_ROLE_DIRECTORY = [
  { name: 'Business User', code: 'BUSINESS_USER', responsibility: 'Search and view authorized documents.', configurable: true },
  { name: 'Document Steward', code: 'DOCUMENT_STEWARD', responsibility: 'Controlled download and permanent corrective redaction.', configurable: true },
  { name: 'Business Administrator', code: 'BUSINESS_ADMIN', responsibility: 'Business policy and metadata administration.', configurable: false },
]

const AUTH_TYPE_LABELS: Record<string, string> = {
  OAUTH2_CLIENT_CREDENTIALS: 'OAuth 2.0 client credentials',
  JWT_BEARER: 'JWT bearer',
  MTLS: 'Mutual TLS',
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
  const [auditMoreFilters, setAuditMoreFilters] = useState(false)
  const [selectedAuditEvent, setSelectedAuditEvent] = useState<AuditEventView | null>(null)

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
    // Ignore responses from a superseded persona/session request. Otherwise an older
    // principal can overwrite capabilities after the demo role has changed.
    let cancelled = false
    if (!localMode && (authLoading || !getAccessToken())) {
      setSession(null)
      return () => { cancelled = true }
    }
    setSession(null)
    setError('')
    setMessage('')
    setViewerDocument(null)
    setRecord(null)
    setResults([])
    roleApi<SessionInfo>('/api/v1/session', selectedPersona)
      .then(async (current) => {
        if (cancelled) return
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
      .catch((reason: unknown) => { if (cancelled) return; setSession(null); if (!localMode && reason instanceof ApiRequestError) { if (reason.status === 403) { setAuthDenied(true); setAuthError('You are signed in, but this account does not have access to Secure Document Vault. Contact your administrator if you believe you should have access.'); } else if (reason.status === 401) { clearOidcSession(); setAuthDenied(false); setAuthError('Your organization session has expired or could not be verified. Please sign in again.'); } } })
    return () => { cancelled = true }
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

  const attentionVolumes = operations?.storageVolumes.filter(volumeNeedsAttention) ?? []
  const incidentTotal = (operations?.processingFailureCount ?? 0) + (operations?.stuckDocumentCount ?? 0) + (operations?.attentionSubmissionCount ?? 0)
  const countTone = (value: number | null | undefined, positive: StatusTone): StatusTone => value == null ? 'neutral' : value > 0 ? positive : 'ok'
  const overviewSignals: { key: string; tone: StatusTone; count: number | string; title: string; detail: string; onOpen: () => void; openLabel: string }[] = operations ? [
    {
      key: 'failures',
      tone: countTone(operations.processingFailureCount, 'danger'),
      count: operations.processingFailureCount ?? '—',
      title: 'Processing failures',
      detail: operations.processingFailureCount == null ? 'Count unavailable' : operations.processingFailureCount > 0 ? 'Documents that failed processing' : 'No processing failures',
      onOpen: () => setPlatformTab('incidents'),
      openLabel: 'View incidents',
    },
    {
      key: 'storage',
      tone: attentionVolumes.length > 0 ? 'warn' : 'ok',
      count: attentionVolumes.length,
      title: 'Storage volumes',
      detail: attentionVolumes.length > 0 ? attentionVolumes.map((volume) => volume.displayName).join(', ') : 'All active storage volumes are healthy',
      onOpen: () => setPlatformTab('storage'),
      openLabel: 'View storage',
    },
    {
      key: 'stuck',
      tone: countTone(operations.stuckDocumentCount, 'warn'),
      count: operations.stuckDocumentCount ?? '—',
      title: 'Stuck transitions',
      detail: operations.stuckDocumentCount == null ? 'Count unavailable' : operations.stuckDocumentCount > 0 ? 'Documents not progressing through workflow' : 'No stuck transitions',
      onOpen: () => setPlatformTab('incidents'),
      openLabel: 'View incidents',
    },
    {
      key: 'submissions',
      tone: countTone(operations.attentionSubmissionCount, 'warn'),
      count: operations.attentionSubmissionCount ?? '—',
      title: 'Incomplete submissions',
      detail: operations.attentionSubmissionCount == null ? 'Count unavailable' : operations.attentionSubmissionCount > 0 ? 'Submissions missing expected documents' : 'No incomplete submissions',
      onOpen: () => setPlatformTab('incidents'),
      openLabel: 'View incidents',
    },
  ] : []
  const openSignalCount = overviewSignals.filter((signal) => signal.tone === 'danger' || signal.tone === 'warn').length
  const activeRoleLabel = DEMO_ROLES.find((role) => role.value === selectedPersona)?.label
  const adminStorageAttention = storageVolumes.filter((item) => item.observationError != null || item.warningThresholdReached || item.stopWriteThresholdReached).length
  const stagedIdentity = identityOverview?.staged ?? null
  const identityReadyToActivate = stagedIdentity?.status === 'VALIDATED' && stagedIdentity?.lastTestStatus === 'PASSED'
  const visibleAuditEvents = auditEvents.filter((event) => includeAuditViews || event.action !== 'AUDIT_VIEWED')
  const advancedAuditFilterCount = [auditTargetType, auditTargetId, auditActorType, auditActorId, auditCorrelationId].filter((value) => value.trim()).length

  const renderAuditPanel = (title: string, description: string) => (
    <AdminSection flush className="audit-panel" title={title} meta={visibleAuditEvents.length} description={description}>
      <form className="ax-filters" onSubmit={(event) => { event.preventDefault(); loadAudit(true).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : 'Audit query failed.')) }}>
        <div className="ax-filters-primary">
          <label>Action<input value={auditAction} onChange={(event) => setAuditAction(event.target.value)} placeholder="e.g. DOCUMENT_DOWNLOADED" /></label>
          <label>Outcome<select value={auditOutcome} onChange={(event) => setAuditOutcome(event.target.value)}><option value="">All outcomes</option><option value="SUCCESS">Success</option><option value="DENIED">Denied</option><option value="FAILED">Failed</option></select></label>
          <label>From<input type="datetime-local" value={auditFrom} onChange={(event) => setAuditFrom(event.target.value)} /></label>
          <label>To<input type="datetime-local" value={auditTo} onChange={(event) => setAuditTo(event.target.value)} /></label>
          <button type="submit" disabled={loadingAudit}>{loadingAudit ? 'Loading…' : 'Apply filters'}</button>
        </div>
        {auditMoreFilters && (
          <div className="ax-filters-more" id="audit-more-filters">
            <label>Resource type<input value={auditTargetType} onChange={(event) => setAuditTargetType(event.target.value)} placeholder="e.g. DOCUMENT" /></label>
            <label>Resource ID<input value={auditTargetId} onChange={(event) => setAuditTargetId(event.target.value)} placeholder="Exact resource ID" /></label>
            <label>Actor type<input value={auditActorType} onChange={(event) => setAuditActorType(event.target.value)} placeholder="e.g. USER" /></label>
            <label>Actor ID<input value={auditActorId} onChange={(event) => setAuditActorId(event.target.value)} placeholder="Exact actor ID" /></label>
            <label>Correlation ID<input value={auditCorrelationId} onChange={(event) => setAuditCorrelationId(event.target.value)} placeholder="Exact correlation ID" /></label>
          </div>
        )}
        <div className="ax-filters-toolbar">
          <button type="button" className="ax-disclosure" aria-expanded={auditMoreFilters} aria-controls="audit-more-filters" onClick={() => setAuditMoreFilters((current) => !current)}>
            {auditMoreFilters ? 'Fewer filters' : `More filters${advancedAuditFilterCount ? ` (${advancedAuditFilterCount} set)` : ''}`}
          </button>
          <label className="ax-check"><input type="checkbox" checked={includeAuditViews} onChange={(event) => setIncludeAuditViews(event.target.checked)} /> Include view events</label>
        </div>
      </form>
      {visibleAuditEvents.length === 0 ? <EmptyState title={loadingAudit ? 'Loading audit events…' : 'No audit events match these filters'} /> : (
        <div className="ax-table-wrap">
          <table className="ax-table audit-table audit-ledger">
            <thead><tr><th>Time</th><th>Event</th><th>Actor</th><th>Outcome</th><th>Details</th></tr></thead>
            <tbody>
              {visibleAuditEvents.map((event) => (
                <tr key={event.auditEventId}>
                  <td className="audit-ledger-time">{formatDate(event.occurredAt)}</td>
                  <td><strong className="audit-ledger-event">{humanize(event.action)}</strong></td>
                  <td><span className="audit-ledger-actor" title={event.actorId || ''}>{event.actorId || humanize(event.actorType)}</span></td>
                  <td><StatusPill value={event.outcome} /></td>
                  <td><button type="button" className="audit-detail-link" onClick={() => setSelectedAuditEvent(event)} aria-label={`View details for ${humanize(event.action)} at ${formatDate(event.occurredAt)}`}>View details <span aria-hidden="true">›</span></button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {auditHasMore && <div className="ax-load-more"><button className="secondary" onClick={() => loadAudit(false)} disabled={loadingAudit}>{loadingAudit ? 'Loading…' : 'Load older events'}</button></div>}
      {selectedAuditEvent && (
        <>
          <button type="button" className="admin-drawer-backdrop" aria-label="Close audit event details" onClick={() => setSelectedAuditEvent(null)} />
          <aside className="admin-drawer audit-event-drawer" role="dialog" aria-modal="true" aria-label="Audit event details">
            <div className="admin-drawer-header">
              <div><span className="kicker">Audit evidence</span><h2>{humanize(selectedAuditEvent.action)}</h2></div>
              <button type="button" className="drawer-close-button" aria-label="Close audit details" onClick={() => setSelectedAuditEvent(null)}><Icon name="close" /></button>
            </div>
            <div className="admin-drawer-body">
              <dl className="audit-evidence-fields">
                <div><dt>Time</dt><dd>{formatDate(selectedAuditEvent.occurredAt)}</dd></div>
                <div><dt>Outcome</dt><dd><StatusPill value={selectedAuditEvent.outcome} /></dd></div>
                <div><dt>Action code</dt><dd className="mono">{selectedAuditEvent.action}</dd></div>
                <div><dt>Actor type</dt><dd>{humanize(selectedAuditEvent.actorType)}</dd></div>
                <div><dt>Actor ID</dt><dd className="mono">{selectedAuditEvent.actorId || '—'}</dd></div>
                <div><dt>Resource type</dt><dd>{humanize(selectedAuditEvent.targetType)}</dd></div>
                <div><dt>Resource ID</dt><dd className="mono">{selectedAuditEvent.targetId || '—'}</dd></div>
                <div><dt>Correlation ID</dt><dd className="mono">{selectedAuditEvent.correlationId || '—'}{selectedAuditEvent.correlationId && <button type="button" className="audit-copy" onClick={() => { void navigator.clipboard?.writeText(selectedAuditEvent.correlationId || '') }}>Copy</button>}</dd></div>
              </dl>
              <h3 className="audit-evidence-title">Safe details</h3>
              <pre className="audit-evidence-json">{(() => { try { return JSON.stringify(JSON.parse(selectedAuditEvent.safeDetailsJson || '{}'), null, 2) } catch { return selectedAuditEvent.safeDetailsJson || '—' } })()}</pre>
            </div>
          </aside>
        </>
      )}
    </AdminSection>
  )


  return (
    <div className={`modern-app-shell ${sidebarCollapsed ? 'sidebar-collapsed' : ''}`}>
      <header className="mobile-app-bar">
        <button className="shell-icon-button" type="button" onClick={() => setDrawerOpen(true)} aria-label="Open navigation"><Icon name="menu" /></button>
        <div className="mobile-brand"><span className="brand-lettermark">S</span><strong>vero</strong></div>
        <div className="mobile-user-avatar" title={session?.subject || 'Local user'}>{initials}</div>
      </header>

      {drawerOpen && <button className="nav-drawer-backdrop" aria-label="Close navigation" onClick={() => setDrawerOpen(false)} />}
      <aside className={`modern-sidebar ${drawerOpen ? 'drawer-open' : ''}`}>
        <div className="sidebar-brand-row vero-brand-row">
          <div className="sidebar-brand vero-wordmark" title="vero"><strong>vero</strong><span className="vero-brand-dot" aria-hidden="true" /></div>
          <button className="sidebar-collapse-button" type="button" onClick={toggleSidebar} aria-label={sidebarCollapsed ? 'Expand navigation' : 'Collapse navigation'}>
            <Icon name={sidebarCollapsed ? 'chevronRight' : 'chevronLeft'} />
          </button>
        </div>
        <div className="vero-tenant-card" title={session?.tenantDisplayName || 'Company name unavailable'}>
          <span className="vero-tenant-monogram" aria-hidden="true">{session?.tenantDisplayName?.trim() ? session.tenantDisplayName.trim().charAt(0).toUpperCase() : <Icon name="storage" />}</span>
          <span className="vero-tenant-name">{session?.tenantDisplayName || 'Company name unavailable'}</span>
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
              <span>{localMode ? (activeRoleLabel ?? 'Local user') : (session?.tenantDisplayName || 'Company name unavailable')}</span>
            </div>
          </div>
          {!localMode && <button type="button" className="sidebar-signout" onClick={signOut}>Sign out</button>}
        </div>
      </aside>

      <main className="modern-main-content">
        <div className="sdv-page">
          <header className={`vero-topbar ${view === 'search' ? 'vero-topbar-compact' : ''}`}>
            <div className="vero-topbar-copy">
              <nav className="vero-breadcrumb" aria-label="Current location">
                <span className="vero-breadcrumb-company">{session?.tenantDisplayName || 'Company name unavailable'}</span>
                <span className="vero-crumb-sep" aria-hidden="true">/</span>
                <span>{pageMeta.title}</span>
                {view === 'admin' && <><span className="vero-crumb-sep" aria-hidden="true">/</span><span>{selectedPersona === 'tenantIt' ? humanize(tenantItTab) : humanize(businessAdminTab)}</span></>}
                {view === 'operations' && <><span className="vero-crumb-sep" aria-hidden="true">/</span><span>{humanize(platformTab)}</span></>}
              </nav>
              {view !== 'search' && <>
                <h1>{pageMeta.title}</h1>
                <p className="vero-topbar-lede">{pageMeta.description}</p>
              </>}
            </div>
            <div className="vero-topbar-actions">
              {localMode && (
                <span className={`vero-system-chip ${(system?.status || '').toUpperCase() === 'UP' ? 'is-up' : ''}`} title={system?.activeProfiles?.join(', ') || 'local'}>
                  <span className="vero-system-dot" aria-hidden="true" />
                  <span>{system?.status ?? 'Checking'}</span>
                  <span className="vero-system-version">{system?.version ?? 'development'}</span>
                </span>
              )}
              {view === 'operations' && canViewTechnicalFailures && (
                <button className="secondary vero-topbar-button" onClick={() => loadOperations()} disabled={loadingOperations}>{loadingOperations ? 'Refreshing…' : 'Refresh'}</button>
              )}
            </div>
          </header>

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
        <div className={`ax-workspace ${selectedPersona === 'tenantIt' ? 'tenant-it-workspace' : 'business-admin-workspace'}`}>
          {selectedPersona === 'businessAdmin' ? (
            <>
              <AdminTabs
                label="Business administration sections"
                value={businessAdminTab}
                onChange={setBusinessAdminTab}
                tabs={[
                  { id: 'access', label: 'Users & Access' },
                  { id: 'roles', label: 'Roles & Permissions' },
                  { id: 'retention', label: 'Retention Policies' },
                  { id: 'metadata', label: 'Metadata Configuration' },
                ]}
              />

              {businessAdminTab === 'access' && (
                <div className="ax-split">
                  <AdminSection flush title="Business roles" meta={BUSINESS_ROLE_DIRECTORY.length} description="The roles SDV recognises for business content, and what each one is responsible for.">
                    <div className="ax-table-wrap">
                      <table className="ax-table">
                        <thead><tr><th>Role</th><th>Responsibility</th><th>Token role</th><th className="ax-col-action">Document actions</th></tr></thead>
                        <tbody>
                          {BUSINESS_ROLE_DIRECTORY.map((role) => (
                            <tr key={role.code}>
                              <td><span className="ax-cell-primary"><strong>{role.name}</strong></span></td>
                              <td>{role.responsibility}</td>
                              <td><span className="ax-role-code">{role.code}</span></td>
                              <td className="ax-col-action">{role.configurable ? <button type="button" className="ax-link-button" onClick={() => setBusinessAdminTab('roles')}>Configure</button> : <span className="ax-muted">Not configurable here</span>}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </AdminSection>
                  <AdminSection title="Where assignments live">
                    <div className="ax-stack">
                      <p className="ax-note">Sign-in and user-to-role assignment are managed in your identity provider. SDV reads each user&apos;s role from their sign-in token, so assignments are changed there, not here.</p>
                      <p className="ax-note">What each business role can do with a document is set in <strong>Roles &amp; Permissions</strong>.</p>
                    </div>
                  </AdminSection>
                </div>
              )}

              {businessAdminTab === 'roles' && (
                <AdminSection flush title="Roles & permissions" description="Choose which document actions each business content role can perform. Search and View are baseline access for every business role and cannot be turned off.">
                  {roleCapabilities ? (
                    <>
                      <div className="ax-table-wrap">
                        <table className="ax-table">
                          <thead><tr><th>Permission</th><th className="ax-perm-col">Business User</th><th className="ax-perm-col">Document Steward</th></tr></thead>
                          <tbody>
                            <tr className="ax-row-group"><td colSpan={3}>Baseline access · always on</td></tr>
                            {['Search business records', 'View documents'].map((label) => (
                              <tr key={label}><td><span className="ax-cell-primary"><strong>{label}</strong></span></td><td className="ax-perm-col"><span className="ax-lock">Locked on</span></td><td className="ax-perm-col"><span className="ax-lock">Locked on</span></td></tr>
                            ))}
                            <tr className="ax-row-group"><td colSpan={3}>Document actions · configurable</td></tr>
                            {roleCapabilities.configurableCapabilities.map((capability) => (
                              <tr key={capability}>
                                <td><span className="ax-cell-primary"><strong>{capabilityLabel(capability)}</strong></span></td>
                                {(['BUSINESS_USER', 'DOCUMENT_STEWARD'] as const).map((role) => (
                                  <td className="ax-perm-col" key={`${role}-${capability}`}>
                                    <label className="permission-switch">
                                      <input type="checkbox" role="switch" aria-label={`${capabilityLabel(capability)} for ${role === 'BUSINESS_USER' ? 'Business User' : 'Document Steward'}`} checked={Boolean(roleCapabilities.roles[role]?.[capability])} disabled={!canConfigureRolePermissions} onChange={(event) => setRoleCapabilities((current) => current ? { ...current, roles: { ...current.roles, [role]: { ...current.roles[role], [capability]: event.target.checked } } } : current)} />
                                      <span aria-hidden="true" />
                                    </label>
                                  </td>
                                ))}
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                      <div className="ax-actionbar ax-actionbar-sticky">
                        <span className="ax-actionbar-note">{canConfigureRolePermissions ? 'Changes take effect for new sessions.' : 'You can view these permissions but not change them.'}</span>
                        <div className="ax-actionbar-buttons">
                          <button className="primary-action" onClick={saveRoleCapabilities} disabled={savingRoleCapabilities || !canConfigureRolePermissions}>{savingRoleCapabilities ? 'Saving…' : 'Save permissions'}</button>
                        </div>
                      </div>
                    </>
                  ) : <EmptyState title="Loading role permissions…" />}
                </AdminSection>
              )}

              {businessAdminTab === 'retention' && canConfigureRetention && (
                <>
                  <AdminSection
                    className="ax-section-live"
                    title={<>Active retention policy <span className="ax-live-badge">In force</span></>}
                    actions={<button type="button" className="primary-action" onClick={() => { setRetentionNameInput(''); setRetentionDescriptionInput(''); setRetentionDaysInput(''); setAdminDrawerMode('retention-create') }}><Icon name="plus" /> Activate new policy</button>}
                  >
                    {retentionPolicy ? (
                      <div className="ax-policy-hero">
                        <div className="ax-policy-hero-main">
                          <h3>{retentionPolicy.policyName}</h3>
                          {retentionPolicy.description && <p>{retentionPolicy.description}</p>}
                          <span className="ax-sub">Version {retentionPolicy.version} · Effective {formatDate(retentionPolicy.effectiveFrom)} · Set by {retentionPolicy.createdBy}</span>
                        </div>
                        <div className="ax-policy-value">
                          <strong>{retentionPolicy.indefinite ? 'Indefinite' : `${retentionPolicy.defaultRetentionDays} days`}</strong>
                          <span>Snapshotted onto each document at ingestion</span>
                        </div>
                      </div>
                    ) : <p className="ax-note">Loading retention policy…</p>}
                  </AdminSection>
                  <AdminSection flush title="Version history" meta={retentionPolicy?.history?.length ?? 0} description="Each activation creates an immutable version. Documents keep the retention they were ingested with; older documents are never rewritten.">
                    {retentionPolicy?.history?.length ? (
                      <div className="ax-table-wrap">
                        <table className="ax-table">
                          <thead><tr><th>Version</th><th>Policy</th><th>Retention</th><th>Effective from</th><th>Effective until</th><th>Created by</th></tr></thead>
                          <tbody>
                            {retentionPolicy.history.map((policy) => {
                              const current = policy.policyVersionId === retentionPolicy.policyVersionId
                              return (
                                <tr key={policy.policyVersionId} className={current ? 'ax-row-current' : ''}>
                                  <td className="ax-col-num">v{policy.version}</td>
                                  <td><span className="ax-cell-primary"><strong>{policy.policyName}</strong>{policy.description && <small>{policy.description}</small>}</span></td>
                                  <td>{policy.indefinite ? 'Indefinite' : `${policy.defaultRetentionDays} days`}</td>
                                  <td>{formatDate(policy.effectiveFrom)}</td>
                                  <td>{policy.effectiveUntil ? formatDate(policy.effectiveUntil) : <StatusPill value="ACTIVE" label="Current" />}</td>
                                  <td>{policy.createdBy}</td>
                                </tr>
                              )
                            })}
                          </tbody>
                        </table>
                      </div>
                    ) : <EmptyState title="No policy history available" />}
                  </AdminSection>
                </>
              )}

              {businessAdminTab === 'metadata' && canConfigureMetadata && (
                <AdminSection flush title="Metadata configuration" meta={fields.length} description="Control how each source's fields are labelled and searched in SDV.">
                  <div className="ax-context-bar">
                    <label>Source context
                      <select value={selectedSource} onChange={(event) => { setSelectedSource(event.target.value); setSelectedMetadataFieldKey(''); setAdminDrawerMode(null) }}>
                        {sources.map((source) => <option key={source.sourceId} value={source.sourceId}>{source.displayName}</option>)}
                      </select>
                    </label>
                    {selectedAdminSource && (
                      <div className="ax-context-facts">
                        <span>Source key <span className="ax-code">{selectedAdminSource.sourceKey}</span></span>
                        <StatusPill value={selectedAdminSource.status} />
                      </div>
                    )}
                  </div>
                  <div className="ax-ownership">
                    <div className="ax-owner-other"><strong>Defined by Tenant IT</strong><span>Field key, data type, and whether the source requires it. Fields can&apos;t be created or deleted here.</span></div>
                    <div className="ax-owner-here"><strong>Configured here</strong><span>Display label, normalization, whether the field is searchable, its search mode, and business identifier.</span></div>
                  </div>
                  {!selectedAdminSource ? <EmptyState title="Select a source">Choose a source to configure its business search behavior.</EmptyState> : fields.length === 0 ? <EmptyState title="No source fields defined">Tenant IT must define the integration metadata contract first.</EmptyState> : (
                    <div className="ax-table-wrap">
                      <table className="ax-table">
                        <thead><tr><th>Field</th><th>Type</th><th>Source requirement</th><th>Search</th><th>Business ID</th><th className="ax-col-action"><span className="visually-hidden">Edit</span></th></tr></thead>
                        <tbody>
                          {fields.map((field) => (
                            <tr key={field.definitionId}>
                              <td><span className="ax-cell-primary"><strong>{field.displayLabel}</strong><small className="mono">{field.fieldKey}</small></span></td>
                              <td>{field.fieldType === 'STRING' ? 'Text' : humanize(field.fieldType)}</td>
                              <td>{field.required ? 'Required' : <span className="ax-muted">Optional</span>}</td>
                              <td>{field.searchable ? <StatusPill value="ACTIVE" tone="neutral" label={`${humanize(field.searchMode)} match`} /> : <span className="ax-muted">Not searchable</span>}</td>
                              <td>{field.businessIdentifier ? <StatusPill value="ACTIVE" label="Business ID" /> : <span className="ax-muted">—</span>}</td>
                              <td className="ax-col-action"><button type="button" className="ax-icon-button" aria-label={`Edit ${field.displayLabel}`} title="Edit" onClick={() => { setSelectedMetadataFieldKey(field.fieldKey); setAdminDrawerMode('metadata-edit') }}><Icon name="edit" /></button></td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </AdminSection>
              )}
            </>
          ) : (
            <>
              <AdminTabs
                label="Tenant IT administration sections"
                value={tenantItTab}
                onChange={setTenantItTab}
                tabs={[
                  { id: 'storage', label: 'Storage', attention: adminStorageAttention > 0 },
                  { id: 'authentication', label: 'Authentication' },
                  { id: 'integrations', label: 'Integrations' },
                  { id: 'environment', label: 'Environment' },
                ]}
              />

              {tenantItTab === 'storage' && canConfigureStorage && (
                <AdminSection
                  flush
                  title="Storage volumes"
                  meta={storageVolumes.length}
                  description={adminStorageAttention > 0 ? `${adminStorageAttention} of ${storageVolumes.length} volume(s) need attention.` : 'Encrypted document storage in your environment. Priority controls preferred placement.'}
                  actions={<>
                    <button type="button" className="secondary" aria-label="About storage volumes" aria-expanded={storageHelpOpen} onClick={() => setStorageHelpOpen((current) => !current)}>How storage works</button>
                    <button type="button" className="primary-action" onClick={() => setAdminDrawerMode('storage-create')}><Icon name="plus" /> Add storage volume</button>
                  </>}
                >
                  {storageHelpOpen && <p className="ax-help"><strong>How SDV storage works.</strong> Storage volumes contain encrypted SDV document objects. Priority controls preferred placement. Warning % raises capacity attention; Stop-write % blocks new writes before the filesystem becomes critically full. Changing a path does not relocate documents already stored there.</p>}
                  {storageVolumes.length === 0 ? <EmptyState title="No storage volume configured">Add an ACTIVE volume before ingesting production documents.</EmptyState> : (
                    <div className="ax-table-wrap">
                      <table className="ax-table">
                        <thead><tr><th>Volume</th><th>State</th><th className="ax-col-num">Priority</th><th>Capacity</th><th>Thresholds</th><th>Health</th><th className="ax-col-action"><span className="visually-hidden">Edit</span></th></tr></thead>
                        <tbody>
                          {storageVolumes.map((item) => {
                            const health = item.observationError ? 'UNAVAILABLE' : item.stopWriteThresholdReached ? 'CRITICAL' : item.warningThresholdReached ? 'WARNING' : 'HEALTHY'
                            return (
                              <tr key={item.volume.volumeId}>
                                <td><span className="ax-cell-primary"><strong>{item.volume.displayName}</strong><small className="mono">{item.volume.rootPath}</small></span></td>
                                <td><StatusPill value={item.volume.state} /></td>
                                <td className="ax-col-num">{item.volume.priority}</td>
                                <td><CapacityMeter usedPercent={item.capacity ? item.capacity.usedPercent : null} warningPercent={item.volume.warningPercent} stopWritePercent={item.volume.stopWritePercent} /></td>
                                <td><span className="ax-sub">Warn {item.volume.warningPercent}% · Stop {item.volume.stopWritePercent}%</span></td>
                                <td><StatusPill value={health} /></td>
                                <td className="ax-col-action"><button type="button" className="ax-icon-button" aria-label={`Edit ${item.volume.displayName}`} title="Edit" onClick={() => { setSelectedStorageVolumeId(item.volume.volumeId); setAdminDrawerMode('storage-edit') }}><Icon name="edit" /></button></td>
                              </tr>
                            )
                          })}
                        </tbody>
                      </table>
                    </div>
                  )}
                </AdminSection>
              )}

              {tenantItTab === 'authentication' && canConfigureTenantAuth && (
                <>
                  <AdminSection
                    className="ax-section-live"
                    title={<>Active binding {identityOverview?.active && <span className="ax-live-badge">Live</span>}</>}
                    description="The trusted OIDC binding SDV uses for sign-in right now. Tokens, private keys, and client secrets are never returned here."
                  >
                    {identityOverview?.active ? (
                      <StateSheet columns={3} items={[
                        { label: 'Provider', value: identityOverview.active.displayName },
                        { label: 'Type', value: identityOverview.active.providerType },
                        { label: 'Version', value: `v${identityOverview.active.configurationVersion}` },
                        { label: 'Issuer', value: identityOverview.active.issuerUri, mono: true, wide: true },
                        { label: 'Browser client', value: identityOverview.active.browserClientId ?? 'Not configured', mono: true },
                        { label: 'API audience', value: identityOverview.active.apiAudience, mono: true },
                        { label: 'Last changed', value: formatDate(identityOverview.active.updatedAt) },
                      ]} />
                    ) : <p className="ax-note">No ACTIVE enterprise identity connection is available. Automatic bootstrap will not overwrite existing administered identity records.</p>}
                  </AdminSection>

                  <div className="ax-split">
                    <AdminSection
                      flush
                      className="ax-section-staged"
                      title={<>Staged configuration <span className="ax-staged-badge">{stagedIdentity ? `${humanize(stagedIdentity.status)} · not live` : 'Not live'}</span></>}
                      description="Edits here never touch the active binding. Activation is allowed only for the exact VALIDATED version with a successful connection test from the last 15 minutes."
                    >
                      <form onSubmit={saveIdentityDraft}>
                        <div className="ax-section-body">
                          <ol className="ax-steps" aria-label="Activation steps">
                            <li className={stagedIdentity ? 'is-done' : ''}><span>1 · Save draft</span><strong>{stagedIdentity ? `Saved as v${stagedIdentity.configurationVersion}` : 'Not saved yet'}</strong><small>{stagedIdentity ? formatDate(stagedIdentity.updatedAt) : 'Save to stage a version'}</small></li>
                            <li className={stagedIdentity?.lastTestStatus === 'PASSED' ? 'is-done' : stagedIdentity?.lastTestStatus === 'FAILED' ? 'is-failed' : ''}><span>2 · Test connection</span><strong>{stagedIdentity?.lastTestStatus === 'PASSED' ? 'Passed' : stagedIdentity?.lastTestStatus === 'FAILED' ? 'Failed' : 'Not tested'}</strong><small>{stagedIdentity?.lastTestedAt ? formatDate(stagedIdentity.lastTestedAt) : 'Requires a saved draft'}</small></li>
                            <li className={identityReadyToActivate ? 'is-done' : ''}><span>3 · Activate</span><strong>{identityReadyToActivate ? 'Ready to activate' : 'Locked'}</strong><small>{identityReadyToActivate ? 'Replaces the active binding' : 'Needs a validated, passing test'}</small></li>
                          </ol>
                          <div className="ax-form-grid">
                            <label>Provider ID<input value={identityDraft.providerId} onChange={(event) => setIdentityDraft({ ...identityDraft, providerId: event.target.value })} required /></label>
                            <label>Display name<input value={identityDraft.displayName} onChange={(event) => setIdentityDraft({ ...identityDraft, displayName: event.target.value })} required /></label>
                            <label className="ax-span-2">Issuer URI<input value={identityDraft.issuerUri} onChange={(event) => setIdentityDraft({ ...identityDraft, issuerUri: event.target.value })} placeholder="https://login.example.com/tenant/v2.0" required /></label>
                            <label>Browser client ID<input value={identityDraft.browserClientId} onChange={(event) => setIdentityDraft({ ...identityDraft, browserClientId: event.target.value })} required /></label>
                            <label>API audience<input value={identityDraft.apiAudience} onChange={(event) => setIdentityDraft({ ...identityDraft, apiAudience: event.target.value })} required /></label>
                          </div>
                          {stagedIdentity?.lastTestStatus && <div className={`banner ax-inset-banner ${stagedIdentity.lastTestStatus === 'PASSED' ? 'notice' : 'error'}`}><strong>Last test: {humanize(stagedIdentity.lastTestStatus)}</strong> <span>{stagedIdentity.lastTestMessage}</span></div>}
                        </div>
                        <div className="ax-actionbar">
                          <span className="ax-actionbar-note">The previous active binding is kept as a rollback target.</span>
                          <div className="ax-actionbar-buttons">
                            <button type="submit" className="secondary identity-save-action" disabled={savingIdentity || activatingIdentity}>{savingIdentity ? 'Saving…' : 'Save draft'}</button>
                            <button type="button" className="secondary identity-test-action" disabled={testingIdentity || activatingIdentity || !identityOverview?.staged} onClick={() => void testIdentityDraft()}>{testingIdentity ? 'Testing…' : 'Test connection'}</button>
                            <button type="button" className="primary-action identity-activate-action" disabled={activatingIdentity || testingIdentity || savingIdentity || identityOverview?.staged?.status !== 'VALIDATED' || identityOverview?.staged?.lastTestStatus !== 'PASSED'} onClick={() => void activateIdentityDraft()}>{activatingIdentity ? 'Activating…' : 'Activate validated version'}</button>
                          </div>
                        </div>
                      </form>
                    </AdminSection>

                    <AdminSection flush title="Known-good rollback" meta={identityOverview?.rollbackCandidates?.length ?? 0} description="Previously active configurations for this tenant. Rollback atomically replaces the current active binding.">
                      {identityOverview?.rollbackCandidates?.length ? (
                        <ul className="ax-list">
                          {identityOverview.rollbackCandidates.map((candidate) => (
                            <li key={candidate.connectionId}>
                              <span className="ax-cell-primary"><strong>{candidate.displayName} · v{candidate.configurationVersion}</strong><small className="mono">{candidate.issuerUri}</small><small>Recorded status: {humanize(candidate.status)}</small></span>
                              <button type="button" className="secondary identity-rollback-action" disabled={Boolean(rollingBackIdentity) || activatingIdentity} onClick={() => void rollbackIdentity(candidate)}>{rollingBackIdentity === candidate.connectionId ? 'Rolling back…' : 'Rollback'}</button>
                            </li>
                          ))}
                        </ul>
                      ) : <EmptyState title="No rollback target yet">The previously active configuration will appear here after the first activation.</EmptyState>}
                    </AdminSection>
                  </div>
                </>
              )}

              {tenantItTab === 'integrations' && canConfigureSources && (
                <AdminSection
                  flush
                  title="Ingestion integrations"
                  meta={sources.length}
                  description="Each integration is a producer system: its identity, authentication reference, and technical metadata contract. Business Administration separately decides how those fields are searched."
                  actions={<button type="button" className="primary-action" onClick={() => setAdminDrawerMode('integration-create')}><Icon name="plus" /> Add source</button>}
                >
                  {sources.length === 0 ? <EmptyState title="No integrations configured">Add a producer source to define its technical contract.</EmptyState> : (
                    <div className="ax-table-wrap">
                      <table className="ax-table">
                        <thead><tr><th>Integration</th><th>Source key</th><th>Status</th><th>Authentication</th><th className="ax-col-action"><span className="visually-hidden">Edit</span></th></tr></thead>
                        <tbody>
                          {sources.map((source) => (
                            <tr key={source.sourceId}>
                              <td><span className="ax-cell-primary"><strong>{source.displayName}</strong></span></td>
                              <td><span className="ax-code">{source.sourceKey}</span></td>
                              <td><StatusPill value={source.status} /></td>
                              <td>{source.authType ? AUTH_TYPE_LABELS[source.authType] ?? source.authType : '—'}</td>
                              <td className="ax-col-action"><button type="button" className="ax-icon-button" aria-label={`Edit ${source.displayName}`} title="Edit" onClick={() => { setSelectedSource(source.sourceId); setAdminDrawerMode('integration-edit') }}><Icon name="edit" /></button></td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </AdminSection>
              )}

              {tenantItTab === 'environment' && (
                <AdminSection
                  title="Customer environment"
                  description="The SDV deployment running in this customer environment."
                  actions={<button className="secondary" onClick={() => navigate('operations')}>Open operational readiness</button>}
                >
                  <StateSheet columns={4} items={[
                    { label: 'Product', value: system?.productName ?? 'Secure Document Vault' },
                    { label: 'Version', value: system?.version ?? 'Unknown', mono: true },
                    { label: 'Status', value: system?.status ? <StatusPill value={system.status} /> : 'Unknown' },
                    { label: 'Profiles', value: system?.activeProfiles?.join(', ') || 'Not exposed', mono: true },
                  ]} />
                </AdminSection>
              )}
            </>
          )}
        </div>
      )}

      {view === 'operations' && canViewTechnicalFailures && (
        <div className="ax-workspace operations-workspace">
          <AdminTabs
            label="Platform operations sections"
            value={platformTab}
            onChange={setPlatformTab}
            tabs={[
              { id: 'overview', label: 'Overview' },
              { id: 'storage', label: 'Storage', attention: attentionVolumes.length > 0 },
              { id: 'health', label: 'Platform Health' },
              { id: 'incidents', label: 'Incidents', attention: incidentTotal > 0 },
              { id: 'audit', label: 'Audit' },
              { id: 'configuration', label: 'Configuration' },
            ]}
          />

          {platformTab === 'overview' && (!operations ? <AdminSection title="Operational overview"><p className="ax-note">Loading operational health…</p></AdminSection> : (
            <>
              <section className={`vx-ops-status vx-tone-${statusTone(operations.overallStatus)}`} aria-label="Overall platform status">
                <div className="vx-ops-status-main">
                  <span className="vx-ops-status-label">Overall</span>
                  <strong className="vx-ops-status-value"><span className="vx-ops-status-dot" aria-hidden="true" />{humanize(operations.overallStatus)}</strong>
                </div>
                <p className="vx-ops-status-summary">
                  {openSignalCount === 0 ? 'No conditions need an operator right now.' : `${openSignalCount} of ${overviewSignals.length} conditions need an operator.`}
                </p>
                <span className="vx-ops-status-meta">Observed {formatDate(operations.observedAt)} · refreshes every 30 seconds</span>
              </section>

              <section className="vx-signals" aria-labelledby="vx-signals-title">
                <header className="vx-signals-head">
                  <h2 id="vx-signals-title">Needs attention</h2>
                  <p>Conditions that may need an operator, in priority order.</p>
                </header>
                <div className="vx-signal-grid">
                  {overviewSignals.map((signal) => (
                    <AttentionTile key={signal.key} tone={signal.tone} count={signal.count} title={signal.title} detail={signal.detail} onOpen={signal.onOpen} openLabel={signal.openLabel} />
                  ))}
                </div>
              </section>

              <AdminSection flush title="Core services" className="vx-services">
                <div className="ax-service-list">
                  {[operations.application, operations.database, operations.keyService].map((item) => (
                    <div className="ax-service" key={item.component}>
                      <div className="ax-service-head"><strong>{humanize(item.component)}</strong><StatusPill value={item.status} /></div>
                      <small>{item.message}</small>
                    </div>
                  ))}
                  <div className="ax-service">
                    <div className="ax-service-head"><strong>Retention sweep</strong>{retentionSweepStatus ? <StatusPill value={retentionSweepStatus.outcome} /> : <span className="ax-muted">Loading</span>}</div>
                    <small>{retentionSweepStatus?.completedAt ? `${retentionSweepStatus.deleted} deleted · ${retentionSweepStatus.failed} failed · ${formatDate(retentionSweepStatus.completedAt)}` : 'No sweep evidence yet'}</small>
                  </div>
                </div>
              </AdminSection>
            </>
          ))}

          {platformTab === 'storage' && (
            <AdminSection flush title="Processing storage" meta={operations?.storageVolumes.length ?? 0} description={attentionVolumes.length > 0 ? `${attentionVolumes.length} volume(s) need attention.` : 'All active storage volumes are healthy.'}>
              {!operations ? <EmptyState title="Loading storage…" /> : (
                <div className="ax-table-wrap">
                  <table className="ax-table">
                    <thead><tr><th>Volume</th><th>State</th><th>Health</th><th>Capacity</th><th>Thresholds</th><th>Change state</th></tr></thead>
                    <tbody>
                      {operations.storageVolumes.map((volume) => (
                        <tr key={volume.volumeId}>
                          <td><span className="ax-cell-primary"><strong>{volume.displayName}</strong><small className="mono">{volume.rootPath}</small>{volumeNeedsAttention(volume) && <small className="ax-flag-text">Needs attention{volume.observationError ? ` · ${volume.observationError}` : ''}</small>}</span></td>
                          <td><StatusPill value={volume.state} /></td>
                          <td><StatusPill value={volume.health} /></td>
                          <td><CapacityMeter usedPercent={volume.usedPercent} warningPercent={volume.warningPercent} stopWritePercent={volume.stopWritePercent} /></td>
                          <td><span className="ax-sub">Warn {volume.warningPercent}% · Stop {volume.stopWritePercent}%</span></td>
                          <td>{canConfigureStorage ? <select aria-label={`Change state for ${volume.displayName}`} value={volume.state} onChange={(event) => updateVolumeState(volume, event.target.value as StorageVolumeStatus['state'])}><option value="ACTIVE">Active</option><option value="READ_ONLY">Read only</option><option value="OFFLINE">Offline</option></select> : <span className="ax-muted">No permission</span>}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </AdminSection>
          )}

          {platformTab === 'health' && (
            <AdminSection flush title="Core services" description={operations ? `Observed ${formatDate(operations.observedAt)}` : undefined}>
              {!operations ? <EmptyState title="Loading health…" /> : (
                <div className="ax-table-wrap">
                  <table className="ax-table">
                    <thead><tr><th>Component</th><th>Status</th><th>Detail</th></tr></thead>
                    <tbody>
                      {[operations.application, operations.database, operations.keyService].map((item) => (
                        <tr key={item.component}><td><span className="ax-cell-primary"><strong>{humanize(item.component)}</strong></span></td><td><StatusPill value={item.status} /></td><td>{item.message}</td></tr>
                      ))}
                      <tr><td><span className="ax-cell-primary"><strong>Overall</strong></span></td><td><StatusPill value={operations.overallStatus} /></td><td className="ax-muted">Combined deployment status</td></tr>
                    </tbody>
                  </table>
                </div>
              )}
            </AdminSection>
          )}

          {platformTab === 'incidents' && (
            <>
              <AdminSection flush title="Processing failures" meta={operations ? (operations.processingFailureCount ?? 'Unavailable') : '—'}>
                {operations?.processingFailures.length ? (
                  <div className="ax-table-wrap">
                    <table className="ax-table">
                      <thead><tr><th>Failure</th><th>Document</th><th className="ax-col-num">Attempts</th><th>Ingested</th></tr></thead>
                      <tbody>
                        {operations.processingFailures.map((item) => (
                          <tr key={item.documentId}>
                            <td><span className="ax-cell-primary"><strong>{humanize(item.errorCode || 'PROCESSING_FAILED')}</strong><span><span className="ax-code">{item.errorCode || 'PROCESSING_FAILED'}</span></span></span></td>
                            <td><span className="mono">{item.documentId}</span></td>
                            <td className="ax-col-num">{item.processingAttemptCount}</td>
                            <td>{formatDate(item.ingestedAt)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : <EmptyState title="No processing failures" />}
              </AdminSection>
              <AdminSection flush title="Stuck transitions" meta={operations ? (operations.stuckDocumentCount ?? 'Unavailable') : '—'}>
                {operations?.stuckDocuments.length ? (
                  <div className="ax-table-wrap">
                    <table className="ax-table">
                      <thead><tr><th>Stuck in</th><th>Document</th><th>In this state since</th></tr></thead>
                      <tbody>
                        {operations.stuckDocuments.map((item) => (
                          <tr key={item.documentId}><td><StatusPill value={item.status} /></td><td><span className="mono">{item.documentId}</span></td><td>{formatDate(item.stateSince)}</td></tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : <EmptyState title="No stuck transitions" />}
              </AdminSection>
              <AdminSection flush title="Incomplete submissions" meta={operations ? (operations.attentionSubmissionCount ?? 'Unavailable') : '—'}>
                {operations?.attentionSubmissions.length ? (
                  <div className="ax-table-wrap">
                    <table className="ax-table">
                      <thead><tr><th>Submission</th><th>Documents received</th><th>Status</th><th>Last activity</th></tr></thead>
                      <tbody>
                        {operations.attentionSubmissions.map((item) => (
                          <tr key={item.submissionId}><td><span className="mono">{item.sourceSubmissionId}</span></td><td><strong>{item.receivedDocumentCount} of {item.expectedDocumentCount}</strong></td><td><StatusPill value={item.status} /></td><td>{formatDate(item.lastActivityAt)}</td></tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : <EmptyState title="No incomplete submissions need attention" />}
              </AdminSection>
            </>
          )}

          {platformTab === 'audit' && renderAuditPanel('Audit', 'Technical audit evidence for this tenant. Safe details never include document content or removed sensitive text.')}

          {platformTab === 'configuration' && (
            <AdminSection title="Deployment configuration" description="Settings here apply only to this customer's SDV environment. Licensing and branding are not editable yet.">
              <StateSheet columns={3} items={[
                { label: 'Product', value: system?.productName ?? 'Secure Document Vault' },
                { label: 'Version', value: system?.version ?? 'Unknown', mono: true },
                { label: 'Active profiles', value: system?.activeProfiles?.join(', ') || 'Not exposed', mono: true },
              ]} />
            </AdminSection>
          )}
        </div>
      )}

      {view === 'audit' && canViewTechnicalFailures && (
        <div className="ax-workspace">
          {renderAuditPanel('Tenant audit history', 'Append-oriented tenant audit. Safe details contain technical evidence only; document content and removed sensitive text are never part of this view.')}
        </div>
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
