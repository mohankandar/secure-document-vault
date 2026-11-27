import type { LicenceView } from './licenceWords'
import type { BusinessNotifications, EventChange, EventRow, ItNotifications, Recipient, ZoneSource } from './notificationWords'
import { ApiRequestError, roleApi, contentBinary, contentDownload, contentExport, type RegisteredExport, type SessionInfo, type SearchSchema, type SearchResponse, type BusinessRecord, type RecordSummary, type SearchSource, type SensitivityLevel } from '../api'
import type { Field, Source, RecordItem, Doc } from './model'
import type { SignInTestAnswer } from './signInTestWords'

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
/** How many records Home and Records ask for. */
export const RECENT_LIMIT = 8
export const recentRecords = (limit = RECENT_LIMIT) => roleApi<{ records: RecentRecord[] }>('/api/v1/business/me/recent-records?limit=' + limit, 'business')
export const record = (ref: string) => roleApi<BusinessRecord>('/api/v1/business/records/' + encodeURIComponent(ref), 'business')
export function toSource(s: SearchSource): Source {
  // Fail closed: anything but ACTIVE counts as paused, and a missing flag as holding no record.
  return { id:s.sourceId, key:s.sourceKey, name:s.displayName, status:s.status === 'ACTIVE' ? 'ACTIVE' : 'PAUSED', hasRecords:s.hasRecords === true, lastDeliveredAt:s.lastDeliveredAt ?? null, fields:s.fields.map(f=>({
    key:f.fieldKey, label:f.displayLabel, type:f.fieldType === 'DATE' ? 'DATE':'STRING',
    identifier:f.businessIdentifier, searchable:true, mode:f.searchMode, normalization:
    f.normalization === 'DIGITS_ONLY' || f.normalization === 'UPPERCASE' ? f.normalization : 'NONE', normalizationRaw:f.normalization, sensitive:f.sensitive === true, partialAsPrefix:f.partialAsPrefix === true
  } as Field)) }
}
const fields = (a: {displayLabel:string,value:string,sensitive?:boolean}[]) => a.map(f=>({ label:f.displayLabel,value:f.value,sensitive:f.sensitive === true }))
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
    ingestedAt:d.ingestedAt,expiresAt:d.expiresAt || '',level:d.effectiveSensitivityLevel ?? d.sensitivityLevel,ownLevel:d.sensitivityLevel,quarantined:d.quarantined === true}))
  // Task 0026: documents this person can't open come apart, with only a reference and a level.
  const locked=(r.lockedDocuments ?? []).map(d=>({id:d.documentId,level:d.effectiveSensitivityLevel}))
  // A record can have several incomplete submissions: add up the counts and list every missing document.
  const open=r.incompleteAttention?.submissions ?? []
  const incomplete=open.length?{expectedCount:open.reduce((n,s)=>n+s.expectedCount,0),receivedCount:open.reduce((n,s)=>n+s.receivedCount,0),missing:open.flatMap(s=>s.missingSourceDocumentIds ?? [])}:null
  return {ref:r.recordRef,sourceId:r.sourceId,identifiers:ids,metadata:fields(r.metadata),
    title:recordName(r.title,ids,r.recordRef),updatedAt:r.latestSubmissionAt,
    incomplete:incomplete?{expected:incomplete.expectedCount,received:incomplete.receivedCount,missing:incomplete.missing}:null,docs,locked,unavailable:(r.unavailableDocuments ?? []).map(d=>d.documentId),
    recordLevel:r.recordSensitivityLevel ?? null}
}

/** viewingId: this opening's ID; page requests send it back so each page is recorded once per viewing. */
export type ViewerMetadata = { documentId: string; originalFilename: string; pageCount: number; viewingId?: string }
export type ViewerSearch = { pageCount: number; totalMatches: number; pages: {pageNumber:number; matchCount:number}[]; highlights?: {pageNumber:number;x:number;y:number;width:number;height:number}[] }
const viewerPath = (id: string) => '/api/v1/business/documents/' + encodeURIComponent(id) + '/viewer'
/** Small page previews (PNG data URLs), at most 24 pages per request. They belong to a viewing (task 0063). */
export type ViewerPreviews = { documentId: string; pageCount: number; first: number; previews: { pageNumber: number; width: number; height: number; dataUrl: string }[] }
/** Task 0063: no room for it just now (busy): wait as the server says and ask again, a few times. */
async function againWhenBusy<T>(ask: () => Promise<T>): Promise<T> {
  for (let n = 0; ; n++) {
    try { return await ask() } catch (e) {
      if (n >= 4 || !(e instanceof ApiRequestError) || e.code !== 'SDV-SYS-018') throw e
      await new Promise((r) => window.setTimeout(r, Math.min(10, e.retryAfterSeconds ?? 5) * 1000))
    }
  }
}
export const viewerPreviews = (id: string, first: number, count: number, viewingId: string) => againWhenBusy(() => roleApi<ViewerPreviews>(viewerPath(id) + '/previews?first=' + first + '&count=' + count + '&viewingId=' + encodeURIComponent(viewingId), 'business'))
export const viewerMetadata = (id:string) => roleApi<ViewerMetadata>(viewerPath(id), 'business')
export const viewerPage = (id:string, page:number, scale:number, viewingId:string, printId?:string) =>
  againWhenBusy(() => contentBinary(viewerPath(id) + '/pages/' + page + '?scale=' + scale + '&viewingId=' + encodeURIComponent(viewingId) + (printId ? '&printId=' + encodeURIComponent(printId) : ''), 'business'))
export const viewerSearch = (id:string, q:string, viewingId:string) =>
  againWhenBusy(() => roleApi<ViewerSearch>(viewerPath(id) + '/search?q=' + encodeURIComponent(q) + '&viewingId=' + encodeURIComponent(viewingId), 'business'))

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

/** Task 0043: checked for being complete; a short file fails with SDV-DOC-009. */
export const secureDownload = (id:string) => contentDownload('/api/v1/business/documents/'+encodeURIComponent(id)+'/download','business')
/** Task 0071: printId gets a Highly Confidential document's pages in print quality, for 15 minutes. */
export const recordPrintEvent = (id:string) => roleApi<{printId:string}>('/api/v1/business/documents/'+encodeURIComponent(id)+'/print-event','business',{method:'POST'})
export type CorrectionRegion = {pageNumber:number;x:number;y:number;width:number;height:number}
/** Task 0066: the sealed originals of a document redacted on legal hold (Restricted Stewards; reading only). */
export type SealedOriginal = { sealedId: string; sealedAt: string }
const sealedPath = (id: string) => '/api/v1/business/documents/' + encodeURIComponent(id) + '/sealed-originals'
export const sealedOriginals = (id: string) => roleApi<SealedOriginal[]>(sealedPath(id), 'business')
export const openSealedOriginal = (id: string, sealedId: string) => roleApi<ViewerMetadata>(sealedPath(id) + '/' + encodeURIComponent(sealedId) + '/viewer', 'business')
export const sealedOriginalPage = (id: string, sealedId: string, page: number, viewingId: string) =>
  againWhenBusy(() => contentBinary(sealedPath(id) + '/' + encodeURIComponent(sealedId) + '/viewer/pages/' + page + '?scale=1.25&viewingId=' + encodeURIComponent(viewingId), 'business'))
/** status APPROVAL_REQUESTED: the document is on legal hold; a Business admin approves first (task 0066). */
export const permanentRedaction = (id:string,reason:string,regions:CorrectionRegion[]) =>
  roleApi<{status:string}>('/api/v1/business/documents/'+encodeURIComponent(id)+'/redactions','steward',{method:'POST',body:JSON.stringify({reason,confirmed:true,regions})})

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

/** Task 0077: the entity's case fields. locked: there are values, so type, identifier and sensitivity don't change. */
export type CaseField = { fieldKey: string; label: string; type: string; primary: boolean; sensitive: boolean; locked: boolean; active: boolean }
export type FieldImportLine = { fieldKey: string; label: string; outcome: 'CREATE' | 'UNCHANGED' | 'CONFLICT'; conflict: string | null; message: string | null }
export const caseFields = (role: 'businessAdmin' | 'tenantIt') => roleApi<CaseField[]>('/api/v1/admin/case-fields', role)
export const setFieldSensitive = (fieldKey: string, sensitive: boolean) => roleApi<CaseField>('/api/v1/admin/case-fields/' + encodeURIComponent(fieldKey) + '/sensitive', 'businessAdmin', { method: 'PUT', body: JSON.stringify({ sensitive }) })
export const exportFieldList = () => contentBinary('/api/v1/admin/case-fields/export', 'businessAdmin')
export const previewFieldList = (file: string) => roleApi<FieldImportLine[]>('/api/v1/admin/case-fields/import/preview', 'businessAdmin', { method: 'POST', body: JSON.stringify({ file }) })
export const importFieldList = (file: string) => roleApi<FieldImportLine[]>('/api/v1/admin/case-fields/import', 'businessAdmin', { method: 'POST', body: JSON.stringify({ file }) })

/** The one or two fields that name a record (task 0009); an empty list means the default title. */
export const getRecordTitleFields=(sourceId:string)=>roleApi<string[]>('/api/v1/admin/metadata-sources/'+encodeURIComponent(sourceId)+'/record-title','businessAdmin')
export const saveRecordTitleFields=(sourceId:string,fieldKeys:string[])=>roleApi<string[]>('/api/v1/admin/metadata-sources/'+encodeURIComponent(sourceId)+'/record-title','businessAdmin',{method:'PUT',body:JSON.stringify({fieldKeys})})
export const getMetadataDisplayOrder=(sourceId:string)=>roleApi<string[]>('/api/v1/admin/metadata-sources/'+encodeURIComponent(sourceId)+'/display-order','businessAdmin')
export const saveMetadataDisplayOrder=(sourceId:string,fieldKeys:string[])=>roleApi<string[]>('/api/v1/admin/metadata-sources/'+encodeURIComponent(sourceId)+'/display-order','businessAdmin',{method:'PUT',body:JSON.stringify({fieldKeys})})

export type ItSource={sourceId:string;sourceKey:string;displayName:string;status:'ACTIVE'|'DISABLED';authType:'OAUTH2_CLIENT_CREDENTIALS'|'JWT_BEARER'|'MTLS';credentialReference:string;createdAt:string;updatedAt:string;limits?:{maxPdfBytes?:number|null;maxPagesPerPdf?:number|null;maxDocumentsPerSubmission?:number|null;maxAggregateSubmissionBytes?:number|null;incompleteAttentionHours?:number|null}}
/** Business search settings come with each field; IT reads them but cannot change them. */
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
/** Task 0041 (owner answer 6): the login connection in use, read only; setup changes it. */
export type IdentityConnection={connectionId:string;providerId:string;providerType:string;issuerUri:string;browserClientId:string|null;apiAudience:string;status:string;configurationVersion:number}
export type IdentityOverview={active:IdentityConnection|null;changedBySetupOnly:boolean}
export const identityOverview=()=>roleApi<IdentityOverview>('/api/v1/admin/login-connection','tenantIt')
/** Task 0057: platform-core's read-only test sign-in of the connection in use. Changes nothing. */
export const testSignIn=()=>roleApi<SignInTestAnswer>('/api/v1/admin/login-connection/test','tenantIt',{method:'POST'})
/** Task 0041 (owner answer 5): the entities this sign-in may work in; current is null until one is chosen. */
export type SessionEntities={current:string|null;entities:{entityId:string;name:string}[];activatableSlots:string[]}
export const sessionEntities=()=>roleApi<SessionEntities>('/api/v1/session/entities','business')
/** Audited by the server (ENTITY_SWITCHED) in the entity switched to, which this request names. */
export const entitySwitched=(from:string|null)=>roleApi<void>('/api/v1/session/entity-switch','business',{method:'POST',body:JSON.stringify({from})})
/** databaseSchema: checked once when Vero started. state CURRENT, OLDER, NEWER or UNREACHABLE; message is the plain sentence. */
export type SystemInfo={productName:string;version:string;status:string;/** IT and Platform operations only (task 0071) */activeProfiles:string[]|null;timestamp:string;databaseSchema?:{state:string;message:string}}
export const systemInfo=(role:'tenantIt'|'platform'='tenantIt')=>roleApi<SystemInfo>('/api/v1/system/info',role)
export type OpsDetail=OpSummary & {storageVolumes:{volumeId:string;displayName:string;state:string;health:string;usedPercent:number|null;writable:boolean;warningThresholdReached:boolean;stopWriteThresholdReached:boolean;observationError:string|null;rootPath?:string;priority?:number;warningPercent?:number;stopWritePercent?:number;totalBytes?:number|null;usableBytes?:number|null;observedAt?:string|null}[];processingFailures:{documentId:string;submissionId:string;sourceId:string;errorCode:string;processingAttemptCount:number;ingestedAt:string;
  /** Task 0027 (0.4): never a file name; the effective level (null if unknown) and the page count instead. */ pageCount?:number|null;sensitivityLevel?:SensitivityLevel|null}[];stuckDocuments:{documentId:string;submissionId:string;status:string;stateSince:string;processingErrorCode:string|null;ingestedAt?:string;pageCount?:number|null;sensitivityLevel?:SensitivityLevel|null}[];attentionSubmissions:{submissionId:string;sourceSubmissionId:string;status:string;expectedDocumentCount:number;receivedDocumentCount:number;lastActivityAt:string;attentionHours:number;sourceId?:string}[];
  /** Task 0011: by submission ID, the record and sending system behind each problem. */
  problemRecords?:Record<string,{recordNumber:string|null;recordNumberLabel:string|null;sourceName:string|null}>;
  /** Task 0039: intake attempts with no known sending system (a failed sign-in, for example), last 7 days, newest first. Never token content. */
  unidentifiedIntakeAttemptCount?:number|null;unidentifiedIntakeAttempts?:UnidentifiedIntakeAttempt[];
  /** Task 0040 part 2: busy answers (503 SDV-SYS-018) from this Vero, the last hour and since it started. Vero-wide. */
  busyAnswers?:{lastHour:number;sinceStart:number;lastAt:string|null;retryAfterSeconds:number}|null}
/** address: the Vero address called, IDs blanked; callerAddress: the network address the request came from. */
/** Task 0039 part 2: one row per caller address, address and code per minute; count = requests, lastSeenAt = the last of them. */
export type UnidentifiedIntakeAttempt={attemptId:string;tenantId:string|null;occurredAt:string;lastSeenAt:string;count:number;callerAddress:string|null;method:string;address:string;status:number;code:string;reference:string}
/** IT reads the same summary for its read-only Problems tab. */
export const opsDetail=(role:'platform'|'tenantIt'='platform')=>roleApi<OpsDetail>('/api/v1/operations/summary',role)
export type OpsAuditEvent={auditEventId:string;occurredAt:string;action:string;outcome:string;actorId:string;actorType:string;targetType:string;targetId:string;correlationId:string|null;safeDetailsJson:string|null;
  /** Null for events before migration 020 (show actorId). */ actorDisplayName?:string|null;
  /** Document events: looked up when the list is read. recordNumber is the record's primary identifier. */ recordNumber?:string|null;
  /** Task 0027 (0.4): never a file name. The document instead: ID (support only), effective level, received date, pages. */ documentId?:string|null;sensitivityLevel?:SensitivityLevel|null;documentReceivedAt?:string|null;documentPageCount?:number|null}
/** sensitivityLevel (task 0030): only events that recorded this level for their document when they happened. */
export const opsAudit=(cursor?:string,filters?:{action?:string;outcome?:string;actorId?:string;actorType?:string;targetType?:string;targetId?:string;correlationId?:string;from?:string;to?:string;recordNumber?:string;sensitivityLevel?:string},limit=50)=>{const p=new URLSearchParams({limit:String(limit)});if(cursor)p.set('cursor',cursor);for(const [k,v] of Object.entries(filters??{}))if(v)p.set(k,v);return roleApi<{events:OpsAuditEvent[];nextCursor:string|null;hasMore:boolean}>('/api/v1/operations/audit-events?'+p.toString(),'platform')}
/** Task 0011 export: the server builds the CSV from the list's filters (plus the activity group and hidden routine codes) and records the export. */
export const opsAuditExport=(filters:Record<string,string|undefined>,actions?:string[],excludeActions?:string[])=>{const p=new URLSearchParams();for(const [k,v] of Object.entries(filters))if(v)p.set(k,v);if(actions?.length)p.set('actions',actions.join(','));if(excludeActions?.length)p.set('excludeActions',excludeActions.join(','));return contentBinary('/api/v1/operations/audit-events/export?'+p.toString(),'platform')}
/** The last automatic cleanup run since the server started (kept in memory; the server reports no schedule). */
/** nextRunAt (task 0023): when the next cleanup run is due, from the scheduler's own setting; null when the server can't say. */
export type OpsRetention={outcome:'SUCCESS'|'FAILED'|'NEVER_RUN'|string;startedAt:string|null;completedAt:string|null;candidates:number;deleted:number;failed:number;/** Task 0027: due documents the run kept because they are on legal hold. */ heldSkipped?:number;reasonCode:string|null;nextRunAt?:string|null}
/** Task 0074: what Vero depends on. cause and fix are null while a check is working. */
export type ServiceCheck={name:string;group:string;state:'WORKING'|'HAVING_PROBLEMS'|'DOWN';code:string;cause:string|null;fix:string|null;checkedAt:string}
export const serviceChecks=(role:'platform'|'tenantIt')=>roleApi<{checks:ServiceCheck[]}>('/api/v1/operations/checks',role)
export const diagnosticBundle=(role:'platform'|'tenantIt')=>contentBinary('/api/v1/operations/diagnostic-bundle',role)
/** Task 0075: the licence and this entity's use of it. */
export const licenceView=(role:'businessAdmin'|'tenantIt')=>roleApi<LicenceView>('/api/v1/admin/licence',role)
export const usageExport=(startAgain:boolean)=>contentBinary('/api/v1/admin/licence/usage-export?startAgain='+startAgain,'tenantIt','POST')
export const opsRetention=()=>roleApi<OpsRetention>('/api/v1/operations/retention-status','platform')

/** Thresholds left out use the tenant's standard warning and stop levels. */
export const createStorageVolume=(v:{displayName:string;rootPath:string;state:string;priority:number;warningPercent?:number;stopWritePercent?:number})=>roleApi<LiveStorageVolume>('/api/v1/admin/storage-volumes','tenantIt',{method:'POST',body:JSON.stringify(v)})
/** Checks a folder before a volume is added (task 0010): writes and removes a test file, leaves nothing behind. */
export type FolderCheck={normalizedRootPath:string;writable:boolean;usableBytes:number|null;totalBytes:number|null;problem:string|null}
export const checkStorageFolder=(rootPath:string)=>roleApi<FolderCheck>('/api/v1/admin/storage-volumes/check','tenantIt',{method:'POST',body:JSON.stringify({rootPath})})
export const updateStorageVolume=(id:string,v:{displayName:string;state:string;priority:number;warningPercent:number;stopWritePercent:number})=>roleApi<LiveStorageVolume>('/api/v1/admin/storage-volumes/'+encodeURIComponent(id),'tenantIt',{method:'PUT',body:JSON.stringify(v)})

// ---- Task 0029: sensitivity levels and requests (the server side is task 0028) ----

/** A raise applies at once (APPLIED, the new level); a lowering is a request (REQUESTED, the level stays). */
export type LevelChangeResult = { outcome: 'APPLIED' | 'REQUESTED'; level: SensitivityLevel; canStillOpen: boolean; requestId: string | null; requestExpiresAt: string | null }
export type LevelScope = 'document' | 'record'
const levelPath = (scope: LevelScope, id: string) => '/api/v1/business/' + (scope === 'document' ? 'documents/' : 'records/') + encodeURIComponent(id) + '/sensitivity'
export const raiseLevel = (scope: LevelScope, id: string, level: SensitivityLevel, reason: string) =>
  roleApi<LevelChangeResult>(levelPath(scope, id) + '/raise', 'business', { method: 'POST', body: JSON.stringify({ level, reason }) })
export const askToLowerLevel = (scope: LevelScope, id: string, level: SensitivityLevel, reason: string) =>
  roleApi<LevelChangeResult>(levelPath(scope, id) + '/lower-request', 'business', { method: 'POST', body: JSON.stringify({ level, reason }) })

/** REDACT_HELD (task 0066): permanently redact a document on legal hold. RELEASE_HOLD (task 0071): release a legal hold. */
export type AccessRequestKind = 'PRINT' | 'DOWNLOAD' | 'LOWER_LEVEL' | 'LOWER_RECORD_LEVEL' | 'REDACT_HELD' | 'RELEASE_HOLD'
export type AccessRequestStatus = 'PENDING' | 'APPROVED' | 'DECLINED' | 'USED' | 'EXPIRED'
/**
 * One of the signed-in Steward's own requests. expiresAt: while PENDING, when it expires undecided; once a print or
 * download is APPROVED, when its one use must happen by. A whole-record request names no document.
 */
export type MyRequest = { requestId: string; kind: AccessRequestKind; scope: 'DOCUMENT' | 'RECORD'; status: AccessRequestStatus; documentId: string | null; requestedLevel: SensitivityLevel | null; reason: string; requestedAt: string; decidedAt: string | null; expiresAt: string; usedAt: string | null;
  /** Task 0030 (0.3): the record, by its sending system and primary identifier; null when it can't be found, and in the answer to a new request. */ sourceId?: string | null; primaryIdentifier?: string | null; primaryIdentifierLabel?: string | null }
/** Stewards only (a Business user is refused with SDV-APR-015, so the screens don't ask). */
export const myRequests = () => roleApi<MyRequest[]>('/api/v1/business/access-requests', 'business')
/** A Restricted Steward asks to print or download one Highly Confidential document once. */
export const requestPrintOrDownload = (documentId: string, kind: 'PRINT' | 'DOWNLOAD', reason: string) =>
  roleApi<MyRequest>('/api/v1/business/documents/' + encodeURIComponent(documentId) + '/access-requests', 'business', { method: 'POST', body: JSON.stringify({ kind, reason }) })

/** Task 0029 (0.4): one level change with its reason, for Stewards and Business admins. from is null for a record's first level. */
export type LevelHistoryEntry = { changeId: string; scope: 'DOCUMENT' | 'RECORD'; direction: 'RAISED' | 'LOWERED'; from: SensitivityLevel | null; to: SensitivityLevel; reason: string; changedBy: string; changedAt: string; accessRequestId: string | null }
export const documentLevelHistory = (documentId: string) => roleApi<LevelHistoryEntry[]>(levelPath('document', documentId) + '/history', 'business')
export const recordLevelHistory = (recordRef: string) => roleApi<LevelHistoryEntry[]>(levelPath('record', recordRef) + '/history', 'business')

/**
 * Task 0029 (0.5): the level fields of a waiting request on the approvals list (the Approvals screen is task 0030).
 * ownLevel: the document's own level; recordLevel: its record's (null when none); levelAfter: the effective level once
 * a document lowering is approved; accessUnchanged: true when that is still the level now.
 */
export type PendingLevels = { level: SensitivityLevel | null; requestedLevel: SensitivityLevel | null; ownLevel: SensitivityLevel | null; recordLevel: SensitivityLevel | null; levelAfter: SensitivityLevel | null; accessUnchanged: boolean | null }
/** The approver's note for a lowering that changes nothing for who can open the document; null otherwise. */
export const approvalLevelNote = (r: PendingLevels, recordWord = 'contract') =>
  r.accessUnchanged === true && r.recordLevel ? `The ${recordWord} stays at ${LEVEL_NAMES[r.recordLevel]}, so who can open it won't change` : null
export const LEVEL_NAMES: Record<SensitivityLevel, string> = { STANDARD: 'Standard', CONFIDENTIAL: 'Confidential', HIGHLY_CONFIDENTIAL: 'Highly Confidential' }

// ---- Task 0030: Approvals and Legal holds for the Business admin (the server side is tasks 0027 and 0028) ----

/** "Document 2 of 6": only when two documents of the record can't be told apart by received date and pages. */
export type RequestPosition = { index: number; of: number }
/**
 * A waiting request as the Business admin sees it, and nothing more: the record's primary identifier, the document's
 * received date and page count (or the record's document count), the levels, who asked, why and when. Never a title,
 * file name or content. documentId is for support only: the screen doesn't show it.
 */
export type PendingRequest = PendingLevels & { requestId: string; kind: AccessRequestKind; scope: 'DOCUMENT' | 'RECORD'; primaryIdentifier: string | null; primaryIdentifierLabel: string | null; receivedAt: string | null; pageCount: number | null; documentCount: number | null; position: RequestPosition | null; requester: string; reason: string; requestedAt: string; documentId: string | null }
export type PendingGroup = { primaryIdentifier: string | null; primaryIdentifierLabel: string | null; requests: PendingRequest[] }
/** pendingExpiryDays (task 0031, 0.3): how long a request may wait undecided in this tenant. */
export type PendingList = { groups: PendingGroup[]; requestCount: number; pendingExpiryDays: number }
/** Oldest first, grouped by the record's primary identifier. Every Business admin of the tenant sees the same list. */
export const pendingRequests = () => roleApi<PendingList>('/api/v1/admin/access-requests', 'businessAdmin')
/**
 * Task 0031 (0.2): a decision by any Business admin of the tenant, for "Decided recently" (last 30 days, at most 50,
 * newest first). Never the reason or the requester. decidedBy: the admin's name, or their sign-in subject.
 */
export type DecidedRequest = { requestId: string; kind: AccessRequestKind; scope: 'DOCUMENT' | 'RECORD'; requestedLevel: SensitivityLevel | null; primaryIdentifier: string | null; primaryIdentifierLabel: string | null; decision: 'APPROVE' | 'DECLINE'; decidedBy: string; decidedAt: string }
export const decidedRecently = () => roleApi<DecidedRequest[]>('/api/v1/admin/access-requests/decided', 'businessAdmin')
export type DecisionResult = { requestId: string; status: AccessRequestStatus; grantExpiresAt: string | null }
export const decideRequest = (requestId: string, decision: 'approve' | 'decline') =>
  roleApi<DecisionResult>('/api/v1/admin/access-requests/' + encodeURIComponent(requestId) + '/' + decision, 'businessAdmin', { method: 'POST' })
/** outcome: APPROVED, DECLINED, REFUSED (with its code and message) or FAILED. */
export type BulkItem = { requestId: string; outcome: 'APPROVED' | 'DECLINED' | 'REFUSED' | 'FAILED'; code: string | null; message: string | null }
export type BulkResult = { results: BulkItem[]; decided: number; notDecided: number }
/** At most this many at once (the server's limit). Whole-record requests are never sent this way. */
export const BULK_MAX = 100
export const decideMany = (decision: 'APPROVE' | 'DECLINE', requestIds: string[]) =>
  roleApi<BulkResult>('/api/v1/admin/access-requests/decisions', 'businessAdmin', { method: 'POST', body: JSON.stringify({ decision, requestIds }) })

/** A document as the Business admin sees it for a hold: never a title, file name or content. documentId: support only. */
export type HoldDocument = { documentId: string; primaryIdentifier: string | null; primaryIdentifierLabel: string | null; sensitivityLevel: SensitivityLevel | null; receivedAt: string | null; pageCount: number | null; onHold: boolean; activeHoldId: string | null }
export type LegalHold = { holdId: string; documentId: string; primaryIdentifier: string | null; primaryIdentifierLabel: string | null; sensitivityLevel: SensitivityLevel | null; receivedAt: string | null; pageCount: number | null; reason: string; placedBy: string; placedByName: string | null; placedAt: string; releasedBy: string | null; releasedByName: string | null; releasedAt: string | null }
/** The documents of the record with this primary identifier (at most 100). */
export const holdDocuments = (identifier: string) => roleApi<HoldDocument[]>('/api/v1/admin/legal-holds/documents?identifier=' + encodeURIComponent(identifier), 'businessAdmin')
/** Holds in place, newest first (at most 200). */
export const activeHolds = () => roleApi<LegalHold[]>('/api/v1/admin/legal-holds', 'businessAdmin')
export const placeHold = (documentId: string, reason: string) => roleApi<LegalHold>('/api/v1/admin/legal-holds', 'businessAdmin', { method: 'POST', body: JSON.stringify({ documentId, reason }) })
/** Task 0071: a request that another Business admin decides in Approvals; not the one who placed the hold. */
export const requestHoldRelease = (holdId: string, reason: string) => roleApi<MyRequest>('/api/v1/admin/legal-holds/' + encodeURIComponent(holdId) + '/release', 'businessAdmin', { method: 'POST', body: JSON.stringify({ reason }) })
export type HoldReleaseWaiting = { holdId: string; requestId: string; requestedBy: string; requestedAt: string }
export const holdReleaseRequests = () => roleApi<HoldReleaseWaiting[]>('/api/v1/admin/legal-holds/release-requests', 'businessAdmin')

// ---- Task 0032: the Business admin's Reports tab (Daily intake) and the Stewards' intake notices ----

/** A delivery line: its request ID as the sending system sent it, its first time, its record's primary identifier, counts. */
/** failed: after receipt or at intake; failedCodes (task 0039): the codes of intake requests that failed on Vero's side. */
export type IntakeDeliveryLine = { requestId: string | null; receivedAt: string; receivedLocal: string; primaryIdentifier: string | null; documents: number; pages: number; duplicates: number; refused: number; refusedCodes: string[]; failed: number; failedCodes?: string[] }
export type IntakeSystemTotals = { deliveries: number; contracts: number; documents: number; pages: number; duplicates: number; refused: number; failed: number }
/** deliveries: the first linesPerSystem; moreDeliveries: the rest, which are in the CSV. */
export type IntakeSystemBlock = { sourceId: string; displayName: string; totals: IntakeSystemTotals; deliveries: IntakeDeliveryLine[]; moreDeliveries: number }
/** failed: after receipt plus (task 0039) failedAtIntake, the intake requests that failed on Vero's side (by code in failedCodes). */
export type IntakeTotals = { deliveries: number; sendingSystems: number; documents: number; pages: number; duplicates: number; contracts: number; newContracts: number; updatedContracts: number; refused: number; refusedCodes: { code: string; count: number }[]; failed: number; failedAtIntake?: number; failedCodes?: { code: string; count: number }[] }
/**
 * Counts only: no title, file name or name. from/to/today are days in the entity's time zone (timeZone, an IANA name);
 * timeZoneSource (task 0081): the entity's own zone, else the customer's, else none (UTC).
 */
export type IntakeReport = { from: string; to: string; today: string; timeZone: string; timeZoneSource: ZoneSource; sourceId: string | null; primaryIdentifierLabel: string | null; sendingSystems: { sourceId: string; displayName: string }[]; totals: IntakeTotals; systems: IntakeSystemBlock[]; linesPerSystem: number }
export type IntakeFilter = { from?: string; to?: string; sourceId?: string }
const intakeQuery = (f: IntakeFilter) => { const p = new URLSearchParams(); if (f.from) p.set('from', f.from); if (f.to) p.set('to', f.to); if (f.sourceId) p.set('sourceId', f.sourceId); const s = p.toString(); return s ? '?' + s : '' }
export const intakeReport = (f: IntakeFilter) => roleApi<IntakeReport>('/api/v1/admin/reports/intake' + intakeQuery(f), 'businessAdmin')
/** One line per document; the server records the export in the audit. */
export const intakeReportCsv = (f: IntakeFilter) => contentBinary('/api/v1/admin/reports/intake/export' + intakeQuery(f), 'businessAdmin')

/**
 * Task 0032 (0): a sending system sent an uncorrected copy of a corrected document; nothing was stored. Task 0033
 * (0.1): stored on the server; each steward sees their unread ones (unread: how many in all) until they mark them read.
 */
export type IntakeNotice = { noticeId: string; kind: 'UNCORRECTED_COPY_RECEIVED'; receivedAt: string; sendingSystem: string | null; primaryIdentifier: string | null; primaryIdentifierLabel: string | null; message: string }
export type IntakeNotices = { notices: IntakeNotice[]; unread: number }
export const intakeNotices = () => roleApi<IntakeNotices>('/api/v1/business/intake-notices', 'business')
/** Marks these notices read by this person (audited). */
export const markIntakeNoticesRead = (noticeIds: string[]) =>
  roleApi<{ marked: number }>('/api/v1/business/intake-notices/read', 'business', { method: 'POST', body: JSON.stringify({ noticeIds }) })

// ---- Task 0033: the Sensitivity report (Business admin, counts only) ----

export type SensitivityRequestKind = 'DOWNLOAD' | 'PRINT' | 'LOWER_LEVEL' | 'LOWER_RECORD_LEVEL'
export type SensitivityReport = {
  from: string; to: string; today: string; timeZone: string; timeZoneSource: ZoneSource; sourceId: string | null
  primaryIdentifierLabel: string | null; sendingSystems: { sourceId: string; displayName: string }[]
  /** Stored now, by effective level; recordsWithLevel: records (contracts) with a record level. */
  documents: { standard: number; confidential: number; highlyConfidential: number; total: number; recordsWithLevel: number; onLegalHold: number }
  /** Requests made in the period, by what became of them (judged now). */
  requests: { kind: SensitivityRequestKind; approved: number; declined: number; expired: number; waiting: number }[]
  levelChanges: { raisedBySteward: number; raisedBySendingSystem: number; lowered: number }
  /** roles: the role names the refusals recorded; empty when none was recorded. */
  attempts: { roles: string[]; attempts: number; people: number }[]
}
export const sensitivityReport = (f: IntakeFilter) => roleApi<SensitivityReport>('/api/v1/admin/reports/sensitivity' + intakeQuery(f), 'businessAdmin')
/** Every count, one line each; the server records the export in the audit. */
export const sensitivityReportCsv = (f: IntakeFilter) => contentBinary('/api/v1/admin/reports/sensitivity/export' + intakeQuery(f), 'businessAdmin')

// ---- Task 0053: the Storage and retention report (Business admin, counts only, worked out in the background) ----

export type StorageTotal = { level: SensitivityLevel | null; documents: number; pages: number; bytes: number }
export type StorageReport = {
  /** When the counts were worked out (UTC), how long that took, and how often they are worked out again. */
  countedAt: string; tookMs: number; recountMinutes: number; timeZone: string; timeZoneSource: ZoneSource
  sendingSystems: { sourceId: string; displayName: string }[]
  stored: { sourceId: string; sendingSystem: string; level: SensitivityLevel; documents: number; pages: number; bytes: number }[]
  totals: StorageTotal[]; total: StorageTotal
  /** The last 12 months, oldest first (YYYY-MM in the entity's time zone): received then, at their level now. */
  growth: { month: string; documents: number; pages: number; bytes: number; standard: number; confidential: number; highlyConfidential: number }[]
  received: { month: string; sourceId: string; sendingSystem: string; level: SensitivityLevel; documents: number; pages: number; bytes: number }[]
  /** Per retention rule; keptForever: never due (the within counts are null). */
  retention: { retentionDays: number | null; ruleName: string | null; keptForever: boolean; stored: number; within30: number | null; within60: number | null; within90: number | null }[]
  heldBack: { legalHold: number; quarantine: number; both: number; total: number }
}
export const storageReport = () => roleApi<StorageReport>('/api/v1/admin/reports/storage', 'businessAdmin')
/** Every count, one line each, with its reference and SHA-256 fingerprint; the server records the export in the audit. */
export const storageReportCsv = (): Promise<RegisteredExport> => contentExport('/api/v1/admin/reports/storage/export', 'businessAdmin')

// ---- Task 0054: the Activity report (Business admin, counts only, from the audit trail) ----

export type ActivityKind = 'OPENED' | 'PRINTED' | 'DOWNLOADED'
export type ActivityReport = {
  from: string; to: string; today: string; timeZone: string; timeZoneSource: ZoneSource
  /** The last day read from the counts kept; the rest of the period (today) comes from the audit trail now. */
  keptThrough: string | null
  /** roles empty: not recorded (before task 0054); levelNotRecorded: events without a level. */
  access: { kind: ActivityKind; roles: string[]; standard: number; confidential: number; highlyConfidential: number; levelNotRecorded: number; total: number }[]
  accessTotals: { kind: ActivityKind; standard: number; confidential: number; highlyConfidential: number; levelNotRecorded: number; total: number }[]
  refusals: { roles: string[]; level: number; quarantine: number; total: number }[]
  /** name: the sign-in name, as on the Audit page. */
  busiest: { name: string; roles: string[]; standard: number; confidential: number; highlyConfidential: number; total: number }[]
  approvals: { requested: number; approved: number; declined: number; expired: number; grantsUsed: number; grantsReturned: number }
}
const activityQuery = (f: { from?: string; to?: string }) => (f.from && f.to ? `?from=${encodeURIComponent(f.from)}&to=${encodeURIComponent(f.to)}` : '')
export const activityReport = (f: { from?: string; to?: string }) => roleApi<ActivityReport>('/api/v1/admin/reports/activity' + activityQuery(f), 'businessAdmin')
/** Every count, one line each, with its reference and SHA-256 fingerprint; the server records the export in the audit. */
export const activityReportCsv = (f: { from?: string; to?: string }): Promise<RegisteredExport> => contentExport('/api/v1/admin/reports/activity/export' + activityQuery(f), 'businessAdmin')

// ---- Task 0055: the Restrictions page (Business admin, read-only; records by primary identifier only) ----

export type RestrictedRecord = { identifierLabel: string | null; identifier: string | null; level: SensitivityLevel; setAt: string }
export type RestrictionChange = { changedBy: string; changedAt: string; scope: 'DOCUMENT' | 'RECORD'; fromLevel: string | null; toLevel: string; identifierLabel: string | null; identifier: string | null }
export type RestrictionsOverview = {
  /** When the documents were counted by level (the Storage and retention report's counts). */
  levelsCountedAt: string
  levels: { sendingSystem: string; confidential: number; highlyConfidential: number }[]
  confidential: number; highlyConfidential: number
  restrictedRecords: number; records: { items: RestrictedRecord[]; next: string | null }
  levelChanges: number; changes: { items: RestrictionChange[]; next: string | null }
  lowerings: { waiting: number; oldest: string | null }
  legalHolds: { holds: number; documents: number }
  quarantine: { incidents: number; documents: number; preparing: number }
}
const restrictionsPath = '/api/v1/admin/restrictions'
export const restrictions = () => roleApi<RestrictionsOverview>(restrictionsPath, 'businessAdmin')
export const restrictedRecords = (after: string) => roleApi<{ items: RestrictedRecord[]; next: string | null }>(restrictionsPath + '/records?after=' + encodeURIComponent(after), 'businessAdmin')
export const restrictionChanges = (after: string) => roleApi<{ items: RestrictionChange[]; next: string | null }>(restrictionsPath + '/level-changes?after=' + encodeURIComponent(after), 'businessAdmin')

// ---- Task 0049: quarantine screens (the server side is task 0048) ----

/** An incident in the list: documentsInForce is how many are still quarantined; never a reason here. */
/** Task 0051: preparing while the background job writes the rows of a filter incident (preparedCount of expectedCount looked at). */
export type QuarantineIncidentSummary = { incidentId: string; name: string; scope: 'DOCUMENT' | 'FILTER'; status: 'OPEN' | 'RELEASED'; createdByName: string | null; createdAt: string; closedAt: string | null; documentsInForce: number; releaseRequested: boolean; preparing: boolean; preparedCount: number; expectedCount: number | null; /** task 0052: an approved whole release runs in the background */ releasing: boolean; documentCount: number; releasedCount: number }
/** One quarantined document as a reviewer sees it: never a title, file name or content. */
export type QuarantinedDocument = { quarantineId: string; documentId: string; primaryIdentifierLabel: string | null; primaryIdentifier: string | null; level: SensitivityLevel | null; receivedAt: string | null; pageCount: number | null; quarantinedByName: string | null; quarantinedAt: string; inForce: boolean; releasedByName: string | null; releasedAt: string | null }
/** hiddenCount: documents at levels this person can't open, counted and never shown. */
export type QuarantineIncident = { incidentId: string; name: string; reason: string; scope: 'DOCUMENT' | 'FILTER'; status: 'OPEN' | 'RELEASED'; createdByName: string | null; createdAt: string; closedAt: string | null; documentsInForce: number; documentCount: number; hiddenCount: number; documents: QuarantinedDocument[]; pendingRequestId: string | null; pendingRequestedByName: string | null; pendingExpiresAt: string | null; preparing: boolean; preparedCount: number; expectedCount: number | null; /** task 0052: documents is one page of at most this many */ listLimit: number; releasing: boolean; releasedCount: number; /** task 0052: where the next page starts; null on the last page */ next: string | null;
  /** task 0071: every release of a single document, newest first (at most 200); only for who started the incident */ singleReleases: { releasedByName: string | null; releasedAt: string; primaryIdentifier: string | null }[] | null }
/** The filter: received days (YYYY-MM-DD, whole UTC days, both needed), a sending system, field values (exact). */
export type QuarantineFilter = { receivedFrom: string; receivedTo: string; sourceId?: string; fields?: Record<string, string> }
/** Task 0051: no limit; the count alone. */
export type QuarantineCount = { count: number }
const quarantinePath = '/api/v1/quarantine'
export const quarantineIncidents = () => roleApi<QuarantineIncidentSummary[]>(quarantinePath + '/incidents', 'steward')
/** Task 0052: a page at a time; after is the previous page's next (omitted: the first page). */
export const quarantineIncident = (id: string, after?: string | null) => roleApi<QuarantineIncident>(quarantinePath + '/incidents/' + encodeURIComponent(id) + (after ? '?after=' + encodeURIComponent(after) : ''), 'steward')
/** Into a new incident (name and reason) or an open one (incidentId; no reason needed). */
export const quarantineDocument = (documentId: string, into: { incidentId: string } | { name: string; reason: string }) =>
  roleApi<QuarantineIncident>(quarantinePath + '/documents', 'steward', { method: 'POST', body: JSON.stringify({ documentId, ...into }) })
export const releaseQuarantinedDocument = (documentId: string, reason: string) =>
  roleApi<QuarantinedDocument>(quarantinePath + '/documents/' + encodeURIComponent(documentId) + '/release', 'steward', { method: 'POST', body: JSON.stringify({ reason }) })
const filterBody = (f: QuarantineFilter) => ({ receivedFrom: f.receivedFrom, receivedTo: f.receivedTo, sourceId: f.sourceId || undefined,
  fields: Object.fromEntries(Object.entries(f.fields ?? {}).map(([k, v]) => [k, v.trim()]).filter(([, v]) => v.length > 0)) })
/** Restricted steward only: how many documents the filter matches now. */
export const countQuarantineFilter = (f: QuarantineFilter) => roleApi<QuarantineCount>(quarantinePath + '/filter/count', 'restricted', { method: 'POST', body: JSON.stringify(filterBody(f)) })
/** Quarantines exactly expectedCount documents, or nothing (SDV-QUA-009 when the count changed). */
export const quarantineByFilter = (f: QuarantineFilter, expectedCount: number, name: string, reason: string) =>
  roleApi<QuarantineIncident>(quarantinePath + '/filter', 'restricted', { method: 'POST', body: JSON.stringify({ ...filterBody(f), expectedCount, name, reason }) })
export const requestIncidentRelease = (incidentId: string, reason: string) =>
  roleApi<QuarantineIncident>(quarantinePath + '/incidents/' + encodeURIComponent(incidentId) + '/release-request', 'steward', { method: 'POST', body: JSON.stringify({ reason }) })

/** what: the server's words for the action ("Opened", "Printed", "Downloaded"). */
export type ExposureEntry = { action: string; what: string; person: string; at: string }
export type ExposureDocument = { documentId: string; primaryIdentifierLabel: string | null; primaryIdentifier: string | null; level: SensitivityLevel | null; receivedAt: string | null; pageCount: number | null; quarantinedAt: string; inForce: boolean; exposure: ExposureEntry[] }
export type ExposureReport = { incidentId: string; name: string; status: 'OPEN' | 'RELEASED'; createdAt: string; standard: number; confidential: number; highlyConfidential: number; hiddenCount: number; documents: ExposureDocument[]; /** all the documents counted; task 0052: documents is one page (the CSV has them all) */ documentCount: number; next: string | null; listLimit: number }
export const quarantineExposure = (incidentId: string, after?: string | null) => roleApi<ExposureReport>(quarantinePath + '/incidents/' + encodeURIComponent(incidentId) + '/exposure' + (after ? '?after=' + encodeURIComponent(after) : ''), 'steward')
/** The CSV with its reference and SHA-256 fingerprint; the server records the export in the audit. Task 0052: it streams, so the fingerprint is worked out here from the file received (the audit has the server's). */
export const quarantineExposureCsv = (incidentId: string): Promise<RegisteredExport> => contentExport(quarantinePath + '/incidents/' + encodeURIComponent(incidentId) + '/exposure/export', 'steward')

/** A whole-incident release waiting for a Business admin: counts, levels and dates; never a reason. */
export type IncidentReleaseRequest = { requestId: string; incidentId: string; incidentName: string; documentCount: number; standard: number; confidential: number; highlyConfidential: number; incidentCreatedAt: string; firstReceivedAt: string | null; lastReceivedAt: string | null; requestedByName: string; requestedAt: string; expiresAt: string; status: string; decidedByName: string | null; decidedAt: string | null }
export const incidentReleaseRequests = () => roleApi<IncidentReleaseRequest[]>('/api/v1/admin/quarantine-releases', 'businessAdmin')
/** One at a time: there is no bulk decision for these. */
export const decideIncidentRelease = (requestId: string, decision: 'approve' | 'decline') =>
  roleApi<IncidentReleaseRequest>('/api/v1/admin/quarantine-releases/' + encodeURIComponent(requestId) + '/' + decision, 'businessAdmin', { method: 'POST' })

// ---- Task 0050: the sealed audit report (Business admin and IT admin) ----

/** A report as the register lists it: from and to are the period's start and its end (the day after the last day, UTC). */
export type AuditReport = { reportId: string; generatedBy: string; generatedAt: string; from: string; to: string; filters: Record<string, string>; rowCount: number; fingerprint: string }
/** from and to: days (YYYY-MM-DD, UTC), both included; the rest are the Audit list's filters, each optional. */
export type AuditReportRequest = { from: string; to: string; outcome?: string; recordNumber?: string; sensitivityLevel?: string }
export type AuditReportVerification = { matches: boolean; fingerprint: string; report: AuditReport | null }
const auditReportPath = '/api/v1/audit-reports'
export const auditReports = () => roleApi<AuditReport[]>(auditReportPath, 'businessAdmin')
/** Generates, registers and audits the report; the files are downloaded from it afterwards. */
export const generateAuditReport = (r: AuditReportRequest) => roleApi<AuditReport>(auditReportPath, 'businessAdmin', { method: 'POST', body: JSON.stringify({
  from: r.from, to: r.to, outcome: r.outcome || undefined, recordNumber: r.recordNumber?.trim() || undefined, sensitivityLevel: r.sensitivityLevel || undefined }) })
/** The sealed CSV, exactly as generated, or its PDF; each copy is audited. */
export const auditReportFile = (reportId: string, format: 'csv' | 'pdf'): Promise<RegisteredExport> => contentExport(auditReportPath + '/' + encodeURIComponent(reportId) + '/' + format, 'businessAdmin')
export const verifyAuditReport = (fingerprint: string) => roleApi<AuditReportVerification>(auditReportPath + '/verify', 'businessAdmin', { method: 'POST', body: JSON.stringify({ fingerprint }) })

/** Task 0081: notifications. IT: channels, technical events and the customer's time zone; Business Admin: this entity's events. */
const ntfPath = '/api/v1/admin/notifications'
const put = (body: unknown): RequestInit => ({ method: 'PUT', body: JSON.stringify(body) })
export const itNotifications = () => roleApi<ItNotifications>(ntfPath + '/it', 'tenantIt')
export const saveAppAddress = (appAddress: string) => roleApi<ItNotifications>(ntfPath + '/it', 'tenantIt', put({ appAddress }))
export const itNotificationEvents = () => roleApi<EventRow[]>(ntfPath + '/it/events', 'tenantIt')
export const saveItNotificationEvent = (id: string, change: EventChange) => roleApi<EventRow>(ntfPath + '/it/events/' + encodeURIComponent(id), 'tenantIt', put(change))
export const saveCustomerZone = (timeZone: string) => roleApi<ItNotifications>(ntfPath + '/it/time-zone', 'tenantIt', put({ timeZone }))
export const itRecipients = () => roleApi<Recipient[]>(ntfPath + '/it/recipients', 'tenantIt')
export const addItRecipient = (userId: string, email: string) => roleApi<Recipient>(ntfPath + '/it/recipients', 'tenantIt', { method: 'POST', body: JSON.stringify({ userId, email }) })
export const removeItRecipient = (recipientId: string) => roleApi<void>(ntfPath + '/it/recipients/' + encodeURIComponent(recipientId), 'tenantIt', { method: 'DELETE' })
export const businessNotifications = () => roleApi<BusinessNotifications>(ntfPath + '/business', 'businessAdmin')
export const saveBusinessNotificationEvent = (id: string, change: EventChange) => roleApi<EventRow>(ntfPath + '/business/events/' + encodeURIComponent(id), 'businessAdmin', put(change))
/** An empty time zone clears the entity's own, so it follows the customer's. */
export const saveEntityZone = (timeZone: string) => roleApi<BusinessNotifications>(ntfPath + '/business/time-zone', 'businessAdmin', put({ timeZone }))
export const businessRecipients = () => roleApi<Recipient[]>(ntfPath + '/business/recipients', 'businessAdmin')
export const addBusinessRecipient = (role: string, userId: string, email: string) => roleApi<Recipient>(ntfPath + '/business/recipients', 'businessAdmin', { method: 'POST', body: JSON.stringify({ role, userId, email }) })
export const removeBusinessRecipient = (recipientId: string) => roleApi<void>(ntfPath + '/business/recipients/' + encodeURIComponent(recipientId), 'businessAdmin', { method: 'DELETE' })

// Task 0082: the page of an Approve or Decline link; the token goes in the body, never in an address.
type LinkAnswer = import('./approvalLinkWords').LinkView & { request: PendingRequest | null }
export const approvalLinkOpen = (token: string) => roleApi<LinkAnswer>('/api/v1/approval-links/open', 'businessAdmin', { method: 'POST', body: JSON.stringify({ token }) })
export const approvalLinkDecide = (token: string) => roleApi<LinkAnswer>('/api/v1/approval-links/decide', 'businessAdmin', { method: 'POST', body: JSON.stringify({ token }) })
