import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { ArrowRight, FileText, Search, X } from 'lucide-react'
import { PdfViewer } from '../PdfViewer'
import { contentApi, type BusinessRecord, type RecordSummary, type SearchResponse, type SearchSchema } from '../api'

/** API-backed R7 workspace. No mock records, document pages, or synthetic security events. */
export function LiveWorkspace({ persona, capabilities }: {
  persona: 'business' | 'steward'
  capabilities: string[]
}) {
  const [schema, setSchema] = useState<SearchSchema>({ sources: [] })
  const [sourceId, setSourceId] = useState('')
  const [criteria, setCriteria] = useState<Record<string, string>>({})
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(false)
  const [results, setResults] = useState<RecordSummary[]>([])
  const [record, setRecord] = useState<BusinessRecord | null>(null)
  const [documentId, setDocumentId] = useState('')
  const [loading, setLoading] = useState(false)
  const [searching, setSearching] = useState(false)
  const [error, setError] = useState('')
  const [searched, setSearched] = useState(false)
  const source = useMemo(() => schema.sources.find(s => s.sourceId === sourceId), [schema, sourceId])
  const selectedDocument = record?.documents.find(d => d.documentId === documentId)
  const can = (permission: string) => capabilities.includes(permission)
  const toDate = (v: string, end = false) => v ? `${v}T${end ? '23:59:59.999' : '00:00:00.000'}Z` : null

  useEffect(() => {
    let active = true
    setSchema({ sources: [] }); setSourceId(''); setResults([]); setRecord(null); setDocumentId(''); setSearched(false)
    contentApi<SearchSchema>('/api/v1/business/search-schema', persona)
      .then(data => { if (active) { setSchema(data); setSourceId(data.sources[0]?.sourceId ?? '') } })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : 'Search configuration unavailable') })
    return () => { active = false }
  }, [persona])

  const search = async (nextPage: number) => {
    if (!sourceId || searching) return
    const submitted = Object.fromEntries(Object.entries(criteria).filter(([, v]) => v.trim()).map(([k, v]) => [k, v.trim()]))
    if (!Object.keys(submitted).length && !dateFrom && !dateTo) {
      setError('Enter at least one search value or a date range.'); return
    }
    setSearching(true); setError('')
    try {
      const response = await contentApi<SearchResponse>('/api/v1/business/search', persona, {
        method: 'POST',
        body: JSON.stringify({ sourceId, criteria: submitted, dateFrom: toDate(dateFrom), dateTo: toDate(dateTo, true),
          lastUpdatedFrom: null, lastUpdatedTo: null, page: nextPage, pageSize: 25 }),
      })
      setResults(response.records); setPage(response.page); setHasMore(response.hasMore)
      setSearched(true); setRecord(null); setDocumentId('')
    } catch (e) { setError(e instanceof Error ? e.message : 'Search failed') }
    finally { setSearching(false) }
  }
  const openRecord = async (ref: string) => {
    setLoading(true); setError(''); setDocumentId('')
    try {
      const value = await contentApi<BusinessRecord>(`/api/v1/business/records/${encodeURIComponent(ref)}`, persona)
      setRecord(value)
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not load record') }
    finally { setLoading(false) }
  }
  const clear = () => { setCriteria({}); setDateFrom(''); setDateTo(''); setResults([]); setRecord(null); setDocumentId(''); setSearched(false); setError('') }

  if (selectedDocument && can('VIEW_PDF')) {
    return <section className="v-live-viewer" aria-label="Secure document viewer">
      <PdfViewer
        key={selectedDocument.documentId} documentId={selectedDocument.documentId}
        filename={selectedDocument.originalFilename} documents={record?.documents ?? []}
        selectedDocumentId={documentId} onSelectDocument={setDocumentId} persona={persona}
        canRedact={can('PERMANENT_REDACTION')} canDownload={can('DOWNLOAD_CURRENT_PDF')}
        canPrint={can('PRINT_DOCUMENT')} canTag={can('ADD_DOCUMENT_TAG')}
        canComment={can('ADD_DOCUMENT_COMMENT')} embedded
        onClose={() => setDocumentId('')} />
    </section>
  }

  return <div className="v-live-workspace">
    <header className="v-live-head"><div><span className="eyebrow">Records workspace</span><h1>Find and review documents</h1>
      <p className="muted">Search by source-defined fields. Documents and actions are permission controlled.</p></div>
      {searched && <button className="v-btn quiet" type="button" onClick={clear}><X size={15}/> Clear search</button>}
    </header>
    {error && <div className="v-live-error" role="alert">{error}</div>}
    <div className="v-live-layout">
      <form className="v-live-panel v-live-filters" onSubmit={(e: FormEvent) => { e.preventDefault(); void search(1) }}>
        <h2>Search records</h2>
        <label>Source<select value={sourceId} onChange={e => { setSourceId(e.target.value); setCriteria({}); clear(); setSourceId(e.target.value) }}>
          {schema.sources.map(s => <option key={s.sourceId} value={s.sourceId}>{s.displayName}</option>)}</select></label>
        {source?.fields.map(f => <label key={f.fieldKey}>{f.displayLabel}<input value={criteria[f.fieldKey] ?? ''}
          onChange={e => setCriteria(prev => ({ ...prev, [f.fieldKey]: e.target.value }))}
          placeholder={f.searchMode === 'PARTIAL' ? 'Contains' : f.searchMode === 'PREFIX' ? 'Starts with' : 'Exact match'} /></label>)}
        <div className="v-live-dates"><label>Date from<input type="date" value={dateFrom} onChange={e => setDateFrom(e.target.value)}/></label>
        <label>Date to<input type="date" value={dateTo} onChange={e => setDateTo(e.target.value)}/></label></div>
        <button className="v-btn primary" type="submit" disabled={searching || !sourceId}><Search size={16}/>{searching ? 'Searching…' : 'Search'}</button>
      </form>
      <section className="v-live-panel v-live-results" aria-label="Search results">
        <div className="v-live-section-head"><h2>Results</h2><span>{searched ? `${results.length} records · page ${page}` : 'Run a search to begin'}</span></div>
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
      </section>
      <section className="v-live-panel v-live-detail" aria-label="Record details">
        <div className="v-live-section-head"><h2>Record</h2>{loading && <span>Loading…</span>}</div>
        {!record && <p className="muted">Select a result to see its documents.</p>}
        {record && <><div className="v-live-identifiers">{record.identifiers.map(x => <div key={x.fieldKey}>
          <small>{x.displayLabel}</small><strong>{x.value}</strong></div>)}</div>
          <h3>Documents ({record.documents.length})</h3>
          {!record.documents.length && <p className="muted">No available documents.</p>}
          {record.documents.map(d => <button type="button" key={d.documentId} className="v-live-doc"
            disabled={!can('VIEW_PDF')} onClick={() => setDocumentId(d.documentId)}>
            <FileText size={17}/><span>{d.originalFilename}</span><ArrowRight size={15}/></button>)}
          {record.incompleteAttention && <p className="muted">Some submissions are incomplete. Only available documents can be opened.</p>}
        </>}
      </section>
    </div>
  </div>
}
