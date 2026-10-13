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
  onViewerStateChange?: (active: boolean) => void
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

export default function BusinessWorkspace({ persona, capabilities, onViewerStateChange }: Props) {
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
  const [searching, setSearching] = useState(false)
  const [opening, setOpening] = useState(false)
  const [truncated, setTruncated] = useState(false)
  const [error, setError] = useState('')
  const [includeViews, setIncludeViews] = useState(false)
  const [activityRange, setActivityRange] = useState('90')
  const [activityDocumentId, setActivityDocumentId] = useState('')
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
    onViewerStateChange?.(tab === 'viewer')
    return () => onViewerStateChange?.(false)
  }, [tab, onViewerStateChange])

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
    if (tab === 'activity' && selectedRecord) {
      setActivityDocumentId((current) => current || selectedDocumentId || selectedRecord.documents[0]?.documentId || '')
      loadActivity().catch(() => undefined)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, selectedRecord?.recordRef, includeViews])

  const filteredActivityRows = activityDocumentId
    ? activityRows.filter((row) => row.documentId === activityDocumentId)
    : activityRows

  const primaryIdentifier = selectedRecord?.identifiers[0]
  const selectedIdentifierKeys = new Set(selectedRecord?.identifiers.map((value) => value.fieldKey) ?? [])
  const selectedRecordDetails = selectedRecord?.metadata.filter((value) => !selectedIdentifierKeys.has(value.fieldKey)) ?? []

  return <div className="business-workspace">
    <div className="business-workflow-bar">
      <nav className="business-tabs" aria-label="Business document workflow">
        <button className={tab === 'search' ? 'active' : ''} onClick={() => setTab('search')}><Icon name="search" />Search</button>
        <button className={tab === 'results' ? 'active' : ''} disabled={results.length === 0} onClick={() => setTab('results')}><Icon name="list" />Results</button>
        <button className={tab === 'viewer' ? 'active' : ''} disabled={!selectedDocument || !canView} onClick={() => setTab('viewer')}><Icon name="document" />Document Viewer</button>
        <button className={tab === 'activity' ? 'active' : ''} disabled={!selectedRecord} onClick={() => setTab('activity')}><Icon name="operations" />Activity</button>
      </nav>
      {(tab === 'viewer' || tab === 'activity') && selectedRecord && (
        <div className="business-workflow-record-context" aria-label="Selected record context">
          {selectedRecord.identifiers.slice(0, 3).map((value) => <span key={value.fieldKey}><small>{value.displayLabel}</small><strong>{value.value}</strong></span>)}
          <span><small>Source</small><strong>{selectedRecord.sourceDisplayName}</strong></span>
        </div>
      )}
    </div>

    {error && <div className="banner error top-gap">{error}</div>}

    {tab === 'search' && <>
      <section className="business-search-screen business-search-v0">
        <div className="business-search-hero business-search-hero-v0">
          <div className="business-search-title">
            <span className="kicker">Business Search</span>
            <h2>Find a business record</h2>
            <p className="muted">Search by any identifier or detail below, then open the record's documents in the secure viewer.</p>
          </div>
          <label className="business-source-context business-source-context-v0">
            <Icon name="storage" />
            <span className="business-source-context-copy">
              <small>Searching in</small>
              <select value={sourceId} onChange={(event) => setSourceId(event.target.value)} aria-label="Search source">{schema.sources.map((item) => <option key={item.sourceId} value={item.sourceId}>{item.displayName}</option>)}</select>
            </span>
            <strong>{source?.fields.length ?? 0} fields</strong>
          </label>
        </div>
      </section>
      <div className="business-search-body-v0">
        <form className="panel business-search-form business-search-form-premium business-search-form-v0" onSubmit={runSearch}>
          <section className="business-search-record-section">
            <div className="business-search-section-heading">
              <h3>{source?.displayName} record</h3>
              <span>Fields shown match the selected source</span>
            </div>
            <div className="business-search-grid business-search-grid-v0">
              {source?.fields.map((field) => <label key={field.fieldKey}>
                <span className="business-field-label">
                  <span>{field.displayLabel}{field.businessIdentifier && <em>ID</em>}</span>
                  <small>{searchHint(field)}</small>
                </span>
                <input
                  value={criteria[field.fieldKey] ?? ''}
                  inputMode={field.normalization === 'DIGITS_ONLY' ? 'numeric' : undefined}
                  autoComplete="off"
                  onChange={(event) => setCriteria((current) => ({ ...current, [field.fieldKey]: event.target.value }))}
                />
              </label>)}
            </div>
          </section>
          <section className="business-search-date-section">
            <span className="business-section-label">Narrow by date</span>
            <div className="business-date-grid-v0">
              <fieldset>
                <legend><span>Date Range</span><small>Document date</small></legend>
                <div><input aria-label="Date Range from" type="date" value={dateFrom} onChange={(e) => setDateFrom(e.target.value)} /><span>–</span><input aria-label="Date Range to" type="date" value={dateTo} onChange={(e) => setDateTo(e.target.value)} /></div>
              </fieldset>
              <fieldset>
                <legend><span>Last Updated</span><small>Latest submission</small></legend>
                <div><input aria-label="Last Updated from" type="date" value={updatedFrom} onChange={(e) => setUpdatedFrom(e.target.value)} /><span>–</span><input aria-label="Last Updated to" type="date" value={updatedTo} onChange={(e) => setUpdatedTo(e.target.value)} /></div>
              </fieldset>
            </div>
          </section>
          <div className="business-search-footer business-search-footer-v0">
            <span className="business-search-guidance">Any one field or date filter is enough to search.</span>
            <div className="form-actions"><button type="button" className="business-clear-action" onClick={() => { setCriteria({}); setDateFrom(''); setDateTo(''); setUpdatedFrom(''); setUpdatedTo('') }}>Clear</button><button type="submit" className="business-primary-action" disabled={searching || !sourceId}><Icon name="search" />{searching ? 'Searching…' : 'Search records'}</button></div>
          </div>
        </form>
      </div>
    </>}

    {tab === 'results' && <>
      <div className="business-results-topbar-v0">
        <div className="business-results-title-v0">
          <h2><strong>{results.length}</strong> matching {results.length === 1 ? 'record' : 'records'}</h2>
          <span>in {source?.displayName ?? 'selected source'}</span>
        </div>
        <div className="business-results-summary">
          <div className="business-search-chips" aria-label="Applied search criteria">
            {(source?.fields ?? []).filter((field) => criteria[field.fieldKey]?.trim()).map((field) => <span key={field.fieldKey}><small>{field.displayLabel}</small><strong>{criteria[field.fieldKey].trim()}</strong></span>)}
            {(dateFrom || dateTo) && <span><small>Date Range</small><strong>{dateFrom || '…'} – {dateTo || '…'}</strong></span>}
            {(updatedFrom || updatedTo) && <span><small>Last Updated</small><strong>{updatedFrom || '…'} – {updatedTo || '…'}</strong></span>}
          </div>
          <button type="button" className="secondary business-modify-search" onClick={() => setTab('search')}><Icon name="edit" />Modify search</button>
        </div>
      </div>
      <div className="business-results-content-v0">
        {truncated && <div className="banner notice">Result window reached. Refine the search to narrow the list.</div>}
        <div className="business-results-layout business-results-layout-v0">
          <section className="panel business-results-panel business-results-panel-v0">
            <div className="business-result-table-wrap"><table className="business-result-table business-result-table-v0"><thead><tr><th></th>{(source?.fields ?? []).slice(0, 5).map((field) => <th key={field.fieldKey}>{field.displayLabel}</th>)}<th>Documents</th><th>Last Updated</th></tr></thead><tbody>{results.map((item) => {
              const values = new Map([...item.identifiers, ...item.metadataPreview].map((value) => [value.fieldKey, value.value]))
              return <tr key={item.recordRef} className={selectedRecord?.recordRef === item.recordRef ? 'selected' : ''} onClick={() => openRecord(item.recordRef)}><td data-label="Select"><input type="radio" name="selected-record" aria-label={`Select record ${item.recordRef}`} readOnly checked={selectedRecord?.recordRef === item.recordRef} /></td>{(source?.fields ?? []).slice(0,5).map((field, index) => <td key={field.fieldKey} data-label={field.displayLabel} className={index === 0 ? 'primary-cell' : ''}>{values.get(field.fieldKey) ?? '—'}</td>)}<td data-label="Documents"><span className="document-count-pill"><Icon name="document" />{item.availableDocumentCount || 'None'}</span></td><td data-label="Last Updated">{formatDate(item.latestSubmissionAt)}</td></tr>
            })}</tbody></table></div>
            {results.length === 0 && <div className="empty-state"><Icon name="search" /><strong>No matching records</strong><span>Try fewer fields or a wider date range.</span><button type="button" onClick={() => setTab('search')}>Modify search</button></div>}
          </section>
          <aside className="panel selected-record-panel selected-record-panel-v0">
            {!selectedRecord ? <div className="selected-record-empty-v0"><span className="selected-record-empty-icon"><Icon name="document" /></span><strong>No record selected</strong><p>Choose a row to review its details and documents here.</p></div> : <>
              <div className="selected-record-identity-v0">
                <span className="business-section-label">Selected record</span>
                <small>{primaryIdentifier?.displayLabel}</small>
                <h2>{primaryIdentifier?.value}</h2>
                {selectedRecord.identifiers.length > 1 && <p>{selectedRecord.identifiers.slice(1).map((value) => `${value.displayLabel} ${value.value}`).join(' · ')}</p>}
              </div>
              {selectedRecordDetails.length > 0 && <div className="selected-record-details-v0">{selectedRecordDetails.map((value) => <div key={value.fieldKey}><span>{value.displayLabel}</span><strong>{value.value}</strong></div>)}<div><span>Last Updated</span><strong>{formatDate(selectedRecord.latestSubmissionAt)}</strong></div></div>}
              <div className="selected-record-documents-v0">
                <div className="selected-record-documents-heading"><strong>Documents</strong><span>{selectedRecord.documents.length} available</span></div>
                <div className="compact-document-list compact-document-list-v0">{selectedRecord.documents.map((document) => <button key={document.documentId} className={selectedDocumentId === document.documentId ? 'selected' : ''} onClick={() => setSelectedDocumentId(document.documentId)} onDoubleClick={() => canView && openViewer(document.documentId)}><span className="document-tile-icon"><Icon name="document" /></span><span><strong>{document.originalFilename}</strong><small>{formatDate(document.availableAt)}</small></span>{selectedDocumentId === document.documentId && <span className="document-selected-check">✓</span>}</button>)}</div>
              </div>
              <div className="selected-record-actions-v0">
                <button className="business-primary-action wide-action" disabled={!selectedDocumentId || !canView} onClick={() => openViewer()}>Open document <span aria-hidden="true">→</span></button>
                <button className="business-ghost-action" onClick={() => setTab('activity')}><Icon name="operations" />Record activity</button>
              </div>
            </>}
          </aside>
        </div>
      </div>
    </>}

    {tab === 'viewer' && selectedRecord && selectedDocument && <div className="viewer-tab-layout premium-viewer-tab-layout">
      <PdfViewer
        key={selectedDocument.documentId}
        documentId={selectedDocument.documentId}
        filename={selectedDocument.originalFilename}
        documents={selectedRecord.documents}
        selectedDocumentId={selectedDocumentId}
        onSelectDocument={setSelectedDocumentId}
        persona={contentPersona}
        canRedact={canRedact}
        canDownload={canDownload}
        canPrint={canPrint}
        canTag={canTag}
        canComment={canComment}
        embedded
        onClose={() => setTab('results')}
      />
    </div>}

    {tab === 'activity' && selectedRecord && <div className="activity-page-v0">
      <div className="activity-page-heading-v0">
        <div><h2>Activity</h2><p>Who did what, and when, on documents in this record. Newest first.</p></div>
        <button type="button" className="secondary" onClick={() => selectedDocument ? setTab('viewer') : setTab('results')}>← Back to document</button>
      </div>
      <section className="activity-card-v0">
        <div className="activity-filters-v0">
          <label><span>Document</span><select value={activityDocumentId} onChange={(e) => setActivityDocumentId(e.target.value)}><option value="">All documents in record ({selectedRecord.documents.length})</option>{selectedRecord.documents.map((document) => <option key={document.documentId} value={document.documentId}>{document.originalFilename}</option>)}</select></label>
          <label><span>Action Type</span><select value={activityAction} onChange={(e) => setActivityAction(e.target.value)}><option value="">All actions</option><option value="DOCUMENT_DOWNLOADED">Download</option><option value="DOCUMENT_PRINTED">Print</option><option value="DOCUMENT_TAG_ADDED">Tag added</option><option value="DOCUMENT_COMMENT_ADDED">Comment added</option><option value="DOCUMENT_PERMANENTLY_REDACTED">Permanent redaction</option><option value="DOCUMENT_AVAILABLE">Document available</option>{includeViews && <option value="DOCUMENT_VIEW_OPENED">Document viewed</option>}</select></label>
          <label><span>Performed By</span><input value={activityActor} onChange={(e) => setActivityActor(e.target.value)} placeholder="All users" /></label>
          <label><span>Date Range</span><select value={activityRange} onChange={(e) => setActivityRange(e.target.value)}><option value="30">Last 30 days</option><option value="90">Last 90 days</option><option value="365">Last year</option><option value="all">All time</option></select></label>
        </div>
        <div className="activity-meta-v0">
          <span>{filteredActivityRows.length} {filteredActivityRows.length === 1 ? 'event' : 'events'}</span>
          <label><input type="checkbox" checked={includeViews} onChange={(e) => setIncludeViews(e.target.checked)} />Include document view events <small>· hidden by default</small></label>
          <button type="button" className="business-ghost-action activity-apply-v0" onClick={() => loadActivity()} disabled={activityLoading}>{activityLoading ? 'Loading…' : 'Refresh'}</button>
        </div>
        <div className="business-result-table-wrap"><table className="business-result-table activity-table activity-table-v0"><thead><tr><th>Date & Time</th><th>Event</th><th>Document</th><th>User</th><th>Outcome</th></tr></thead><tbody>{filteredActivityRows.map((row) => <tr key={row.auditEventId}><td data-label="Date & Time">{formatDate(row.occurredAt)}</td><td data-label="Event"><span className="activity-event-pill">{actionLabel(row.action)}</span></td><td data-label="Document"><button className="activity-document-link" onClick={() => openViewer(row.documentId)}><Icon name="document" />{row.documentName}</button></td><td data-label="User">{row.actorId}</td><td data-label="Outcome"><span className={row.outcome === 'SUCCESS' ? 'activity-outcome-success' : 'activity-outcome-failed'}><i />{row.outcome === 'SUCCESS' ? 'Success' : 'Failed'}</span></td></tr>)}</tbody></table></div>
        {!activityLoading && filteredActivityRows.length === 0 && <p className="activity-empty-v0">No activity matches these filters.</p>}
      </section>
    </div>}
  </div>
}
