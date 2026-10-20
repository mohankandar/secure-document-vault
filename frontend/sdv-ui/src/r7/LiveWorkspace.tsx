import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { ArrowLeft, ArrowRight, FileText, Search, X } from 'lucide-react'
import { PdfViewer } from '../PdfViewer'
import { contentApi, type BusinessRecord, type RecordSummary, type SearchResponse, type SearchSchema, type DocumentActivity } from '../api'

/** API-backed R7 workspace. No mock records, document pages, or synthetic security events. */
export function LiveWorkspace({ persona, capabilities }: {
  persona: 'business' | 'steward'
  capabilities: string[]
}) {
  // Ignore superseded responses when the role, source, search or selected record changes.
  const searchRequest = useRef(0)
  const recordRequest = useRef(0)
  const [schema, setSchema] = useState<SearchSchema>({ sources: [] })
  const [sourceId, setSourceId] = useState('')
  const [criteria, setCriteria] = useState<Record<string, string>>({})
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [updatedFrom, setUpdatedFrom] = useState('')
  const [updatedTo, setUpdatedTo] = useState('')
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(false)
  const [results, setResults] = useState<RecordSummary[]>([])
  const [record, setRecord] = useState<BusinessRecord | null>(null)
  const [documentId, setDocumentId] = useState('')
  const [lastViewedDocumentId, setLastViewedDocumentId] = useState('')
  const [loading, setLoading] = useState(false)
  const [searching, setSearching] = useState(false)
  const [error, setError] = useState('')
  const [searched, setSearched] = useState(false)
  const activityRequest = useRef(0)
  const [detailTab, setDetailTab] = useState<'documents' | 'activity'>('documents')
  const [workspaceTab, setWorkspaceTab] = useState<'search' | 'results' | 'viewer' | 'activity'>('search')
  const [includeViews, setIncludeViews] = useState(false)
  const [activityRange, setActivityRange] = useState('90')
  const [activityAction, setActivityAction] = useState('')
  const [activityActor, setActivityActor] = useState('')
  const [activity, setActivity] = useState<(DocumentActivity & { documentName: string })[]>([])
  const [activityLoading, setActivityLoading] = useState(false)
  const [activityError, setActivityError] = useState('')
  const source = useMemo(() => schema.sources.find(s => s.sourceId === sourceId), [schema, sourceId])
  const selectedDocument = record?.documents.find(d => d.documentId === documentId)
  const can = (permission: string) => capabilities.includes(permission)
  const toDate = (v: string, end = false) => v ? `${v}T${end ? '23:59:59.999' : '00:00:00.000'}Z` : null

  useEffect(() => {
    let active = true
    searchRequest.current += 1
    recordRequest.current += 1
    activityRequest.current += 1
    setActivity([]); setActivityLoading(false); setActivityError(''); setDetailTab('documents'); setWorkspaceTab('search'); setIncludeViews(false)
    setError(''); setSearching(false); setLoading(false)
    setSchema({ sources: [] }); setSourceId(''); setResults([]); setRecord(null); setDocumentId(''); setLastViewedDocumentId(''); setSearched(false)
    contentApi<SearchSchema>('/api/v1/business/search-schema', persona)
      .then(data => { if (active) { setSchema(data); setSourceId(data.sources[0]?.sourceId ?? '') } })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : 'Search configuration unavailable') })
    return () => { active = false; searchRequest.current += 1; recordRequest.current += 1; activityRequest.current += 1 }
  }, [persona])

  const search = async (nextPage: number) => {
    if (!sourceId || searching) return
    const submitted = Object.fromEntries(Object.entries(criteria).filter(([, v]) => v.trim()).map(([k, v]) => [k, v.trim()]))
    if (!Object.keys(submitted).length && !dateFrom && !dateTo && !updatedFrom && !updatedTo) {
      setError('Enter at least one search value or a date range.'); return
    }
    if ((dateFrom && dateTo && dateFrom > dateTo) || (updatedFrom && updatedTo && updatedFrom > updatedTo)) {
      setError('The start date cannot be later than the end date.'); return
    }
    const request = ++searchRequest.current
    recordRequest.current += 1
    activityRequest.current += 1
    setActivity([]); setActivityError(''); setDetailTab('documents'); setActivityLoading(false)
    setRecord(null); setDocumentId(''); setLastViewedDocumentId(''); setLoading(false)
    // Do not show results from an earlier query while a replacement search runs or fails.
    setResults([]); setHasMore(false); setPage(nextPage); setSearched(false)
    setSearching(true); setError('')
    try {
      const response = await contentApi<SearchResponse>('/api/v1/business/search', persona, {
        method: 'POST',
        body: JSON.stringify({ sourceId, criteria: submitted, dateFrom: toDate(dateFrom), dateTo: toDate(dateTo, true),
          lastUpdatedFrom: toDate(updatedFrom), lastUpdatedTo: toDate(updatedTo, true), page: nextPage, pageSize: 25 }),
      })
      if (request !== searchRequest.current) return
      setResults(response.records); setPage(response.page); setHasMore(response.hasMore)
      setSearched(true); setWorkspaceTab('results'); setRecord(null); setDocumentId('')
    } catch (e) { if (request === searchRequest.current) setError(e instanceof Error ? e.message : 'Search failed') }
    finally { if (request === searchRequest.current) setSearching(false) }
  }
  const openRecord = async (ref: string) => {
    const request = ++recordRequest.current
    activityRequest.current += 1; setActivity([]); setActivityError(''); setActivityLoading(false); setDetailTab('documents')
    setLoading(true); setError(''); setDocumentId(''); setLastViewedDocumentId(''); setRecord(null)
    try {
      const value = await contentApi<BusinessRecord>(`/api/v1/business/records/${encodeURIComponent(ref)}`, persona)
      if (request === recordRequest.current) { setRecord(value); setWorkspaceTab('results') }
    } catch (e) { if (request === recordRequest.current) setError(e instanceof Error ? e.message : 'Could not load record') }
    finally { if (request === recordRequest.current) setLoading(false) }
  }
  const clear = () => { searchRequest.current += 1; recordRequest.current += 1; activityRequest.current += 1; setActivity([]); setActivityError(''); setActivityLoading(false); setDetailTab('documents'); setWorkspaceTab('search'); setIncludeViews(false); setSearching(false); setLoading(false); setCriteria({}); setDateFrom(''); setDateTo(''); setUpdatedFrom(''); setUpdatedTo(''); setResults([]); setRecord(null); setDocumentId(''); setLastViewedDocumentId(''); setSearched(false); setError('') }

  useEffect(() => {
    if (!record || detailTab !== 'activity') return
    const request = ++activityRequest.current
    setActivityLoading(true); setActivityError('')
    const load = async () => {
      try {
        const batches = await Promise.all(record.documents.map(async document => {
          const query = new URLSearchParams({ includeViewEvents: String(includeViews), limit: '200' })
          if (activityRange !== 'all') query.set('from', new Date(Date.now() - Number(activityRange) * 86400000).toISOString())
          if (activityAction.trim()) query.set('action', activityAction.trim())
          if (activityActor.trim()) query.set('actorId', activityActor.trim())
          const events = await contentApi<DocumentActivity[]>(
            `/api/v1/business/documents/${document.documentId}/activity?${query}`, persona)
          return events.map(event => ({ ...event, documentName: document.originalFilename }))
        }))
        if (request === activityRequest.current)
          setActivity(batches.flat().sort((a, b) => Date.parse(b.occurredAt) - Date.parse(a.occurredAt)))
      } catch (e) {
        if (request === activityRequest.current) { setActivity([]); setActivityError(e instanceof Error ? e.message : 'Activity could not be loaded') }
      } finally {
        if (request === activityRequest.current) setActivityLoading(false)
      }
    }
    void load()
    return () => { activityRequest.current += 1 }
  }, [record, detailTab, includeViews, activityRange, activityAction, activityActor, persona])

  const openDocument = (id: string) => {
    if (!can('VIEW_PDF') || !record?.documents.some(d => d.documentId === id)) return
    setLastViewedDocumentId(id)
    setDocumentId(id)
    setWorkspaceTab('viewer')
  }

  if (selectedDocument && can('VIEW_PDF')) {
    return <div className="v-live-workspace v-live-workspace-viewer">
      <nav className="v-live-workflow-tabs" aria-label="Business workspace">
        <button type="button" onClick={() => { setDocumentId(''); setWorkspaceTab('search') }}>Search</button>
        <button type="button" onClick={() => { setDocumentId(''); setWorkspaceTab('results') }}>Results</button>
        <button type="button" aria-current="page">Document Viewer</button>
        <button type="button" onClick={() => { setDocumentId(''); setDetailTab('activity'); setWorkspaceTab('activity') }}>Activity</button>
      </nav>
      <section className="v-live-viewer" aria-label="Secure document viewer">
      <header className="v-live-viewer-context">
        <button type="button" className="v-live-viewer-back" onClick={() => { setDocumentId(''); setWorkspaceTab('results') }}>
          <ArrowLeft size={16} aria-hidden /> Back to record
        </button>
        <div className="v-live-viewer-current">
          <span>Document viewer</span>
          <strong title={selectedDocument.originalFilename}>{selectedDocument.originalFilename}</strong>
          <small>{source?.displayName || record?.sourceDisplayName || 'Document source'}</small>
        </div>
        <span className="v-live-viewer-secure">Secure document session</span>
      </header>
      <div className="v-live-viewer-content"><PdfViewer
        key={selectedDocument.documentId} documentId={selectedDocument.documentId}
        filename={selectedDocument.originalFilename} documents={record?.documents ?? []}
        selectedDocumentId={documentId} onSelectDocument={openDocument} persona={persona}
        canRedact={can('PERMANENT_REDACTION')} canDownload={can('DOWNLOAD_CURRENT_PDF')}
        canPrint={can('PRINT_DOCUMENT')} canTag={can('ADD_DOCUMENT_TAG')}
        canComment={can('ADD_DOCUMENT_COMMENT')} embedded
        onClose={() => { setDocumentId(''); setWorkspaceTab('results') }} /></div>
    </section></div>
  }

  return <div className="v-live-workspace">
    <nav className="v-live-workflow-tabs" aria-label="Business workspace">
      <button type="button" aria-current={workspaceTab === 'search' ? 'page' : undefined} onClick={() => { setDetailTab('documents'); setWorkspaceTab('search') }}>Search</button>
      <button type="button" aria-current={workspaceTab === 'results' ? 'page' : undefined} disabled={!searched} onClick={() => { setDetailTab('documents'); setWorkspaceTab('results') }}>Results</button>
      <button type="button" aria-current={workspaceTab === 'viewer' ? 'page' : undefined} disabled={!record || !record.documents.length || !can('VIEW_PDF')} onClick={() => { const next = record?.documents.find(d => d.documentId === lastViewedDocumentId) ?? record?.documents[0]; if (next) openDocument(next.documentId) } }>Document Viewer</button>
      <button type="button" aria-current={workspaceTab === 'activity' ? 'page' : undefined} disabled={!record} onClick={() => { setDetailTab('activity'); setWorkspaceTab('activity') }}>Activity</button>
    </nav>
    <header className="v-live-head"><div><span className="eyebrow">Records workspace</span><h1>Find and review documents</h1>
      <p className="muted">Search by source-defined fields. Documents and actions are permission controlled.</p></div>
      {searched && <button className="v-btn quiet" type="button" onClick={clear}><X size={15}/> Clear search</button>}
    </header>
    {error && <div className="v-live-error" role="alert">{error}</div>}
    <div className={`v-live-layout v-live-layout-${workspaceTab}`}>
      {workspaceTab === 'search' && <form className="v-live-panel v-live-filters" onSubmit={(e: FormEvent) => { e.preventDefault(); void search(1) }}>
        <h2>Search records</h2>
        <label>Source<select value={sourceId} onChange={e => { clear(); setSourceId(e.target.value) }}>
          {schema.sources.map(s => <option key={s.sourceId} value={s.sourceId}>{s.displayName}</option>)}</select></label>
        {source?.fields.map(f => <label key={f.fieldKey}>{f.displayLabel}<input value={criteria[f.fieldKey] ?? ''}
          onChange={e => setCriteria(prev => ({ ...prev, [f.fieldKey]: e.target.value }))}
          placeholder={f.searchMode === 'PARTIAL' ? 'Contains' : f.searchMode === 'PREFIX' ? 'Starts with' : 'Exact match'} /></label>)}
        <div className="v-live-dates"><label>Date from<input type="date" value={dateFrom} onChange={e => setDateFrom(e.target.value)}/></label>
        <label>Date to<input type="date" value={dateTo} onChange={e => setDateTo(e.target.value)}/></label></div>
        <div className="v-live-dates"><label>Updated from<input type="date" value={updatedFrom} onChange={e => setUpdatedFrom(e.target.value)}/></label>
        <label>Updated to<input type="date" value={updatedTo} onChange={e => setUpdatedTo(e.target.value)}/></label></div>
        <button className="v-btn primary" type="submit" disabled={searching || !sourceId}><Search size={16}/>{searching ? 'Searching…' : 'Search'}</button>
      </form>}
      {workspaceTab === 'results' && <section className="v-live-panel v-live-results" aria-label="Search results">
        <div className="v-live-section-head"><h2>Results</h2><span>{searched ? `${results.length} records · page ${page}` : 'Run a search to begin'}</span></div>
        {searching && <p className="muted" role="status">Searching records…</p>}
        {searched && !results.length && <p className="muted">No matching records.</p>}
        {results.map(r => <button type="button" key={r.recordRef}
            className={`v-live-record ${record?.recordRef === r.recordRef ? 'selected' : ''}`}
            onClick={() => void openRecord(r.recordRef)}>
          <div><strong>{r.identifiers.map(x => x.value).join(' · ') || 'Business record'}</strong>
            <small>{r.metadataPreview.map(x => `${x.displayLabel}: ${x.value}`).join(' · ')}</small>
            <small>{r.availableDocumentCount} available documents {r.incomplete ? '· Incomplete submission' : ''}</small></div>
          <ArrowRight size={17} aria-hidden/>
        </button>)}
        {searched && <div className="v-live-pagination"><button type="button" className="v-btn quiet" disabled={page <= 1 || searching} onClick={() => void search(page - 1)}>Previous</button>
          <button type="button" className="v-btn quiet" disabled={!hasMore || searching} onClick={() => void search(page + 1)}>Next</button></div>}
      </section>}
      {(workspaceTab === 'results' || workspaceTab === 'activity') && <section className="v-live-panel v-live-detail" aria-label="Record details">
        <div className="v-live-section-head"><h2>Record</h2>{loading && <span>Loading…</span>}</div>
        {!record && <p className="muted">Select a result to see its documents.</p>}
        {record && <><div className="v-live-identifiers">{record.identifiers.map(x => <div key={x.fieldKey}>
          <small>{x.displayLabel}</small><strong>{x.value}</strong></div>)}</div>
          {workspaceTab === 'results' && <div className="v-live-detailtabs" role="tablist" aria-label="Record details">
            <button type="button" role="tab" aria-selected={detailTab === 'documents'} onClick={() => { setDetailTab('documents'); setWorkspaceTab('results') }}>Documents ({record.documents.length})</button>
            <button type="button" role="tab" aria-selected={detailTab === 'activity'} onClick={() => { setDetailTab('activity'); setWorkspaceTab('activity') }}>Activity</button>
          </div>}
          {workspaceTab === 'results' && detailTab === 'documents' && <>
          <h3>Documents ({record.documents.length})</h3>
          {!record.documents.length && <p className="muted">No available documents.</p>}
          {record.documents.map(d => <button type="button" key={d.documentId} className="v-live-doc"
            disabled={!can('VIEW_PDF')} onClick={() => openDocument(d.documentId)}>
            <FileText size={17}/><span>{d.originalFilename}</span><ArrowRight size={15}/></button>)}
          {record.incompleteAttention && <p className="muted">Some submissions are incomplete. Only available documents can be opened.</p>}
          </>}
          {workspaceTab === 'activity' && <section className="v-live-activity" aria-label="Record activity">
            <div className="v-live-activity-options">
              <label>Activity period
                <select value={activityRange} onChange={e => setActivityRange(e.target.value)}>
                  <option value="30">Last 30 days</option>
                  <option value="90">Last 90 days</option>
                  <option value="365">Last 365 days</option>
                  <option value="all">All available</option>
                </select>
              </label>
              <label>Action code<input type="search" value={activityAction} onChange={e => setActivityAction(e.target.value)} placeholder="Filter by action"/></label>
              <label>Actor ID<input type="search" value={activityActor} onChange={e => setActivityActor(e.target.value)} placeholder="Filter by actor"/></label>
              <label className="v-live-read-toggle"><input type="checkbox" checked={includeViews} onChange={e => setIncludeViews(e.target.checked)} /> Include document view activity</label>
            </div>
            {activityError && <p role="alert" className="v-live-error">{activityError}</p>}
            {activityLoading ? <p role="status">Loading activity…</p> :
              activity.length === 0 ? <p className="muted">No activity found for the selected record.</p> :
              <ol>{activity.map(event => <li key={event.auditEventId}>
                <strong>{event.action.replaceAll('_', ' ')}</strong><small>{event.documentName}</small>
                <span>{new Date(event.occurredAt).toLocaleString()} · {event.actorId || event.actorType} · {event.outcome}</span>
              </li>)}</ol>}
          </section>
        </>}
      </section>}
    </div>
  </div>
}
