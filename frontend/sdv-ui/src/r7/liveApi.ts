import { roleApi, contentBinary, type SessionInfo, type SearchSchema, type SearchResponse, type BusinessRecord, type RecordSummary, type SearchSource } from '../api'
import type { Field, Source, RecordItem, Doc } from './data'

export const session = () => roleApi<SessionInfo>('/api/v1/session', 'business')
export const schema = () => roleApi<SearchSchema>('/api/v1/business/search-schema', 'business')
export const search = (sourceId: string, criteria: Record<string,string>, from: string, to: string, page: number) =>
  roleApi<SearchResponse>('/api/v1/business/search', 'business', { method: 'POST', body: JSON.stringify({
    sourceId, criteria: Object.fromEntries(Object.entries(criteria).map(([key,value]) => [key,value.trim()]).filter(([,value]) => value.length > 0)), lastUpdatedFrom: from ? new Date(from + 'T00:00:00Z').toISOString() : null,
    lastUpdatedTo: to ? new Date(to + 'T23:59:59.999Z').toISOString() : null, page, pageSize: 25
  }) })
export const record = (ref: string) => roleApi<BusinessRecord>('/api/v1/business/records/' + encodeURIComponent(ref), 'business')
export function toSource(s: SearchSource): Source {
  return { id:s.sourceId, key:s.sourceKey, name:s.displayName, status:'ACTIVE', authType:'OAUTH2_CLIENT_CREDENTIALS', credentialRef:'', createdAt:'', updatedAt:'', submissions:0, documents:0, lastActivityAt:'', fields:s.fields.map(f=>({
    key:f.fieldKey, label:f.displayLabel, type:f.fieldType === 'DATE' ? 'DATE':'STRING',
    identifier:f.businessIdentifier, searchable:true, mode:f.searchMode, normalization:
    f.normalization === 'DIGITS_ONLY' || f.normalization === 'UPPERCASE' ? f.normalization : 'NONE', required:false
  } as Field)) }
}
const fields = (a: {displayLabel:string,value:string}[]) => a.map(f=>({ label:f.displayLabel,value:f.value }))
export function toSummary(r: RecordSummary, sourceId:string):RecordItem {
  const ids=fields(r.identifiers), metadata=fields(r.metadataPreview)
  return { ref:r.recordRef,sourceId,identifiers:ids,metadata,title:ids.map(x=>x.value).join(' · ') || r.recordRef,
    updatedAt:r.latestSubmissionAt,submissions:r.submissionCount,incomplete:r.incomplete?{submissionId:'',expected:0,received:0,since:r.latestSubmissionAt}:null,
    docs:[] }
}
export function toRecord(r: BusinessRecord):RecordItem {
  const ids=fields(r.identifiers)
  const docs:Doc[]=r.documents.map(d=>({id:d.documentId,submissionId:d.submissionId,filename:d.originalFilename,kind:d.originalFilename,pages:0,
    ingestedAt:d.ingestedAt,availableAt:d.availableAt || d.ingestedAt,expiresAt:d.expiresAt || '',
    sizeKb:0,tags:[],comments:[],activity:[],redactions:[]}))
  const incomplete=r.incompleteAttention?.submissions?.[0]
  return {ref:r.recordRef,sourceId:r.sourceId,identifiers:ids,metadata:fields(r.metadata),
    title:ids.map(x=>x.value).join(' · ') || r.recordRef,updatedAt:r.latestSubmissionAt,submissions:r.submissionCount,
    incomplete:incomplete?{submissionId:incomplete.submissionId,expected:incomplete.expectedCount,received:incomplete.receivedCount,since:r.latestSubmissionAt}:null,docs}
}

export type ViewerMetadata = { documentId: string; originalFilename: string; pageCount: number }
export type ViewerSearch = { pageCount: number; totalMatches: number; pages: {pageNumber:number; matchCount:number}[]; highlights?: {pageNumber:number;x:number;y:number;width:number;height:number}[] }
const viewerPath = (id: string) => '/api/v1/business/documents/' + encodeURIComponent(id) + '/viewer'
export const viewerMetadata = (id:string) => roleApi<ViewerMetadata>(viewerPath(id), 'business')
export const viewerPage = (id:string, page:number, scale=1) =>
  contentBinary(viewerPath(id) + '/pages/' + page + '?scale=' + scale, 'business')
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

export type RetentionPolicyOverview={defaultRetentionDays:number|null;indefinite:boolean;policyVersionId:string;version:number;policyName:string;description:string|null;effectiveFrom:string;createdBy:string;history:{policyVersionId:string;version:number;policyName:string;description:string|null;defaultRetentionDays:number|null;indefinite:boolean;effectiveFrom:string;effectiveUntil:string|null;createdBy:string;createdAt:string}[]}
export const getRetentionPolicy=()=>roleApi<RetentionPolicyOverview>('/api/v1/admin/retention-policy','businessAdmin')
export const activateRetentionPolicy=(policyName:string,description:string,days:number|null)=>roleApi<RetentionPolicyOverview>('/api/v1/admin/retention-policy','businessAdmin',{method:'PUT',body:JSON.stringify({policyName,description,defaultRetentionDays:days})})

export type AdminMetadataSource={sourceId:string;sourceKey:string;displayName:string;status:string}
export type AdminMetadataField={definitionId:string;sourceId:string;fieldKey:string;version:number;displayLabel:string;fieldType:string;required:boolean;businessIdentifier:boolean;searchable:boolean;normalization:'NONE'|'DIGITS_ONLY'|'UPPERCASE';searchMode:'EXACT'|'PREFIX'|'PARTIAL';active:boolean}
export const adminMetadataSources=()=>roleApi<AdminMetadataSource[]>('/api/v1/admin/metadata-sources','businessAdmin')
export const adminMetadataFields=(sourceId:string)=>roleApi<AdminMetadataField[]>('/api/v1/admin/ingestion-sources/'+encodeURIComponent(sourceId)+'/metadata-fields','businessAdmin')
export const updateBusinessMetadata=(sourceId:string,field:AdminMetadataField)=>roleApi<AdminMetadataField>('/api/v1/admin/metadata-sources/'+encodeURIComponent(sourceId)+'/fields/'+encodeURIComponent(field.fieldKey),'businessAdmin',{method:'PUT',body:JSON.stringify({displayLabel:field.displayLabel,businessIdentifier:field.businessIdentifier,searchable:field.searchable,normalization:field.normalization,searchMode:field.searchMode})})

export const getMetadataDisplayOrder=(sourceId:string)=>roleApi<string[]>('/api/v1/admin/metadata-sources/'+encodeURIComponent(sourceId)+'/display-order','businessAdmin')
export const saveMetadataDisplayOrder=(sourceId:string,fieldKeys:string[])=>roleApi<string[]>('/api/v1/admin/metadata-sources/'+encodeURIComponent(sourceId)+'/display-order','businessAdmin',{method:'PUT',body:JSON.stringify({fieldKeys})})

export type ItSource={sourceId:string;sourceKey:string;displayName:string;status:'ACTIVE'|'DISABLED';authType:'OAUTH2_CLIENT_CREDENTIALS'|'JWT_BEARER'|'MTLS';credentialReference:string;createdAt:string;updatedAt:string;limits?:{maxPdfBytes?:number|null;maxPagesPerPdf?:number|null;maxDocumentsPerSubmission?:number|null;maxAggregateSubmissionBytes?:number|null;incompleteAttentionHours?:number|null}}
export type ItField={fieldKey:string;displayLabel:string;fieldType:string;required:boolean;normalization:string;version:number}
export const itSources=()=>roleApi<ItSource[]>('/api/v1/admin/ingestion-sources','tenantIt')
export const itFields=(sourceId:string)=>roleApi<ItField[]>('/api/v1/admin/ingestion-sources/'+encodeURIComponent(sourceId)+'/source-fields','tenantIt')
export const itUpsertField=(sourceId:string,key:string,body:{displayLabel:string;fieldType:string;required:boolean;normalization:string;validationRules:Record<string,never>})=>roleApi<ItField>('/api/v1/admin/ingestion-sources/'+encodeURIComponent(sourceId)+'/source-fields/'+encodeURIComponent(key),'tenantIt',{method:'PUT',body:JSON.stringify(body)})
export type OpSummary={overallStatus:string;observedAt:string;application:{component:string;status:string;message:string};database:{component:string;status:string;message:string};keyService:{component:string;status:string;message:string};processingFailureCount:number|null;stuckDocumentCount:number|null;attentionSubmissionCount:number|null;dataObservations:{section:string;status:string;message:string}[]}
export const opSummary=()=>roleApi<OpSummary>('/api/v1/operations/summary','platform')

export const itCreateSource=(body:{sourceKey:string;displayName:string;authType:ItSource['authType'];credentialReference:string})=>roleApi<ItSource>('/api/v1/admin/ingestion-sources','tenantIt',{method:'POST',body:JSON.stringify(body)})
export const itUpdateSource=(id:string,body:{displayName:string;status:ItSource['status'];authType:ItSource['authType'];credentialReference:string;limits?:ItSource['limits']})=>roleApi<ItSource>('/api/v1/admin/ingestion-sources/'+encodeURIComponent(id),'tenantIt',{method:'PUT',body:JSON.stringify(body)})
export type ItSourceSummary={submissionCount:number;documentCount:number;lastActivityAt:string|null;lastDocumentAvailableAt:string|null;lastProcessingFailureAt:string|null}
export const itSourceSummary=(id:string)=>roleApi<ItSourceSummary>('/api/v1/admin/ingestion-sources/'+encodeURIComponent(id)+'/operations','tenantIt')

export type LiveStorageVolume={volume:{volumeId:string;displayName:string;rootPath:string;state:string;priority:number;warningPercent:number;stopWritePercent:number};capacity?:{totalBytes:number|null;usableBytes:number|null;usedPercent:number|null;writable:boolean};observationError?:string|null;warningThresholdReached:boolean;stopWriteThresholdReached:boolean}
export const liveStorage=()=>roleApi<LiveStorageVolume[]>('/api/v1/admin/storage-volumes','tenantIt')
export type IdentityConnection={connectionId:string;displayName:string;providerId:string;issuerUri:string;browserClientId:string;apiAudience:string;status:string;configurationVersion:number;lastTestStatus:string|null;lastTestMessage:string|null}
export type IdentityOverview={active:IdentityConnection|null;staged:IdentityConnection|null;rollbackCandidates:IdentityConnection[];activationSupported:boolean;activationMaxTestAgeSeconds:number}
export const identityOverview=()=>roleApi<IdentityOverview>('/api/v1/admin/enterprise-identity','tenantIt')
export const systemInfo=()=>roleApi<{productName:string;version:string;status:string;activeProfiles:string[];timestamp:string}>('/api/v1/system/info','tenantIt')
export type OpsDetail=OpSummary & {storageVolumes:{volumeId:string;displayName:string;state:string;health:string;usedPercent:number|null;writable:boolean}[];processingFailures:Record<string,unknown>[];stuckDocuments:Record<string,unknown>[];attentionSubmissions:Record<string,unknown>[]}
export const opsDetail=()=>roleApi<OpsDetail>('/api/v1/operations/summary','platform')
export const opsAudit=(cursor?:string)=>roleApi<{events:{auditEventId:string;occurredAt:string;action:string;outcome:string;actorId:string;targetType:string;targetId:string}[];nextCursor:string|null;hasMore:boolean}>('/api/v1/operations/audit-events?limit=50'+(cursor?'&cursor='+encodeURIComponent(cursor):''),'platform')
export const opsRetention=()=>roleApi<Record<string,unknown>>('/api/v1/operations/retention-status','platform')

export const createStorageVolume=(v:{displayName:string;rootPath:string;state:string;priority:number;warningPercent:number;stopWritePercent:number})=>roleApi<LiveStorageVolume>('/api/v1/admin/storage-volumes','tenantIt',{method:'POST',body:JSON.stringify(v)})
export const updateStorageVolume=(id:string,v:{displayName:string;state:string;priority:number;warningPercent:number;stopWritePercent:number})=>roleApi<LiveStorageVolume>('/api/v1/admin/storage-volumes/'+encodeURIComponent(id),'tenantIt',{method:'PUT',body:JSON.stringify(v)})
export const saveIdentityDraft=(v:{providerId:string;displayName:string;issuerUri:string;browserClientId:string;apiAudience:string})=>roleApi<IdentityOverview>('/api/v1/admin/enterprise-identity/draft','tenantIt',{method:'PUT',body:JSON.stringify(v)})
export const testIdentityDraft=()=>roleApi<IdentityOverview>('/api/v1/admin/enterprise-identity/draft/test','tenantIt',{method:'POST'})
export const activateIdentityDraft=(connectionId:string,expectedConfigurationVersion:number,confirmation:string)=>roleApi<IdentityOverview>('/api/v1/admin/enterprise-identity/draft/activate','tenantIt',{method:'POST',body:JSON.stringify({connectionId,expectedConfigurationVersion,confirmation})})
export const rollbackIdentity=(connectionId:string,expectedConfigurationVersion:number,confirmation:string)=>roleApi<IdentityOverview>('/api/v1/admin/enterprise-identity/rollback','tenantIt',{method:'POST',body:JSON.stringify({connectionId,expectedConfigurationVersion,confirmation})})
