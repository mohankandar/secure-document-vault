import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ArrowRight, FileText, Search, X } from 'lucide-react'
import { contentApi, type SearchSchema, type SearchSource, type RecordSummary, type SearchResponse, type BusinessRecord, type DocumentActivity } from '../api'
import { PdfViewer } from '../PdfViewer'
import { Badge, Empty, Skeleton } from './ui'

type Tab = 'search' | 'results' | 'viewer' | 'activity'
export function Workspace({ persona, capabilities }: { persona: 'business' | 'steward'; capabilities: string[] }) {
  const [schema, setSchema] = useState<SearchSchema>({sources:[]})
  const [sourceId, setSourceId] = useState('')
  const [criteria, setCriteria] = useState<Record<string,string>>({})
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [updatedFrom, setUpdatedFrom] = useState('')
  const [updatedTo, setUpdatedTo] = useState('')
  const [tab, setTab] = useState<Tab>('search')
  const [results, setResults] = useState<RecordSummary[]>([])
  const [searched, setSearched] = useState(false)
  const [page, setPage] = useState(1)
  const [more, setMore] = useState(false)
  const [record, setRecord] = useState<BusinessRecord|null>(null)
  const [docId, setDocId] = useState('')
  const [lastDocId, setLastDocId] = useState('')
  const [activity, setActivity] = useState<(DocumentActivity & { documentName: string })[]>([])
  const [includeViews, setIncludeViews] = useState(false)
  const [range, setRange] = useState('90')
  const [loading, setLoading] = useState(false)
  const [loadingRecord, setLoadingRecord] = useState(false)
  const [loadingActivity, setLoadingActivity] = useState(false)
  const [error, setError] = useState('')
  const [activityError, setActivityError] = useState('')
  const searchSeq = useRef(0), recordSeq = useRef(0), activitySeq = useRef(0)
  const source: SearchSource | undefined = schema.sources.find(s=>s.sourceId===sourceId)
  const canView = capabilities.includes('VIEW_PDF')
  const selectedDoc = record?.documents.find(d=>d.documentId===docId)
  const iso = (v:string,end=false)=>v ? v+'T'+(end?'23:59:59.999':'00:00:00.000')+'Z' : null

  useEffect(()=>{
    let active=true
    ++searchSeq.current; ++recordSeq.current; ++activitySeq.current
    setResults([]);setRecord(null);setDocId('');setLastDocId('');setActivity([]);setSearched(false);setTab('search');setError('')
    void contentApi<SearchSchema>('/api/v1/business/search-schema',persona)
      .then(x=>{if(active){setSchema(x);setSourceId(x.sources[0]?.sourceId??'')}})
      .catch(e=>{if(active)setError(e instanceof Error?e.message:'Search configuration unavailable')})
    return ()=>{active=false;++searchSeq.current;++recordSeq.current;++activitySeq.current}
  },[persona])

  const clear=()=>{++searchSeq.current;++recordSeq.current;++activitySeq.current;setCriteria({});setFrom('');setTo('');setUpdatedFrom('');setUpdatedTo('');setResults([]);setRecord(null);setDocId('');setLastDocId('');setActivity([]);setSearched(false);setLoading(false);setLoadingRecord(false);setLoadingActivity(false);setError('');setTab('search')}
  const search=async(next=1)=>{
    if(loading||!sourceId)return
    const filters=Object.fromEntries(Object.entries(criteria).filter(([,v])=>v.trim()).map(([k,v])=>[k,v.trim()]))
    if(!Object.keys(filters).length&&!from&&!to&&!updatedFrom&&!updatedTo){setError('Enter at least one search value or date filter.');return}
    if((from&&to&&from>to)||(updatedFrom&&updatedTo&&updatedFrom>updatedTo)){setError('Start date cannot be after end date.');return}
    const token=++searchSeq.current;++recordSeq.current;++activitySeq.current
    setLoading(true);setError('');setResults([]);setRecord(null);setDocId('');setLastDocId('');setSearched(false);setPage(next);setActivity([])
    try {
      const x=await contentApi<SearchResponse>('/api/v1/business/search',persona,{method:'POST',body:JSON.stringify({sourceId,criteria:filters,dateFrom:iso(from),dateTo:iso(to,true),lastUpdatedFrom:iso(updatedFrom),lastUpdatedTo:iso(updatedTo,true),page:next,pageSize:25})})
      if(token!==searchSeq.current)return
      setResults(x.records);setPage(x.page);setMore(x.hasMore);setSearched(true);setTab('results')
    }catch(e){if(token===searchSeq.current)setError(e instanceof Error?e.message:'Search failed')}
    finally{if(token===searchSeq.current)setLoading(false)}
  }
  const selectRecord=async(ref:string)=>{
    const token=++recordSeq.current;++activitySeq.current
    setRecord(null);setDocId('');setLastDocId('');setActivity([]);setLoadingRecord(true);setError('')
    try{const x=await contentApi<BusinessRecord>('/api/v1/business/records/'+encodeURIComponent(ref),persona);if(token===recordSeq.current)setRecord(x)}
    catch(e){if(token===recordSeq.current)setError(e instanceof Error?e.message:'Record unavailable')}
    finally{if(token===recordSeq.current)setLoadingRecord(false)}
  }
  const openDoc=(id:string)=>{if(canView&&record?.documents.some(d=>d.documentId===id)){setDocId(id);setLastDocId(id);setTab('viewer')}}
  useEffect(()=>{
    if(tab!=='activity'||!record)return
    const token=++activitySeq.current;setLoadingActivity(true);setActivityError('');setActivity([])
    void Promise.all(record.documents.map(async d=>{
      const q=new URLSearchParams({includeViewEvents:String(includeViews),limit:'200'})
      if(range!=='all')q.set('from',new Date(Date.now()-Number(range)*86400000).toISOString())
      const rows=await contentApi<DocumentActivity[]>('/api/v1/business/documents/'+encodeURIComponent(d.documentId)+'/activity?'+q,persona)
      return rows.map(x=>({...x,documentName:d.originalFilename}))
    })).then(parts=>{if(token===activitySeq.current)setActivity(parts.flat().sort((a,b)=>b.occurredAt.localeCompare(a.occurredAt)))})
      .catch(e=>{if(token===activitySeq.current)setActivityError(e instanceof Error?e.message:'Activity unavailable')})
      .finally(()=>{if(token===activitySeq.current)setLoadingActivity(false)})
    return()=>{++activitySeq.current}
  },[tab,record,range,includeViews,persona])

  const fields=source?.fields??[]
  return <div className="v-body" style={{maxWidth:'none'}}>
    <nav className="v-tabs" aria-label="Business workspace">
      {(['search','results','viewer','activity'] as const).map(t=><button key={t} type="button" aria-current={tab===t?'page':undefined}
        disabled={t==='results'&&!searched||t==='viewer'&&(!record||!record.documents.length||!canView)||t==='activity'&&!record}
        onClick={()=>{if(t==='viewer'){const x=record?.documents.find(d=>d.documentId===lastDocId)??record?.documents[0];if(x)openDoc(x.documentId)}else {setDocId('');setTab(t)}}}>
        {t==='viewer'?'Document Viewer':t[0].toUpperCase()+t.slice(1)}</button>)}
    </nav>
    {error&&<div className="v-panel" role="alert" style={{padding:14,color:'#b42318'}}>{error}</div>}
    {tab==='search'&&<section className="ws-search" aria-label="Search records">
      <div className="ws-search-head"><div className="titles"><div className="eyebrow">Records workspace</div><h1>Find a business record</h1></div>
        <label className="v-field" style={{maxWidth:300}}>Source<select className="v-select" value={sourceId} onChange={e=>{clear();setSourceId(e.target.value)}}>{schema.sources.map(x=><option value={x.sourceId} key={x.sourceId}>{x.displayName}</option>)}</select></label>
      </div>
      <form className="ws-search-form" onSubmit={(e:FormEvent)=>{e.preventDefault();void search(1)}}>
        <div className="eyebrow">Identifiers</div>
        <div className="ws-fields">{fields.filter(f=>f.businessIdentifier).map(f=><label key={f.fieldKey} className="v-field"><span>{f.displayLabel}<span className="v-hint">{f.searchMode==='EXACT'?'Exact match':f.searchMode==='PREFIX'?'Starts with':'Contains'}</span></span>
          <input className="v-input" value={criteria[f.fieldKey]??''} onChange={e=>setCriteria(v=>({...v,[f.fieldKey]:e.target.value}))}/></label>)}</div>
        {fields.some(f=>!f.businessIdentifier)&&<><div className="eyebrow" style={{marginTop:20}}>Record details</div><div className="ws-fields">{fields.filter(f=>!f.businessIdentifier).map(f=><label key={f.fieldKey} className="v-field"><span>{f.displayLabel}<span className="v-hint">{f.searchMode==='EXACT'?'Exact match':f.searchMode==='PREFIX'?'Starts with':'Contains'}</span></span>
          <input className="v-input" value={criteria[f.fieldKey]??''} onChange={e=>setCriteria(v=>({...v,[f.fieldKey]:e.target.value}))}/></label>)}</div></>}
        <div className="eyebrow" style={{marginTop:20}}>Dates</div>
        <div className="ws-fields"><fieldset className="v-field" style={{border:0,padding:0,margin:0}}><legend className="v-label">Date range</legend><div className="row"><input className="v-input" type="date" aria-label="Date from" value={from} onChange={e=>setFrom(e.target.value)}/><span>to</span><input className="v-input" type="date" aria-label="Date to" value={to} onChange={e=>setTo(e.target.value)}/></div></fieldset>
          <fieldset className="v-field" style={{border:0,padding:0,margin:0}}><legend className="v-label">Last updated</legend><div className="row"><input className="v-input" type="date" aria-label="Updated from" value={updatedFrom} onChange={e=>setUpdatedFrom(e.target.value)}/><span>to</span><input className="v-input" type="date" aria-label="Updated to" value={updatedTo} onChange={e=>setUpdatedTo(e.target.value)}/></div></fieldset></div>
        <div className="row" style={{justifyContent:'flex-end',marginTop:22}}><small className="faint grow">Any one field or date filter is enough to search.</small><button type="button" className="v-btn quiet" onClick={clear}>Clear</button><button type="submit" className="v-btn primary" disabled={!sourceId||loading}><Search size={16}/>{loading?'Searching…':'Search records'}</button></div>
      </form>
    </section>}
    {tab==='results'&&<section className="ws-results" aria-label="Search results">
      <div className="ws-results-head"><h2>{searched?results.length+' records · page '+page:'Results'}</h2></div>
      {loading&&<Skeleton rows={4}/>}
      {searched&&!results.length&&<Empty title="No matching records">Try different criteria.</Empty>}
      <ul className="ws-list">{results.map(r=><li key={r.recordRef}><button className="ws-rec" type="button" aria-current={record?.recordRef===r.recordRef} onClick={()=>void selectRecord(r.recordRef)}>
        <span className="ws-stack" aria-hidden><i/><i/><i/><span>{r.availableDocumentCount}</span></span>
        <span className="stack grow"><span className="ids">{r.identifiers.map(v=><span key={v.fieldKey}><b>{v.displayLabel}</b>{v.value}</span>)}</span>
          <span className="title">{r.metadataPreview.map(v=>v.value).join(' · ')||'Business record'}</span>
          <span className="meta">{r.availableDocumentCount} documents · {r.submissionCount} submissions {r.incomplete&&<Badge tone="warn">Incomplete submission</Badge>}</span></span><ArrowRight size={16}/></button></li>)}</ul>
      {searched&&<div className="ws-pager"><button className="v-btn sm" disabled={page<=1||loading} onClick={()=>void search(page-1)}>Previous</button><span>Page {page}</span><button className="v-btn sm" disabled={!more||loading} onClick={()=>void search(page+1)}>Next</button></div>}
      {loadingRecord&&<p role="status">Loading record…</p>}
      {record&&<aside className="ws-inspector" aria-label="Record inspector"><header className="ws-insp-head"><div className="stack grow"><span className="eyebrow">Record</span><h2>{record.identifiers.map(i=>i.value).join(' · ')}</h2></div><button className="v-btn quiet icon" aria-label="Close record" onClick={()=>setRecord(null)}><X size={18}/></button></header>
        <div className="ws-insp-body"><div className="ws-ids">{record.identifiers.map(i=><div key={i.fieldKey}><span>{i.displayLabel}</span><strong>{i.value}</strong></div>)}</div>
          {record.incompleteAttention&&<Badge tone="warn">Incomplete submissions</Badge>}
          <h3>Documents ({record.documents.length})</h3><ul className="ws-docs">{record.documents.map(d=><li key={d.documentId}><button type="button" className="ws-doc" disabled={!canView} onClick={()=>openDoc(d.documentId)}><span className="sheet" aria-hidden/><span className="grow"><strong>{d.originalFilename}</strong><span>Ingested {new Date(d.ingestedAt).toLocaleDateString()}</span></span><FileText size={16}/></button></li>)}</ul>
          <h3>Details</h3><dl className="v-kv">{record.metadata.map(v=><div key={v.fieldKey}><dt>{v.displayLabel}</dt><dd>{v.value}</dd></div>)}</dl>
          <button type="button" className="v-btn" onClick={()=>setTab('activity')}>Record activity</button></div></aside>}
    </section>}
    {tab==='viewer'&&selectedDoc&&canView&&<section aria-label="Secure document viewer"><PdfViewer key={selectedDoc.documentId} documentId={selectedDoc.documentId} filename={selectedDoc.originalFilename} documents={record?.documents??[]}
      selectedDocumentId={docId} onSelectDocument={openDoc} persona={persona} canRedact={capabilities.includes('PERMANENT_REDACTION')} canDownload={capabilities.includes('DOWNLOAD_CURRENT_PDF')} canPrint={capabilities.includes('PRINT_DOCUMENT')} canTag={capabilities.includes('ADD_DOCUMENT_TAG')} canComment={capabilities.includes('ADD_DOCUMENT_COMMENT')} embedded onClose={()=>{setDocId('');setTab('results')}}/></section>}
    {tab==='activity'&&record&&<section className="v-panel"><div className="v-panel-body"><div className="row between"><h2>Record activity</h2><label className="v-field">Period<select className="v-select" value={range} onChange={e=>setRange(e.target.value)}><option value="30">30 days</option><option value="90">90 days</option><option value="365">365 days</option><option value="all">All</option></select></label></div>
      <label className="v-field"><input type="checkbox" checked={includeViews} onChange={e=>setIncludeViews(e.target.checked)}/> Include document view activity</label>
      {activityError&&<p role="alert">{activityError}</p>}{loadingActivity&&<p role="status">Loading activity…</p>}{!loadingActivity&&!activity.length&&<p>No activity for this record.</p>}
      <ol className="v-timeline">{activity.map(e=><li key={e.auditEventId}><div className="stack"><strong>{e.action.replaceAll('_',' ')} · {e.documentName}</strong><span>{e.actorId||e.actorType} · {new Date(e.occurredAt).toLocaleString()} · {e.outcome}</span></div></li>)}</ol>
    </div></section>}
  </div>
}
