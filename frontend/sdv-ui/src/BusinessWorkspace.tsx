import { FormEvent, useEffect, useMemo, useRef, useState } from 'react'
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

const actionTone = (action: string) => action.includes('REDACT') ? 'redaction' : action.includes('VIEW') || action.includes('PAGE') || action.includes('SEARCHED') ? 'view' : 'default'

export default function BusinessWorkspace({ persona, capabilities, onViewerStateChange }: Props) {
  const sourcePreferenceKey = `sdv.businessWorkspace.sourceId.${persona}`
  const contentPersona: ContentPersona = persona === 'steward' ? 'steward' : 'business'
  const [tab, setTab] = useState<BusinessTab>('search')
  const viewerContainerRef = useRef<HTMLDivElement | null>(null)
  const [schema, setSchema] = useState<SearchSchema>({ sources: [] })
  const [sourceId, setSourceId] = useState('')
  const [criteria, setCriteria] = useState<Record<string, string>>({})
  const [dateFrom, setDateFrom] = useState('')
  const [dateTo, setDateTo] = useState('')
  const [updatedFrom, setUpdatedFrom] = useState('')
  const [updatedTo, setUpdatedTo] = useState('')
  const [results, setResults] = useState<RecordSummary[]>([])
  const [selectedRecord, setSelectedRecord] = useState<BusinessRecord | null>(null)
  const [inspectorOpen, setInspectorOpen] = useState(false)
  const [selectedDocumentId, setSelectedDocumentId] = useState('')
  const [searching, setSearching] = useState(false)
  const [opening, setOpening] = useState(false)
  const [searchPage, setSearchPage] = useState(1)
  const [searchHasMore, setSearchHasMore] = useState(false)
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

  // Size the Viewer from its actual position in the viewport, not a
  // hard-coded header offset. Keep the sidebar footer inside this space.
  useEffect(() => {
    if (tab !== 'viewer') return
    const element = viewerContainerRef.current
    if (!element) return
    const updateHeight = () => {
      const top = element.getBoundingClientRect().top
      const remaining = Math.max(160, Math.floor(window.innerHeight - top - 12))
      element.style.height = `${remaining}px`
    }
    updateHeight()
    window.addEventListener('resize', updateHeight)
    const bar = element.closest('.bw')?.querySelector('.bw-bar')
    const observer = typeof ResizeObserver !== 'undefined' && bar
      ? new ResizeObserver(updateHeight)
      : null
    if (bar && observer) observer.observe(bar)
    return () => {
      window.removeEventListener('resize', updateHeight)
      observer?.disconnect()
    }
  }, [tab])

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
    setSearchPage(1)
    setSearchHasMore(false)
    setSelectedRecord(null)
    setInspectorOpen(false)
    setSelectedDocumentId('')
    setTab('search')
  }, [sourceId, sourcePreferenceKey])

  const isoStart = (value: string) => value ? new Date(`${value}T00:00:00`).toISOString() : null
  const isoEnd = (value: string) => value ? new Date(`${value}T23:59:59.999`).toISOString() : null

  async function loadSearchPage(requestedPage: number) {
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
          page: requestedPage,
          pageSize: 25,
        }),
      })
      setResults(response.records)
      setSearchPage(response.page)
      setSearchHasMore(response.hasMore)
      setSelectedRecord(null)
      setInspectorOpen(false)
      setSelectedDocumentId('')
      setTab('results')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Search failed.')
    } finally {
      setSearching(false)
    }
  }

  async function runSearch(event: FormEvent) {
    event.preventDefault()
    await loadSearchPage(1)
  }

  async function openRecord(recordRef: string, openFirstDocument = false) {
    if (opening) return
    setOpening(true)
    setError('')
    try {
      const value = await contentApi<BusinessRecord>(`/api/v1/business/records/${encodeURIComponent(recordRef)}`, contentPersona)
      const firstDocumentId = value.documents[0]?.documentId ?? ''
      setSelectedRecord(value)
      setSelectedDocumentId(firstDocumentId)
      setInspectorOpen(!(openFirstDocument && canView && firstDocumentId))
      if (openFirstDocument && canView && firstDocumentId) {
        setTab('viewer')
      }
      // No documents or no view permission: remain in Results with the record inspector.
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

  function closeRecord() {
    setInspectorOpen(false)
    setSelectedRecord(null)
    setSelectedDocumentId('')
  }

  function clearSearch() {
    setCriteria({})
    setDateFrom('')
    setDateTo('')
    setUpdatedFrom('')
    setUpdatedTo('')
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
  const secondaryIdentifiers = selectedRecord?.identifiers.slice(1) ?? []
  const selectedIdentifierKeys = new Set(selectedRecord?.identifiers.map((value) => value.fieldKey) ?? [])
  const selectedRecordDetails = selectedRecord?.metadata.filter((value) => !selectedIdentifierKeys.has(value.fieldKey)) ?? []

  const sourceFields = source?.fields ?? []
  const identifierFields = sourceFields.filter((field) => field.businessIdentifier)
  const detailFields = sourceFields.filter((field) => !field.businessIdentifier)
  const resultFields = sourceFields.slice(0, 5)
  const appliedCriteria = sourceFields.filter((field) => criteria[field.fieldKey]?.trim())
  const hasCriteria = appliedCriteria.length > 0 || dateFrom || dateTo || updatedFrom || updatedTo
  const showRecordContext = (tab === 'viewer' || tab === 'activity') && selectedRecord

  const renderField = (field: SearchField) => <label key={field.fieldKey} className="bw-field">
    <span className="bw-field-label">
      <span>{field.displayLabel}</span>
      <small>{searchHint(field)}</small>
    </span>
    <input
      className={field.businessIdentifier ? 'bw-mono' : undefined}
      data-vero-field-kind={field.fieldKey === 'serviceEntity' ? 'short-code' : undefined}
      value={criteria[field.fieldKey] ?? ''}
      inputMode={field.normalization === 'DIGITS_ONLY' ? 'numeric' : undefined}
      autoComplete="off"
      onChange={(event) => setCriteria((current) => ({ ...current, [field.fieldKey]: event.target.value }))}
    />
  </label>

  const stages: { id: BusinessTab; label: string; icon: Parameters<typeof Icon>[0]['name']; disabled: boolean; count?: number }[] = [
    { id: 'search', label: 'Search', icon: 'search', disabled: false },
    { id: 'results', label: 'Results', icon: 'list', disabled: results.length === 0, count: results.length || undefined },
    { id: 'viewer', label: 'Document Viewer', icon: 'document', disabled: !selectedDocument || !canView },
    { id: 'activity', label: 'Activity', icon: 'operations', disabled: !selectedRecord },
  ]

  return <div className={`bw bw-stage-${tab}`}>
    <div className="bw-bar">
      <nav className="bw-rail" aria-label="Business document workflow">
        {stages.map((stage) => <button
          key={stage.id}
          type="button"
          className={tab === stage.id ? 'active' : ''}
          aria-current={tab === stage.id ? 'step' : undefined}
          disabled={stage.disabled}
          onClick={() => { if (stage.id === 'results') setInspectorOpen(false); setTab(stage.id) }}
        >
          <Icon name={stage.icon} />
          <span>{stage.label}</span>
          {stage.count !== undefined && <span className="bw-rail-count" aria-hidden="true">{stage.count}</span>}
        </button>)}
      </nav>
      {showRecordContext && (
        <dl className="bw-context" aria-label="Selected record context">
          {selectedRecord.identifiers.slice(0, 3).map((value) => <div key={value.fieldKey}><dt>{value.displayLabel}</dt><dd className="bw-mono">{value.value}</dd></div>)}
          <div className="bw-context-source"><dt>Source</dt><dd>{selectedRecord.sourceDisplayName}</dd></div>
        </dl>
      )}
    </div>

    {error && <div className="bw-alert" role="alert"><Icon name="alert" /><span>{error}</span></div>}

    {tab === 'search' && <section className="bw-search ui-workspace-fluid" aria-labelledby="bw-search-title">
      <header className="bw-search-head ui-workspace-header">
        <div className="bw-search-intro ui-workspace-intro">
          <p className="bw-eyebrow">Business search</p>
          <h2 id="bw-search-title">Find a business record</h2>
          <p className="bw-lede ui-workspace-lede">Search by any identifier or detail, then open the record&apos;s documents in the secure viewer.</p>
        </div>
        <label className="bw-source ui-workspace-aside">
          <span className="bw-source-label"><Icon name="storage" />Source</span>
          <select value={sourceId} onChange={(event) => setSourceId(event.target.value)} aria-label="Search source">{schema.sources.map((item) => <option key={item.sourceId} value={item.sourceId}>{item.displayName}</option>)}</select>
        </label>
      </header>

      <form className="bw-sheet ui-workspace-surface" onSubmit={runSearch}>
        {!source && <div className="bw-sheet-loading" aria-busy="true">Loading search fields…</div>}
        {identifierFields.length > 0 && <fieldset className="bw-group ui-workspace-section">
          <legend>Identifiers</legend>
          <div className="bw-field-grid">{identifierFields.map(renderField)}</div>
        </fieldset>}
        {detailFields.length > 0 && <fieldset className="bw-group ui-workspace-section">
          <legend>Record details</legend>
          <div className="bw-field-grid">{detailFields.map(renderField)}</div>
        </fieldset>}
        <fieldset className="bw-group ui-workspace-section">
          <legend>Dates</legend>
          <div className="bw-date-grid">
            <div className="bw-range" role="group" aria-label="Date Range">
              <span className="bw-field-label"><span>Date Range</span><small>Document date</small></span>
              <div className="bw-range-inputs"><input aria-label="Date Range from" type="date" value={dateFrom} onChange={(e) => setDateFrom(e.target.value)} /><span aria-hidden="true">to</span><input aria-label="Date Range to" type="date" value={dateTo} onChange={(e) => setDateTo(e.target.value)} /></div>
            </div>
            <div className="bw-range" role="group" aria-label="Last Updated">
              <span className="bw-field-label"><span>Last Updated</span><small>Latest submission</small></span>
              <div className="bw-range-inputs"><input aria-label="Last Updated from" type="date" value={updatedFrom} onChange={(e) => setUpdatedFrom(e.target.value)} /><span aria-hidden="true">to</span><input aria-label="Last Updated to" type="date" value={updatedTo} onChange={(e) => setUpdatedTo(e.target.value)} /></div>
            </div>
          </div>
        </fieldset>
        <footer className="bw-sheet-actions ui-workspace-action-bar">
          <p>Any one field or date filter is enough to search.</p>
          <div className="bw-actions-row ui-action-row">
            <button type="button" className="bw-btn bw-btn-quiet" onClick={clearSearch} disabled={!hasCriteria}>Clear</button>
            <button type="submit" className="bw-btn bw-btn-primary" disabled={searching || !sourceId}><Icon name="search" />{searching ? 'Searching…' : 'Search records'}</button>
          </div>
        </footer>
      </form>
    </section>}

    {tab === 'results' && <section className="bw-results ui-workspace-fluid" aria-labelledby="bw-results-title">
      <header className="bw-results-head ui-workspace-toolbar">
        <div className="bw-results-title">
          <h2 id="bw-results-title"><span className="bw-tabular">{results.length}</span> {searchPage === 1 && !searchHasMore ? `matching ${results.length === 1 ? 'record' : 'records'}` : 'records on this page'}</h2>
          <p>{source?.displayName ?? 'Selected source'} <span aria-hidden="true">·</span> Page {searchPage}</p>
        </div>
        <ul className="bw-criteria" aria-label="Applied search criteria">
          {appliedCriteria.map((field) => <li key={field.fieldKey}><span>{field.displayLabel}</span><strong className={field.businessIdentifier ? 'bw-mono' : undefined}>{criteria[field.fieldKey].trim()}</strong></li>)}
          {(dateFrom || dateTo) && <li><span>Date Range</span><strong>{dateFrom || '…'} – {dateTo || '…'}</strong></li>}
          {(updatedFrom || updatedTo) && <li><span>Last Updated</span><strong>{updatedFrom || '…'} – {updatedTo || '…'}</strong></li>}
        </ul>
        <button type="button" className="bw-btn bw-btn-secondary" onClick={() => setTab('search')}><Icon name="edit" />Modify search</button>
      </header>

      <div className={`bw-results-body ${selectedRecord && inspectorOpen ? 'has-inspector' : ''}`}>
        <section className="bw-ledger ui-workspace-surface" aria-busy={opening || searching}>
          {(opening || searching) && <div className="bw-progress" aria-hidden="true" />}
          {results.length > 0 && <div className="bw-table-scroll">
            <table className="bw-table business-result-table">
              <thead><tr>
                {resultFields.map((field) => <th key={field.fieldKey}>{field.displayLabel}</th>)}
                <th className="bw-col-num">Documents</th>
                <th>Last Updated</th>
                <th className="bw-col-open"><span className="sr-only">Open record</span></th>
              </tr></thead>
              <tbody>{results.map((item) => {
                const values = new Map([...item.identifiers, ...item.metadataPreview].map((value) => [value.fieldKey, value.value]))
                const isSelected = selectedRecord?.recordRef === item.recordRef
                return <tr key={item.recordRef} className={isSelected ? 'selected' : ''} onClick={() => openRecord(item.recordRef)}>
                  {resultFields.map((field, index) => <td key={field.fieldKey} data-label={field.displayLabel} className={`${index === 0 ? 'bw-cell-primary' : ''} ${field.businessIdentifier ? 'bw-mono' : ''}`}>{values.get(field.fieldKey) ?? '—'}</td>)}
                  <td className="bw-col-num" data-label="Documents"><span className={`bw-doc-count ${item.availableDocumentCount ? '' : 'is-empty'}`}><Icon name="document" />{item.availableDocumentCount || 'None'}</span></td>
                  <td className="bw-cell-date" data-label="Last Updated">{formatDate(item.latestSubmissionAt)}</td>
                  <td className="bw-col-open"><button type="button" className="bw-open-record" aria-label={`View PDF for record ${values.get(resultFields[0]?.fieldKey) ?? item.recordRef}`} onClick={(event) => { event.stopPropagation(); void openRecord(item.recordRef, true) }}><Icon name="arrowUpRight" /></button></td>
                </tr>
              })}</tbody>
            </table>
          </div>}
          {results.length === 0 && <div className="bw-empty">
            <Icon name="search" />
            <strong>No matching records</strong>
            <span>Try fewer fields or a wider date range.</span>
            <button type="button" className="bw-btn bw-btn-secondary" onClick={() => setTab('search')}>Modify search</button>
          </div>}
          {results.length > 0 && <nav className="bw-pagination" aria-label="Search result pages">
            <button type="button" className="bw-btn bw-btn-quiet" disabled={searching || searchPage <= 1} onClick={() => loadSearchPage(searchPage - 1)}><Icon name="chevronLeft" />Previous</button>
            <span>Page <strong className="bw-tabular">{searchPage}</strong></span>
            <button type="button" className="bw-btn bw-btn-quiet" disabled={searching || !searchHasMore} onClick={() => loadSearchPage(searchPage + 1)}>Next<Icon name="chevronRight" /></button>
          </nav>}
        </section>

        {selectedRecord && inspectorOpen && <>
          <div className="bw-scrim" role="presentation" onClick={closeRecord} />
          <aside className="bw-inspector" aria-label="Selected record inspector">
            <header className="bw-inspector-head">
              <div className="bw-inspector-identity">
                <p className="bw-eyebrow">Selected record</p>
                <p className="bw-inspector-label">{primaryIdentifier?.displayLabel}</p>
                <h3 className="bw-tabular">{primaryIdentifier?.value}</h3>
              </div>
              <button type="button" className="bw-icon-btn" onClick={closeRecord} aria-label="Close selected record details"><span aria-hidden="true">×</span></button>
            </header>

            <dl className="bw-facts">
              {secondaryIdentifiers.map((value) => <div key={value.fieldKey}><dt>{value.displayLabel}</dt><dd className="bw-mono">{value.value}</dd></div>)}
              {selectedRecordDetails.map((value) => <div key={value.fieldKey}><dt>{value.displayLabel}</dt><dd>{value.value}</dd></div>)}
              <div><dt>Last Updated</dt><dd>{formatDate(selectedRecord.latestSubmissionAt)}</dd></div>
            </dl>

            <section className="bw-docs" aria-labelledby="bw-docs-title">
              <header><h4 id="bw-docs-title">Documents</h4><span>{selectedRecord.documents.length} available</span></header>
              {selectedRecord.documents.length === 0 && <p className="bw-docs-empty">No documents are available on this record.</p>}
              <ul>{selectedRecord.documents.map((document) => {
                const isActive = selectedDocumentId === document.documentId
                return <li key={document.documentId}>
                  <button type="button" className={isActive ? 'selected' : ''} aria-pressed={isActive} onClick={() => setSelectedDocumentId(document.documentId)} onDoubleClick={() => canView && openViewer(document.documentId)}>
                    <span className="bw-doc-icon"><Icon name="document" /></span>
                    <span className="bw-doc-copy"><strong>{document.originalFilename}</strong><small>{formatDate(document.availableAt)}</small></span>
                    <span className={`bw-doc-check${isActive ? ' is-on' : ''}`} aria-hidden="true" />
                  </button>
                </li>
              })}</ul>
            </section>

            <footer className="bw-inspector-actions">
              <button type="button" className="bw-btn bw-btn-primary bw-btn-block" disabled={!selectedDocumentId || !canView} onClick={() => openViewer()}>Open document <Icon name="arrowUpRight" /></button>
              <button type="button" className="bw-btn bw-btn-quiet bw-btn-block" onClick={() => setTab('activity')}><Icon name="operations" />Record activity</button>
            </footer>
          </aside>
        </>}
      </div>
    </section>}

    {tab === 'viewer' && selectedRecord && selectedDocument && <div className="bw-viewer" ref={viewerContainerRef}>
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
        onClose={() => { setInspectorOpen(false); setTab('results') }}
      />
    </div>}

    {tab === 'activity' && selectedRecord && <section className="bw-activity" aria-labelledby="bw-activity-title">
      <header className="bw-activity-head">
        <div>
          <h2 id="bw-activity-title">Activity</h2>
          <p>Who did what, and when, on documents in this record. Newest first.</p>
        </div>
        <button type="button" className="bw-btn bw-btn-secondary" onClick={() => selectedDocument ? setTab('viewer') : setTab('results')}><Icon name="chevronLeft" />Back to document</button>
      </header>

      <div className="bw-ledger">
        <div className="bw-filters">
          <label className="bw-field"><span className="bw-field-label"><span>Document</span></span><select value={activityDocumentId} onChange={(e) => setActivityDocumentId(e.target.value)}><option value="">All documents in record ({selectedRecord.documents.length})</option>{selectedRecord.documents.map((document) => <option key={document.documentId} value={document.documentId}>{document.originalFilename}</option>)}</select></label>
          <label className="bw-field"><span className="bw-field-label"><span>Action Type</span></span><select value={activityAction} onChange={(e) => setActivityAction(e.target.value)}><option value="">All actions</option><option value="DOCUMENT_DOWNLOADED">Download</option><option value="DOCUMENT_PRINTED">Print</option><option value="DOCUMENT_TAG_ADDED">Tag added</option><option value="DOCUMENT_COMMENT_ADDED">Comment added</option><option value="DOCUMENT_PERMANENTLY_REDACTED">Permanent redaction</option><option value="DOCUMENT_AVAILABLE">Document available</option>{includeViews && <option value="DOCUMENT_VIEW_OPENED">Document viewed</option>}</select></label>
          <label className="bw-field"><span className="bw-field-label"><span>Performed By</span></span><input value={activityActor} onChange={(e) => setActivityActor(e.target.value)} placeholder="All users" /></label>
          <label className="bw-field"><span className="bw-field-label"><span>Date Range</span></span><select value={activityRange} onChange={(e) => setActivityRange(e.target.value)}><option value="30">Last 30 days</option><option value="90">Last 90 days</option><option value="365">Last year</option><option value="all">All time</option></select></label>
        </div>
        <div className="bw-activity-meta">
          <span className="bw-tabular">{filteredActivityRows.length} {filteredActivityRows.length === 1 ? 'event' : 'events'}</span>
          <label className="bw-check"><input type="checkbox" checked={includeViews} onChange={(e) => setIncludeViews(e.target.checked)} /><span>Include document view events</span><small>Hidden by default</small></label>
          <button type="button" className="bw-btn bw-btn-secondary" onClick={() => loadActivity()} disabled={activityLoading}>{activityLoading ? 'Loading…' : 'Refresh'}</button>
        </div>
        {activityLoading && <div className="bw-progress" aria-hidden="true" />}
        {filteredActivityRows.length > 0 && <div className="bw-table-scroll">
          <table className="bw-table bw-activity-table">
            <thead><tr><th>Date &amp; Time</th><th>Event</th><th>Document</th><th>User</th><th>Outcome</th></tr></thead>
            <tbody>{filteredActivityRows.map((row) => <tr key={row.auditEventId}>
              <td className="bw-cell-date" data-label="Date & Time">{formatDate(row.occurredAt)}</td>
              <td data-label="Event"><span className={`bw-event bw-event-${actionTone(row.action)}`}>{actionLabel(row.action)}</span></td>
              <td data-label="Document"><button type="button" className="bw-link" onClick={() => openViewer(row.documentId)}><Icon name="document" />{row.documentName}</button></td>
              <td className="bw-mono bw-cell-actor" data-label="User">{row.actorId}</td>
              <td data-label="Outcome"><span className={`bw-outcome ${row.outcome === 'SUCCESS' ? 'is-success' : 'is-failed'}`}>{row.outcome === 'SUCCESS' ? 'Success' : 'Failed'}</span></td>
            </tr>)}</tbody>
          </table>
        </div>}
        {!activityLoading && filteredActivityRows.length === 0 && <div className="bw-empty bw-empty-compact"><Icon name="operations" /><strong>No activity matches these filters</strong><span>Widen the date range or include document view events.</span></div>}
      </div>
    </section>}
  </div>
}
