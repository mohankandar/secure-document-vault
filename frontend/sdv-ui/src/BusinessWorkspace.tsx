import { FormEvent, useEffect, useMemo, useState } from 'react'
import { PdfViewer } from './PdfViewer'
import Icon from './Icon'
import {
  BusinessRecord,
  DocumentActivity,
  LocalPersona,
  RecordSummary,
  SearchField,
  SearchResponse,
  SearchSchema,
  contentApi,
} from './api'

type BusinessTab = 'search' | 'results' | 'viewer' | 'activity'
type ContentPersona = 'business' | 'steward'

type Props = {
  persona: LocalPersona
  capabilities: string[]
}

type ActivityRow = DocumentActivity & { documentId: string; documentName: string }

const formatDate = (value?: string | null) => {
  if (!value) return '—'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

const searchHint = (field: SearchField) => field.searchMode === 'PREFIX' ? 'Starts with' : field.searchMode === 'PARTIAL' ? 'Contains' : 'Exact match'

const actionLabel = (action: string) => ({
  DOCUMENT_DOWNLOADED: 'Download',
  DOCUMENT_PRINTED: 'Print',
  DOCUMENT_TAG_ADDED: 'Tag added',
  DOCUMENT_COMMENT_ADDED: 'Comment added',
  DOCUMENT_PERMANENTLY_REDACTED: 'Permanent redaction',
  DOCUMENT_REDACTION_FAILED: 'Redaction failed',
  DOCUMENT_RECEIVED: 'Document uploaded',
  DOCUMENT_AVAILABLE: 'Document available',
  DOCUMENT_VIEW_OPENED: 'Document viewed',
  DOCUMENT_PAGE_VIEWED: 'Page viewed',
  DOCUMENT_TEXT_SEARCHED: 'Document searched',
}[action] ?? action.replaceAll('_', ' ').toLowerCase().replace(/^./, (m) => m.toUpperCase()))

function iconForAction(action: string) {
  if (action.includes('DOWNLOAD')) return 'download' as const
  if (action.includes('REDACT')) return 'redaction' as const
  if (action.includes('VIEW')) return 'eye' as const
  if (action.includes('PRINT')) return 'print' as const
  if (action.includes('COMMENT')) return 'document' as const
  return 'document' as const
}

export default function BusinessWorkspace({ persona, capabilities }: Props) {
  const sourcePreferenceKey = `sdv.businessWorkspace.sourceId.${persona}`
  const contentPersona: ContentPersona = persona === 'steward' ? 'steward' : 'business'
  const [tab, setTab] = useState<BusinessTab>('search')
  const [schema, setSchema] = useState<SearchSchema>({ sources: [] })
  const [sourceId, setSourceId] = useState('')
  const [criteria, setCriteria] = useState<Record<string, string>>({})
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [updatedFrom, setUpdatedFrom] = useState('')
  const [updatedTo, setUpdatedTo] = useState('')
  const [results, setResults] = useState<RecordSummary[]>([])
  const [selectedRecord, setSelectedRecord] = useState<BusinessRecord | null>(null)
  const [selectedDocumentId, setSelectedDocumentId] = useState('')
  const [documentListExpanded, setDocumentListExpanded] = useState<boolean | null>(null)
  const [searching, setSearching] = useState(false)
  const [opening, setOpening] = useState(false)
  const [truncated, setTruncated] = useState(false)
  const [error, setError] = useState('')
  const [includeViews, setIncludeViews] = useState(false)
  const [activityRange, setActivityRange] = useState('30')
  const [activityAction, setActivityAction] = useState('')
  const [activityActor, setActivityActor] = useState('')
  const [activityRows, setActivityRows] = useState<ActivityRow[]>([])
  const [activityLoading, setActivityLoading] = useState(false)

  const source = useMemo(() => schema.sources.find((item) => item.sourceId === sourceId), [schema.sources, sourceId])
  const selectedDocument = useMemo(() => selectedRecord?.documents.find((item) => item.documentId === selectedDocumentId) ?? null, [selectedRecord, selectedDocumentId])
  const canView = capabilities.includes('VIEW_PDF')
  const canDownload = capabilities.includes('DOWNLOAD_CURRENT_PDF')
  const canPrint = capabilities.includes('PRINT_DOCUMENT')
  const canTag = capabilities.includes('ADD_DOCUMENT_TAG')
  const canComment = capabilities.includes('ADD_DOCUMENT_COMMENT')
  const canRedact = capabilities.includes('PERMANENT_REDACTION')

  useEffect(() => {
    setError('')
    contentApi<SearchSchema>('/api/v1/business/search-schema', contentPersona)
      .then((data) => {
        setSchema(data)
        setSourceId((current) => {
          if (data.sources.some((item) => item.sourceId === current)) return current
          const preferred = window.localStorage.getItem(sourcePreferenceKey) ?? ''
          if (data.sources.some((item) => item.sourceId === preferred)) return preferred
          return data.sources[0]?.sourceId ?? ''
        })
      })
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : 'Could not load search configuration.'))
  }, [contentPersona, sourcePreferenceKey])

  useEffect(() => {
    if (sourceId) window.localStorage.setItem(sourcePreferenceKey, sourceId)
    setCriteria({})
    setResults([])
    setSelectedRecord(null)
    setSelectedDocumentId('')
    setTab('search')
  }, [sourceId, sourcePreferenceKey])

  const isoStart = (value: string) => value ? new Date(`${value}T00:00:00`).toISOString() : null
  const isoEnd = (value: string) => value ? new Date(`${value}T23:59:59.999`).toISOString() : null

  async function runSearch(event: FormEvent) {
    event.preventDefault()
    if (!sourceId) return
    const submitted = Object.fromEntries(Object.entries(criteria).filter(([, value]) => value.trim()).map(([key, value]) => [key, value.trim()]))
    if (Object.keys(submitted).length === 0 && !dateFrom && !dateTo && !updatedFrom && !updatedTo) {
      setError('Enter at least one source field, Date Range, or Last Updated filter.')
      return
    }
    setSearching(true)
    setError('')
    try {
      const response = await contentApi<SearchResponse>('/api/v1/business/search', contentPersona, {
        method: 'POST',
        body: JSON.stringify({
          sourceId,
          criteria: submitted,
          dateFrom: isoStart(dateFrom),
          dateTo: isoEnd(dateTo),
          lastUpdatedFrom: isoStart(updatedFrom),
          lastUpdatedTo: isoEnd(updatedTo),
          limit: 50,
        }),
      })
      setResults(response.records)
      setTruncated(response.truncated)
      setSelectedRecord(null)
      setSelectedDocumentId('')
      setTab('results')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Search failed.')
    } finally {
      setSearching(false)
    }
  }

  async function openRecord(recordRef: string) {
    setOpening(true)
    setError('')
    try {
      const value = await contentApi<BusinessRecord>(`/api/v1/business/records/${encodeURIComponent(recordRef)}`, contentPersona)
      setSelectedRecord(value)
      setSelectedDocumentId(value.documents[0]?.documentId ?? '')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not open business record.')
    } finally {
      setOpening(false)
    }
  }

  function openViewer(documentId?: string) {
    const id = documentId || selectedDocumentId
    if (!id) return
    setSelectedDocumentId(id)
    setTab('viewer')
  }

  async function loadActivity() {
    if (!selectedRecord) return
    setActivityLoading(true)
    setError('')
    try {
      const from = activityRange === 'all' ? '' : new Date(Date.now() - Number(activityRange) * 24 * 60 * 60 * 1000).toISOString()
      const batches = await Promise.all(selectedRecord.documents.map(async (document) => {
        const params = new URLSearchParams({ includeViewEvents: String(includeViews), limit: '200' })
        if (from) params.set('from', from)
        if (activityAction) params.set('action', activityAction)
        if (activityActor.trim()) params.set('actorId', activityActor.trim())
        const events = await contentApi<DocumentActivity[]>(`/api/v1/business/documents/${document.documentId}/activity?${params}`, contentPersona)
        return events.map((event) => ({ ...event, documentId: document.documentId, documentName: document.originalFilename }))
      }))
      const byId = new Map<string, ActivityRow>()
      batches.flat().forEach((row) => byId.set(row.auditEventId, row))
      setActivityRows([...byId.values()].sort((a, b) => new Date(b.occurredAt).getTime() - new Date(a.occurredAt).getTime()))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not load activity.')
    } finally {
      setActivityLoading(false)
    }
  }

  useEffect(() => {
    if (tab === 'activity' && selectedRecord) loadActivity().catch(() => undefined)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, selectedRecord?.recordRef, includeViews])

  const primaryIdentifier = selectedRecord?.identifiers[0]

  return <div className="business-workspace">
    <nav className="business-tabs" aria-label="Business document workflow">
      <button className={tab === 'search' ? 'active' : ''} onClick={() => setTab('search')}><Icon name="search" />Search</button>
      <button className={tab === 'results' ? 'active' : ''} disabled={results.length === 0} onClick={() => setTab('results')}><Icon name="list" />Results</button>
      <button className={tab === 'viewer' ? 'active' : ''} disabled={!selectedDocument || !canView} onClick={() => setTab('viewer')}><Icon name="document" />Document Viewer</button>
      <button className={tab === 'activity' ? 'active' : ''} disabled={!selectedRecord} onClick={() => setTab('activity')}><Icon name="operations" />Activity</button>
    </nav>

    {error && <div className="banner error top-gap">{error}</div>}

    {tab === 'search' && <section className="business-search-screen">
      <div className="business-search-hero">
        <div className="business-search-title">
          <span className="kicker">Business Search</span>
          <h2>Find a business record</h2>
          <p className="muted">Search by any identifier or detail below, then securely open the documents associated with the record.</p>
        </div>
        <label className="business-source-context">
          <span>Searching in</span>
          <select value={sourceId} onChange={(event) => setSourceId(event.target.value)} aria-label="Search source">{schema.sources.map((item) => <option key={item.sourceId} value={item.sourceId}>{item.displayName}</option>)}</select>
          <small>{source?.fields.length ?? 0} configured search fields</small>
        </label>
      </div>
      <form className="panel business-search-form business-search-form-premium" onSubmit={runSearch}>
        <div className="business-search-grid">
          {source?.fields.map((field) => <label key={field.fieldKey}><span>{field.displayLabel}{field.businessIdentifier && <em>Identifier</em>}</span><input value={criteria[field.fieldKey] ?? ''} onChange={(event) => setCriteria((current) => ({ ...current, [field.fieldKey]: event.target.value }))} placeholder={`${searchHint(field)} · ${field.fieldType.toLowerCase()}`} /></label>)}
        </div>
        <div className="common-filter-grid">
          <fieldset><legend>Date Range</legend><label>From<input type="date" value={dateFrom} onChange={(e) => setDateFrom(e.target.value)} /></label><label>To<input type="date" value={dateTo} onChange={(e) => setDateTo(e.target.value)} /></label></fieldset>
          <fieldset><legend>Last Updated</legend><label>From<input type="date" value={updatedFrom} onChange={(e) => setUpdatedFrom(e.target.value)} /></label><label>To<input type="date" value={updatedTo} onChange={(e) => setUpdatedTo(e.target.value)} /></label></fieldset>
        </div>
        <div className="business-search-footer">
          <span className="business-search-guidance">Any one field or date filter is enough to search.</span>
          <div className="form-actions"><button type="button" className="secondary" onClick={() => { setCriteria({}); setDateFrom(''); setDateTo(''); setUpdatedFrom(''); setUpdatedTo('') }}>Clear</button><button type="submit" disabled={searching || !sourceId}>{searching ? 'Searching…' : 'Search records'}</button></div>
        </div>
      </form>
    </section>}

    {tab === 'results' && <>
      <div className="business-results-hero">
        <div>
          <span className="kicker">Results</span>
          <h2><strong>{results.length}</strong> matching {results.length === 1 ? 'record' : 'records'}</h2>
          <p className="muted">in {source?.displayName ?? 'selected source'}</p>
        </div>
        <div className="business-results-summary">
          <div className="business-search-chips" aria-label="Applied search criteria">
            {(source?.fields ?? []).filter((field) => criteria[field.fieldKey]?.trim()).map((field) => <span key={field.fieldKey}><small>{field.displayLabel}</small><strong>{criteria[field.fieldKey].trim()}</strong></span>)}
            {(dateFrom || dateTo) && <span><small>Date Range</small><strong>{dateFrom || '…'} – {dateTo || '…'}</strong></span>}
            {(updatedFrom || updatedTo) && <span><small>Last Updated</small><strong>{updatedFrom || '…'} – {updatedTo || '…'}</strong></span>}
          </div>
          <button type="button" className="secondary business-modify-search" onClick={() => setTab('search')}>Modify search</button>
        </div>
      </div>
      <div className="business-results-layout">
      <section className="panel business-results-panel">
        {truncated && <div className="banner notice">Result window reached. Refine the search to narrow the list.</div>}
        <div className="business-result-table-wrap"><table className="business-result-table"><thead><tr><th></th>{(source?.fields ?? []).slice(0, 5).map((field) => <th key={field.fieldKey}>{field.displayLabel}</th>)}<th>Documents</th><th>Last Updated</th></tr></thead><tbody>{results.map((item) => {
          const values = new Map([...item.identifiers, ...item.metadataPreview].map((value) => [value.fieldKey, value.value]))
          return <tr key={item.recordRef} className={selectedRecord?.recordRef === item.recordRef ? 'selected' : ''} onClick={() => openRecord(item.recordRef)}><td data-label="Select"><input type="radio" aria-label={`Select record ${item.recordRef}`} readOnly checked={selectedRecord?.recordRef === item.recordRef} /></td>{(source?.fields ?? []).slice(0,5).map((field) => <td key={field.fieldKey} data-label={field.displayLabel}>{values.get(field.fieldKey) ?? '—'}</td>)}<td data-label="Documents">{item.availableDocumentCount}</td><td data-label="Last Updated">{formatDate(item.latestSubmissionAt)}</td></tr>
        })}</tbody></table></div>
        {results.length === 0 && <div className="empty-state"><Icon name="search" /><strong>No matching records</strong><span>Change your criteria in the Search tab and try again.</span></div>}
      </section>
      <aside className="panel selected-record-panel">
        <div className="panel-heading selected-record-heading"><div><span className="kicker">Selected record</span><h2>{primaryIdentifier?.value ?? (opening ? 'Opening…' : 'No record selected')}</h2>{!selectedRecord && !opening && <p className="muted">Choose a row to review its details and available documents.</p>}</div></div>
        {selectedRecord && <><div className="compact-record-grid">{selectedRecord.metadata.map((value) => <div key={value.fieldKey}><span>{value.displayLabel}</span><strong>{value.value}</strong></div>)}</div><div className="section-heading"><h3>Available documents ({selectedRecord.documents.length})</h3></div><div className="compact-document-list">{selectedRecord.documents.map((document) => <button key={document.documentId} className={selectedDocumentId === document.documentId ? 'selected' : ''} onClick={() => setSelectedDocumentId(document.documentId)}><Icon name="document" /><span><strong>{document.originalFilename}</strong><small>Available {formatDate(document.availableAt)}</small></span></button>)}</div><button className="wide-action" disabled={!selectedDocumentId || !canView} onClick={() => openViewer()}>Open selected document</button></>}
      </aside>
    </div>
    </>}

    {tab === 'viewer' && selectedRecord && selectedDocument && <div className="viewer-tab-layout">
      <div className="viewer-record-strip">{selectedRecord.identifiers.map((value) => <div key={value.fieldKey}><span>{value.displayLabel}</span><strong>{value.value}</strong></div>)}<div><span>Source</span><strong>{selectedRecord.sourceDisplayName}</strong></div></div>
      <div className={`viewer-tab-body ${(documentListExpanded ?? (selectedRecord.documents.length > 1)) ? 'documents-open' : 'documents-collapsed'}`}>
        <div className="viewer-document-switch">
          <button type="button" className="secondary viewer-document-toggle" aria-expanded={documentListExpanded ?? (selectedRecord.documents.length > 1)} onClick={() => setDocumentListExpanded((value) => !(value ?? (selectedRecord.documents.length > 1)))}><span>Documents ({selectedRecord.documents.length})</span><span className="viewer-document-toggle-chevron" aria-hidden="true">{documentListExpanded ?? (selectedRecord.documents.length > 1) ? '▴' : '▾'}</span></button>
          <span title={selectedDocument.originalFilename}>{selectedDocument.originalFilename}</span>
        </div>
        {(documentListExpanded ?? (selectedRecord.documents.length > 1)) && <aside className="panel viewer-document-rail" aria-label="Documents for selected record"><h3>Available documents ({selectedRecord.documents.length})</h3>{selectedRecord.documents.map((document) => <button key={document.documentId} type="button" aria-current={selectedDocumentId === document.documentId ? 'true' : undefined} className={selectedDocumentId === document.documentId ? 'selected' : ''} onClick={() => setSelectedDocumentId(document.documentId)}><Icon name="document" /><span><strong>{document.originalFilename}</strong><small>{formatDate(document.availableAt)}</small></span></button>)}</aside>}
        <PdfViewer key={selectedDocument.documentId} documentId={selectedDocument.documentId} filename={selectedDocument.originalFilename} persona={contentPersona} canRedact={canRedact} canDownload={canDownload} canPrint={canPrint} canTag={canTag} canComment={canComment} embedded onClose={() => setTab('results')} />
      </div>
    </div>}

    {tab === 'activity' && selectedRecord && <section className="panel business-tab-panel activity-workspace">
      <div className="activity-record-context"><div className="activity-record-title"><span className="kicker">Record information</span></div><div className="activity-context-values">{selectedRecord.identifiers.map((value) => <div key={value.fieldKey}><span>{value.displayLabel}</span><strong>{value.value}</strong></div>)}<div><span>Source</span><strong>{selectedRecord.sourceDisplayName}</strong></div><div><span>Total Documents</span><strong>{selectedRecord.documents.length}</strong></div></div></div>
      <div className="panel-heading activity-heading"><div><span className="kicker">Activity</span><h2>Activity history</h2></div><label className="activity-view-toggle"><input type="checkbox" checked={includeViews} onChange={(e) => setIncludeViews(e.target.checked)} /><span>Include document view events</span><small>Hidden by default</small></label></div>
      <div className="activity-filters"><label>Date Range<select value={activityRange} onChange={(e) => setActivityRange(e.target.value)}><option value="30">Last 30 days</option><option value="90">Last 90 days</option><option value="365">Last year</option><option value="all">All time</option></select></label><label>Action Type<select value={activityAction} onChange={(e) => setActivityAction(e.target.value)}><option value="">All actions</option><option value="DOCUMENT_DOWNLOADED">Download</option><option value="DOCUMENT_PRINTED">Print</option><option value="DOCUMENT_TAG_ADDED">Tag added</option><option value="DOCUMENT_COMMENT_ADDED">Comment added</option><option value="DOCUMENT_PERMANENTLY_REDACTED">Permanent redaction</option><option value="DOCUMENT_AVAILABLE">Document available</option>{includeViews && <option value="DOCUMENT_VIEW_OPENED">Document viewed</option>}</select></label><label>Performed By<input value={activityActor} onChange={(e) => setActivityActor(e.target.value)} placeholder="All users" /></label><button className="secondary" onClick={() => loadActivity()} disabled={activityLoading}>{activityLoading ? 'Loading…' : 'Apply filters'}</button></div>
      <div className="business-result-table-wrap"><table className="business-result-table activity-table"><thead><tr><th>Date & Time</th><th>Action Type</th><th>Details</th><th>Performed By</th><th>Document</th><th>Source</th></tr></thead><tbody>{activityRows.map((row) => <tr key={row.auditEventId}><td data-label="Date & Time">{formatDate(row.occurredAt)}</td><td data-label="Action Type"><span className="activity-action"><Icon name={iconForAction(row.action)} />{actionLabel(row.action)}</span></td><td data-label="Details">{row.outcome === 'SUCCESS' ? actionLabel(row.action) : `${actionLabel(row.action)} · ${row.outcome}`}</td><td data-label="Performed By">{row.actorId}</td><td data-label="Document"><button className="link-button" onClick={() => openViewer(row.documentId)}>{row.documentName}</button></td><td data-label="Source">{selectedRecord.sourceDisplayName}</td></tr>)}</tbody></table></div>
      {!activityLoading && activityRows.length === 0 && <p className="muted activity-empty">No activity matches the current filters.</p>}
    </section>}
  </div>
}
