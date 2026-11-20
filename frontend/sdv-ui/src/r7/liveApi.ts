import { roleApi, contentBinary, type SessionInfo, type SearchSchema, type SearchResponse, type BusinessRecord, type RecordSummary, type SearchSource } from '../api'
import type { Field, Source, RecordItem, Doc } from './model'

export const session = () => roleApi<SessionInfo>('/api/v1/session', 'business')
export const schema = () => roleApi<SearchSchema>('/api/v1/business/search-schema', 'business')
/** Records per search page. The search request and the hints shown to people both use this value. */
export const SEARCH_PAGE_SIZE = 25
/** A date range typed as yyyy-mm-dd days. Both ends are optional. */
export type DayRange = { from: string; to: string }
/** Start of the From day and end of the To day, in the person's own time zone. */
const dayStart = (d: string) => (d ? new Date(d + 'T00:00:00').toISOString() : null)
const dayEnd = (d: string) => (d ? new Date(d + 'T23:59:59.999').toISOString() : null)
/**
 * received: when the submission arrived (server dateFrom/dateTo, which filter submissions.created_at).
 * updated: last activity on the submission (server lastUpdatedFrom/lastUpdatedTo, submissions.last_activity_at).
 */
/** incompleteOnly: only records with missing documents; counts as a criterion. pageSize defaults to SEARCH_PAGE_SIZE. */
/** anywhere (task 0022): text searched in every searchable field, each with its own match; results come best match first. */
export type SearchOptions = { incompleteOnly?: boolean; pageSize?: number; anywhere?: string }
export const search = (sourceId: string, criteria: Record<string,string>, received: DayRange, updated: DayRange, page: number, options: SearchOptions = {}) =>
  roleApi<SearchResponse>('/api/v1/business/search', 'business', { method: 'POST', body: JSON.stringify({
    sourceId, criteria: Object.fromEntries(Object.entries(criteria).map(([key,value]) => [key,value.trim()]).filter(([,value]) => value.length > 0)),
    dateFrom: dayStart(received.from), dateTo: dayEnd(received.to),
    lastUpdatedFrom: dayStart(updated.from), lastUpdatedTo: dayEnd(updated.to), incompleteOnly: options.incompleteOnly ? true : undefined,
    anywhere: options.anywhere?.trim() || undefined,
    page, pageSize: options.pageSize ?? SEARCH_PAGE_SIZE
  }) })
/** The signed-in person's own recently opened records (server: own DOCUMENT_VIEW_OPENED events, still available). */
export type RecentRecord = { sourceId: string; record: RecordSummary; lastOpenedAt: string }
export const recentRecords = (limit = 8) => roleApi<{ records: RecentRecord[] }>('/api/v1/business/me/recent-records?limit=' + limit, 'business')
export const record = (ref: string) => roleApi<BusinessRecord>('/api/v1/business/records/' + encodeURIComponent(ref), 'business')
export function toSource(s: SearchSource): Source {
  return { id:s.sourceId, key:s.sourceKey, name:s.displayName, status:'ACTIVE', fields:s.fields.map(f=>({
    key:f.fieldKey, label:f.displayLabel, type:f.fieldType === 'DATE' ? 'DATE':'STRING',
    identifier:f.businessIdentifier, searchable:true, mode:f.searchMode, normalization:
    f.normalization === 'DIGITS_ONLY' || f.normalization === 'UPPERCASE' ? f.normalization : 'NONE', normalizationRaw:f.normalization
  } as Field)) }
}
const fields = (a: {displayLabel:string,value:string}[]) => a.map(f=>({ label:f.displayLabel,value:f.value }))
/** The server's record title; a record without one is named by its identifier (never guessed from other fields). */
export const recordName = (title: string | null | undefined, ids: {value:string}[], ref: string) => title || ids.map(x=>x.value).join(' · ') || ref
export function toSummary(r: RecordSummary, sourceId:string):RecordItem {
  const ids=fields(r.identifiers), metadata=fields(r.metadataPreview)
  return { ref:r.recordRef,sourceId,identifiers:ids,metadata,title:recordName(r.title,ids,r.recordRef),
    updatedAt:r.latestSubmissionAt,incomplete:r.incomplete?{expected:0,received:0}:null,
    docs:[] }
}
export function toRecord(r: BusinessRecord):RecordItem {
  const ids=fields(r.identifiers)
  const docs:Doc[]=r.documents.map(d=>({id:d.documentId,submissionId:d.submissionId,filename:d.originalFilename,kind:d.originalFilename,pages:d.pageCount ?? 0,redacted:!!d.redacted,
    ingestedAt:d.ingestedAt,expiresAt:d.expiresAt || ''}))
  // A record can have several incomplete submissions: add up the counts and list every missing document.
  const open=r.incompleteAttention?.submissions ?? []
  const incomplete=open.length?{expectedCount:open.reduce((n,s)=>n+s.expectedCount,0),receivedCount:open.reduce((n,s)=>n+s.receivedCount,0),missing:open.flatMap(s=>s.missingSourceDocumentIds ?? [])}:null
  return {ref:r.recordRef,sourceId:r.sourceId,identifiers:ids,metadata:fields(r.metadata),
    title:recordName(r.title,ids,r.recordRef),updatedAt:r.latestSubmissionAt,
    incomplete:incomplete?{expected:incomplete.expectedCount,received:incomplete.receivedCount,missing:incomplete.missing}:null,docs}
}

/** viewingId: this opening's ID; page requests send it back so each page is recorded once per viewing. */
export type ViewerMetadata = { documentId: string; originalFilename: string; pageCount: number; viewingId?: string }
export type ViewerSearch = { pageCount: number; totalMatches: number; pages: {pageNumber:number; matchCount:number}[]; highlights?: {pageNumber:number;x:number;y:number;width:number;height:number}[] }
const viewerPath = (id: string) => '/api/v1/business/documents/' + encodeURIComponent(id) + '/viewer'
/** Small page previews (PNG data URLs), at most 24 pages per request. The server writes no audit event for them. */
export type ViewerPreviews = { documentId: string; pageCount: number; first: number; previews: { pageNumber: number; width: number; height: number; dataUrl: string }[] }
export const viewerPreviews = (id: string, first: number, count: number) => roleApi<ViewerPreviews>(viewerPath(id) + '/previews?first=' + first + '&count=' + count, 'business')
export const viewerMetadata = (id:string) => roleApi<ViewerMetadata>(viewerPath(id), 'business')
export const viewerPage = (id:string, page:number, scale=1, viewingId?:string) =>
  contentBinary(viewerPath(id) + '/pages/' + page + '?scale=' + scale + (viewingId ? '&viewingId=' + encodeURIComponent(viewingId) : ''), 'business')
export const viewerSearch = (id:string, q:string) =>
  roleApi<ViewerSearch>(viewerPath(id) + '/search?q=' + encodeURIComponent(q), 'business')

export type LiveDocumentActivity = { auditEventId:string; actorType:string; actorId:string; action:string; occurredAt:string; outcome:'SUCCESS'|'DENIED'|'FAILURE'; safeDetailsJson:string }
export const documentActivity = (id:string,includeViewEvents=false,limit=100) =>
  roleApi<LiveDocumentActivity[]>('/api/v1/business/documents/'+encodeURIComponent(id)+'/activity?includeViewEvents='+includeViewEvents+'&limit='+limit,'business')

export type LiveTag = { tagId:string; documentId:string; tag:string; createdBy:string; createdAt:string }
export type LiveComment = { commentId:string; documentId:string; comment:string; createdBy:string; createdAt:string }
const documentPath = (id:string) => '/api/v1/business/documents/'+encodeURIComponent(id)
export const documentTags = (id:string) => roleApi<LiveTag[]>(documentPath(id)+'/tags','business')
export const documentComments = (id:string) => roleApi<LiveComment[]>(documentPath(id)+'/comments','business')
export const createDocumentTag = (id:string,tag:string) => roleApi<LiveTag>(documentPath(id)+'/tags','business',{method:'POST',body:JSON.stringify({tag})})
export const createDocumentComment = (id:string,comment:string) => roleApi<LiveComment>(documentPath(id)+'/comments','business',{method:'POST',body:JSON.stringify({comment})})

export const secureDownload = (id:string) => contentBinary('/api/v1/business/documents/'+encodeURIComponent(id)+'/download','business')
export const recordPrintEvent = (id:string) => roleApi<void>('/api/v1/business/documents/'+encodeURIComponent(id)+'/print-event','business',{method:'POST'})
export type CorrectionRegion = {pageNumber:number;x:number;y:number;width:number;height:number}
export const permanentRedaction = (id:string,reason:string,regions:CorrectionRegion[]) =>
  roleApi<unknown>('/api/v1/business/documents/'+encodeURIComponent(id)+'/redactions','steward',{method:'POST',body:JSON.stringify({reason,confirmed:true,regions})})

export type RolePolicy = {roles:Record<string,Record<string,boolean>>;configurableCapabilities:string[]}
export const getRolePolicy = () => roleApi<RolePolicy>('/api/v1/admin/role-capabilities','businessAdmin')
export const saveRolePolicy = (roles:RolePolicy['roles']) => roleApi<RolePolicy>('/api/v1/admin/role-capabilities','businessAdmin',{method:'PUT',body:JSON.stringify({roles})})

export type RetentionPolicyOverview={defaultRetentionDays:number|null;indefinite:boolean;policyVersionId:string;version:number;policyName:string;description:string|null;effectiveFrom:string;createdBy:string;createdByDisplayName?:string|null;history:{policyVersionId:string;version:number;policyName:string;description:string|null;defaultRetentionDays:number|null;indefinite:boolean;effectiveFrom:string;effectiveUntil:string|null;createdBy:string;createdByDisplayName?:string|null;createdAt:string}[]}
export const getRetentionPolicy=()=>roleApi<RetentionPolicyOverview>('/api/v1/admin/retention-policy','businessAdmin')
export const activateRetentionPolicy=(policyName:string,description:string,days:number|null)=>roleApi<RetentionPolicyOverview>('/api/v1/admin/retention-policy','businessAdmin',{method:'PUT',body:JSON.stringify({policyName,description,defaultRetentionDays:days})})

export type AdminMetadataSource={sourceId:string;sourceKey:string;displayName:string;status:string}
export type AdminMetadataField={definitionId:string;sourceId:string;fieldKey:string;version:number;displayLabel:string;fieldType:string;required:boolean;businessIdentifier:boolean;searchable:boolean;normalization:'NONE'|'DIGITS_ONLY'|'UPPERCASE';searchMode:'EXACT'|'PREFIX'|'PARTIAL';active:boolean}
export const adminMetadataSources=()=>roleApi<AdminMetadataSource[]>('/api/v1/admin/metadata-sources','businessAdmin')
export const adminMetadataFields=(sourceId:string)=>roleApi<AdminMetadataField[]>('/api/v1/admin/ingestion-sources/'+encodeURIComponent(sourceId)+'/metadata-fields','businessAdmin')
export const updateBusinessMetadata=(sourceId:string,field:AdminMetadataField)=>roleApi<AdminMetadataField>('/api/v1/admin/metadata-sources/'+encodeURIComponent(sourceId)+'/fields/'+encodeURIComponent(field.fieldKey),'businessAdmin',{method:'PUT',body:JSON.stringify({displayLabel:field.displayLabel,businessIdentifier:field.businessIdentifier,searchable:field.searchable,normalization:field.normalization,searchMode:field.searchMode})})

/** The one or two fields that name a record (task 0009); an empty list means the default title. */
export const getRecordTitleFields=(sourceId:string)=>roleApi<string[]>('/api/v1/admin/metadata-sources/'+encodeURIComponent(sourceId)+'/record-title','businessAdmin')
export const saveRecordTitleFields=(sourceId:string,fieldKeys:string[])=>roleApi<string[]>('/api/v1/admin/metadata-sources/'+encodeURIComponent(sourceId)+'/record-title','businessAdmin',{method:'PUT',body:JSON.stringify({fieldKeys})})
export const getMetadataDisplayOrder=(sourceId:string)=>roleApi<string[]>('/api/v1/admin/metadata-sources/'+encodeURIComponent(sourceId)+'/display-order','businessAdmin')
export const saveMetadataDisplayOrder=(sourceId:string,fieldKeys:string[])=>roleApi<string[]>('/api/v1/admin/metadata-sources/'+encodeURIComponent(sourceId)+'/display-order','businessAdmin',{method:'PUT',body:JSON.stringify({fieldKeys})})

export type ItSource={sourceId:string;sourceKey:string;displayName:string;status:'ACTIVE'|'DISABLED';authType:'OAUTH2_CLIENT_CREDENTIALS'|'JWT_BEARER'|'MTLS';credentialReference:string;createdAt:string;updatedAt:string;limits?:{maxPdfBytes?:number|null;maxPagesPerPdf?:number|null;maxDocumentsPerSubmission?:number|null;maxAggregateSubmissionBytes?:number|null;incompleteAttentionHours?:number|null}}
/** Business search settings come with each field; Tenant IT reads them but cannot change them. */
export type ItField={fieldKey:string;displayLabel:string;fieldType:string;required:boolean;normalization:string;version:number;businessIdentifier?:boolean;searchable?:boolean;searchMode?:'EXACT'|'PREFIX'|'PARTIAL'}
export const itSources=()=>roleApi<ItSource[]>('/api/v1/admin/ingestion-sources','tenantIt')
/** The standard limits: what applies when a sending system sets none of its own (task 0019 follow-up). */
export type ItLimits=NonNullable<ItSource['limits']>
export const itStandardLimits=()=>roleApi<ItLimits>('/api/v1/admin/ingestion-sources/standard-limits','tenantIt')
export const itFields=(sourceId:string)=>roleApi<ItField[]>('/api/v1/admin/ingestion-sources/'+encodeURIComponent(sourceId)+'/source-fields','tenantIt')
export const itUpsertField=(sourceId:string,key:string,body:{displayLabel:string;fieldType:string;required:boolean;normalization:string;validationRules:Record<string,never>})=>roleApi<ItField>('/api/v1/admin/ingestion-sources/'+encodeURIComponent(sourceId)+'/source-fields/'+encodeURIComponent(key),'tenantIt',{method:'PUT',body:JSON.stringify(body)})
type OpsComponent={component:string;status:string;message:string;observedAt?:string|null}
export type OpSummary={overallStatus:string;observedAt:string;application:OpsComponent;database:OpsComponent;keyService:OpsComponent;processingFailureCount:number|null;stuckDocumentCount:number|null;attentionSubmissionCount:number|null;dataObservations:{section:string;status:string;message:string}[]}
export const opSummary=()=>roleApi<OpSummary>('/api/v1/operations/summary','platform')

export const itCreateSource=(body:{sourceKey:string;displayName:string;authType:ItSource['authType'];credentialReference:string})=>roleApi<ItSource>('/api/v1/admin/ingestion-sources','tenantIt',{method:'POST',body:JSON.stringify(body)})
export const itUpdateSource=(id:string,body:{displayName:string;status:ItSource['status'];authType:ItSource['authType'];credentialReference:string;limits?:ItSource['limits']})=>roleApi<ItSource>('/api/v1/admin/ingestion-sources/'+encodeURIComponent(id),'tenantIt',{method:'PUT',body:JSON.stringify(body)})
export type ItSourceSummary={submissionCount:number;documentCount:number;lastActivityAt:string|null;lastDocumentAvailableAt:string|null;lastProcessingFailureAt:string|null}
export const itSourceSummary=(id:string)=>roleApi<ItSourceSummary>('/api/v1/admin/ingestion-sources/'+encodeURIComponent(id)+'/operations','tenantIt')

export type LiveStorageVolume={volume:{volumeId:string;displayName:string;rootPath:string;state:string;priority:number;warningPercent:number;stopWritePercent:number};capacity?:{totalBytes:number|null;usableBytes:number|null;usedPercent:number|null;writable:boolean};observationError?:string|null;warningThresholdReached:boolean;stopWriteThresholdReached:boolean}
export const liveStorage=()=>roleApi<LiveStorageVolume[]>('/api/v1/admin/storage-volumes','tenantIt')
export type IdentityConnection={connectionId:string;displayName:string;providerId:string;issuerUri:string;browserClientId:string;apiAudience:string;status:string;configurationVersion:number;providerType?:string;updatedAt?:string|null;lastTestedAt?:string|null;lastTestStatus:string|null;lastTestMessage:string|null}
export type IdentityOverview={active:IdentityConnection|null;staged:IdentityConnection|null;rollbackCandidates:IdentityConnection[];activationSupported:boolean;activationMaxTestAgeSeconds:number}
export const identityOverview=()=>roleApi<IdentityOverview>('/api/v1/admin/enterprise-identity','tenantIt')
/** databaseSchema: checked once when Vero started. state CURRENT, OLDER, NEWER or UNREACHABLE; message is the plain sentence. */
export type SystemInfo={productName:string;version:string;status:string;activeProfiles:string[];timestamp:string;databaseSchema?:{state:string;message:string}}
export const systemInfo=(role:'tenantIt'|'platform'='tenantIt')=>roleApi<SystemInfo>('/api/v1/system/info',role)
export type OpsDetail=OpSummary & {storageVolumes:{volumeId:string;displayName:string;state:string;health:string;usedPercent:number|null;writable:boolean;warningThresholdReached:boolean;stopWriteThresholdReached:boolean;observationError:string|null;rootPath?:string;priority?:number;warningPercent?:number;stopWritePercent?:number;totalBytes?:number|null;usableBytes?:number|null;observedAt?:string|null}[];processingFailures:{documentId:string;submissionId:string;sourceId:string;errorCode:string;processingAttemptCount:number;ingestedAt:string;originalFilename?:string|null}[];stuckDocuments:{documentId:string;submissionId:string;status:string;stateSince:string;processingErrorCode:string|null;originalFilename?:string|null}[];attentionSubmissions:{submissionId:string;sourceSubmissionId:string;status:string;expectedDocumentCount:number;receivedDocumentCount:number;lastActivityAt:string;attentionHours:number;sourceId?:string}[];
  /** Task 0011: by submission ID, the record and sending system behind each problem. */
  problemRecords?:Record<string,{recordNumber:string|null;recordNumberLabel:string|null;recordTitle:string|null;sourceName:string|null}>}
/** Tenant IT reads the same summary for its read-only Problems tab. */
export const opsDetail=(role:'platform'|'tenantIt'='platform')=>roleApi<OpsDetail>('/api/v1/operations/summary',role)
export type OpsAuditEvent={auditEventId:string;occurredAt:string;action:string;outcome:string;actorId:string;actorType:string;targetType:string;targetId:string;correlationId:string|null;safeDetailsJson:string|null;
  /** Null for events before migration 020 (show actorId). */ actorDisplayName?:string|null;
  /** Document events: looked up when the list is read. */ recordNumber?:string|null;filename?:string|null}
export const opsAudit=(cursor?:string,filters?:{action?:string;outcome?:string;actorId?:string;actorType?:string;targetType?:string;targetId?:string;correlationId?:string;from?:string;to?:string;recordNumber?:string},limit=50)=>{const p=new URLSearchParams({limit:String(limit)});if(cursor)p.set('cursor',cursor);for(const [k,v] of Object.entries(filters??{}))if(v)p.set(k,v);return roleApi<{events:OpsAuditEvent[];nextCursor:string|null;hasMore:boolean}>('/api/v1/operations/audit-events?'+p.toString(),'platform')}
/** Task 0011 export: the server builds the CSV from the list's filters (plus the activity group and hidden routine codes) and records the export. */
export const opsAuditExport=(filters:Record<string,string|undefined>,actions?:string[],excludeActions?:string[])=>{const p=new URLSearchParams();for(const [k,v] of Object.entries(filters))if(v)p.set(k,v);if(actions?.length)p.set('actions',actions.join(','));if(excludeActions?.length)p.set('excludeActions',excludeActions.join(','));return contentBinary('/api/v1/operations/audit-events/export?'+p.toString(),'platform')}
/** The last automatic cleanup run since the server started (kept in memory; the server reports no schedule). */
export type OpsRetention={outcome:'SUCCESS'|'FAILED'|'NEVER_RUN'|string;startedAt:string|null;completedAt:string|null;candidates:number;deleted:number;failed:number;reasonCode:string|null}
export const opsRetention=()=>roleApi<OpsRetention>('/api/v1/operations/retention-status','platform')

/** Thresholds left out use the tenant's standard warning and stop levels. */
export const createStorageVolume=(v:{displayName:string;rootPath:string;state:string;priority:number;warningPercent?:number;stopWritePercent?:number})=>roleApi<LiveStorageVolume>('/api/v1/admin/storage-volumes','tenantIt',{method:'POST',body:JSON.stringify(v)})
/** Checks a folder before a volume is added (task 0010): writes and removes a test file, leaves nothing behind. */
export type FolderCheck={normalizedRootPath:string;writable:boolean;usableBytes:number|null;totalBytes:number|null;problem:string|null}
export const checkStorageFolder=(rootPath:string)=>roleApi<FolderCheck>('/api/v1/admin/storage-volumes/check','tenantIt',{method:'POST',body:JSON.stringify({rootPath})})
export const updateStorageVolume=(id:string,v:{displayName:string;state:string;priority:number;warningPercent:number;stopWritePercent:number})=>roleApi<LiveStorageVolume>('/api/v1/admin/storage-volumes/'+encodeURIComponent(id),'tenantIt',{method:'PUT',body:JSON.stringify(v)})
