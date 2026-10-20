import { roleApi, contentBinary, type SessionInfo, type SearchSchema, type SearchResponse, type BusinessRecord, type RecordSummary, type SearchSource } from '../api'
import type { Field, Source, RecordItem, Doc } from './data'

export const session = () => roleApi<SessionInfo>('/api/v1/session', 'business')
export const schema = () => roleApi<SearchSchema>('/api/v1/business/search-schema', 'business')
export const search = (sourceId: string, criteria: Record<string,string>, from: string, to: string, page: number) =>
  roleApi<SearchResponse>('/api/v1/business/search', 'business', { method: 'POST', body: JSON.stringify({
    sourceId, criteria, lastUpdatedFrom: from ? new Date(from + 'T00:00:00Z').toISOString() : null,
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
export type ViewerSearch = { pageCount: number; totalMatches: number; pages: {pageNumber:number; matchCount:number}[] }
const viewerPath = (id: string) => '/api/v1/business/documents/' + encodeURIComponent(id) + '/viewer'
export const viewerMetadata = (id:string) => roleApi<ViewerMetadata>(viewerPath(id), 'business')
export const viewerPage = (id:string, page:number, scale=1) =>
  contentBinary(viewerPath(id) + '/pages/' + page + '?scale=' + scale, 'business')
export const viewerSearch = (id:string, q:string) =>
  roleApi<ViewerSearch>(viewerPath(id) + '/search?q=' + encodeURIComponent(q), 'business')
