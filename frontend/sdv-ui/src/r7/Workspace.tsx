import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { AlertTriangle, ArrowRight, ChevronDown, ChevronUp, FileText, Search, SlidersHorizontal, X } from 'lucide-react'
import { useStore } from './store'
import type { RecordItem, Source } from './data'
import { schema, search, record, toSource, toSummary, toRecord, documentActivity, SEARCH_PAGE_SIZE, type LiveDocumentActivity } from './liveApi'
import { Badge, Callout, Drawer, Empty, ErrorState, Skeleton, fmtDate, fmtRel, humanize } from './ui'
import { Viewer } from './Viewer'
import { MODE_HINT, MODE_WORD, QUICK_RANGES, alternativeFields, boxLabel, quickRange, rangeLabel, routeBoxInput, type Range } from './searchRouting'

type Criteria = Record<string, string>
/** received: when the submission arrived (server dateFrom/dateTo); updated: last activity (lastUpdatedFrom/To). */
type Applied = { sourceId: string; c: Criteria; received: Range; updated: Range; /** only records with missing documents */ incompleteOnly?: boolean }
const NO_RANGE: Range = { from: '', to: '' }
const emptyApplied = (sourceId: string): Applied => ({ sourceId, c: {}, received: NO_RANGE, updated: NO_RANGE })
type FieldValue = { label: string; value: string }
/** What the search result said about a record; kept apart so loading the full record does not change the row. */
type Summary = { identifiers: FieldValue[]; preview: FieldValue[]; docs: number; incomplete: boolean; expected: number; received: number }

function useMediaQuery(q: string) {
  const [m, setM] = useState(() => window.matchMedia(q).matches)
  useEffect(() => {
    const mq = window.matchMedia(q)
    const on = () => setM(mq.matches)
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [q])
  return m
}

// A date range alone is enough: the server lists every record in it, page by page.
const hasCriteria = (a: Pick<Applied, 'c' | 'received' | 'updated' | 'incompleteOnly'>) => Object.values(a.c).some((v) => v.trim()) || !!a.incompleteOnly || [a.received, a.updated].some((r) => !!r.from || !!r.to)
const uniqueLabels = (rows: FieldValue[][]) => [...new Set(rows.flat().map((f) => f.label))]
const valueOf = (list: FieldValue[] | undefined, label: string) => list?.find((f) => f.label === label)?.value ?? ''

export function Workspace() {
  const { records, setRecords, selectedRef, setSelectedRef, openDocId, setOpenDocId, can, setResultsShown, searchRequest } = useStore()
  const [sources, setSources] = useState<Source[]>([])
  const [status, setStatus] = useState<'loading' | 'live' | 'error'>('loading')
  const [failure, setFailure] = useState('')
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(false)
  const [summaries, setSummaries] = useState<Record<string, Summary>>({})
  const [sourceId, setSourceId] = useState('')
  const [applied, setApplied] = useState<Applied | null>(null)
  const [box, setBox] = useState('')
  const [boxMessage, setBoxMessage] = useState('')
  // The field and value of the last search typed in the box; used for the no-results suggestion only.
  const [boxSearch, setBoxSearch] = useState<{ key: string; value: string } | null>(null)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [panelOpen, setPanelOpen] = useState(false)
  const filtersButton = useRef<HTMLButtonElement>(null)
  const wide = useMediaQuery('(min-width: 1024px)')

  useEffect(() => { let active = true; schema().then((s) => { if (active) { setSources(s.sources.map(toSource)); setStatus('live') } })
    .catch((e) => { if (active) { setFailure(String(e)); setStatus('error') } }); return () => { active = false } }, [])
  useEffect(() => { if (!selectedRef) return; let active = true
    record(selectedRef).then((r) => { if (active) setRecords((prev) => prev.map((x) => (x.ref === selectedRef ? toRecord(r) : x))) })
      .catch((e) => { if (active) { setFailure(String(e)); setStatus('error') } })
    return () => { active = false }
  }, [selectedRef, setRecords])
  // A record chosen elsewhere (e.g. "Go to") opens the record panel.
  useEffect(() => { if (selectedRef) setPanelOpen(true) }, [selectedRef])
  useEffect(() => { if (!sources.length || sourceId) return; setSourceId(sources[0].id) }, [sources, sourceId])

  const activeSourceId = applied?.sourceId ?? sourceId
  const source = sources.find((s) => s.id === activeSourceId) ?? sources[0]
  const fields = useMemo(() => source?.fields.filter((f) => f.searchable) ?? [], [source])
  const label = boxLabel(fields)

  useEffect(() => {
    if (!applied) { setRecords([]); setSummaries({}); setHasMore(false); setStatus(sources.length ? 'live' : 'loading'); return }
    let active = true
    setStatus('loading')
    search(applied.sourceId, applied.c, applied.received, applied.updated, page, { incompleteOnly: applied.incompleteOnly }).then((r) => {
      if (!active) return
      setRecords(r.records.map((x) => toSummary(x, applied.sourceId)))
      setSummaries(Object.fromEntries(r.records.map((x) => [x.recordRef, {
        identifiers: x.identifiers.map((f) => ({ label: f.displayLabel, value: f.value })),
        preview: x.metadataPreview.map((f) => ({ label: f.displayLabel, value: f.value })),
        docs: x.availableDocumentCount, incomplete: x.incomplete,
        expected: x.incompleteExpectedCount ?? 0, received: x.incompleteReceivedCount ?? 0,
      }])))
      setHasMore(r.hasMore)
      setStatus('live')
    }).catch((e) => { if (active) { setFailure(String(e)); setRecords([]); setSummaries({}); setStatus('error') } })
    return () => { active = false }
  }, [applied, page, setRecords, sources.length])

  const results = records
  useEffect(() => { setResultsShown(!!applied && status === 'live' && results.length > 0); return () => setResultsShown(false) }, [applied, status, results.length, setResultsShown])
  const selected = records.find((r) => r.ref === selectedRef) ?? null
  const showPanel = !!selected && panelOpen

  const runSearch = (next: Applied | null) => {
    setPage(1)
    setApplied(next && hasCriteria(next) ? next : null)
    setSelectedRef(null)
    setPanelOpen(false)
    setBoxSearch(null)
  }
  const submitBox = (e?: FormEvent) => {
    e?.preventDefault()
    const route = routeBoxInput(box, fields)
    if (!route) return
    if (!route.ok) { setBoxMessage(route.message); return }
    setBoxMessage('')
    const base = applied ?? emptyApplied(activeSourceId)
    runSearch({ ...base, c: { ...base.c, [route.field.key]: route.value } })
    setBoxSearch({ key: route.field.key, value: route.value })
    setBox('')
  }
  const removeChip = (key: string) => {
    if (!applied) return
    if (key === 'received' || key === 'updated') return runSearch({ ...applied, [key]: NO_RANGE })
    if (key === 'incomplete') return runSearch({ ...applied, incompleteOnly: false })
    const c = { ...applied.c }
    delete c[key]
    runSearch({ ...applied, c })
  }
  // When a number typed in the box found nothing, offer each other identifier that takes the same input. One click, no automatic request.
  const boxField = boxSearch && applied?.c[boxSearch.key] === boxSearch.value ? fields.find((f) => f.key === boxSearch.key) : undefined
  const suggestions = boxField && boxSearch ? alternativeFields(boxField, boxSearch.value, fields) : []
  const searchAs = (key: string) => {
    if (!applied || !boxSearch) return
    const c = { ...applied.c }
    delete c[boxSearch.key]
    runSearch({ ...applied, c: { ...c, [key]: boxSearch.value } })
  }
  const clearAll = () => { setBox(''); setBoxMessage(''); runSearch(null) }

  const chips = applied ? [
    ...Object.entries(applied.c).filter(([, v]) => v.trim()).map(([key, v]) => {
      const f = source?.fields.find((x) => x.key === key)
      return { key, text: f ? `${f.label} ${MODE_WORD[f.mode]} ${v.trim()}` : `${key} ${v.trim()}` }
    }),
    ...(applied.incompleteOnly ? [{ key: 'incomplete', text: 'Missing documents' }] : []),
    ...(['received', 'updated'] as const).flatMap((key) => {
      const text = rangeLabel(key === 'received' ? 'Received' : 'Updated', applied[key])
      return text ? [{ key, text }] : []
    }),
  ] : []

  const openRecord = (ref: string) => { setSelectedRef(ref); setPanelOpen(true) }

  // Searches started elsewhere (Home): run them here once the sources are known; optionally select a record.
  const handledRequest = useRef(0)
  const [pendingSelect, setPendingSelect] = useState<string | null>(null)
  useEffect(() => {
    if (!searchRequest || handledRequest.current === searchRequest.id || !sources.length) return
    handledRequest.current = searchRequest.id
    const src = sources.find((s) => s.id === searchRequest.sourceId) ?? sources.find((s) => s.id === sourceId) ?? sources[0]
    const base: Applied = { ...emptyApplied(src.id), incompleteOnly: !!searchRequest.incompleteOnly, received: searchRequest.receivedLast30Days ? quickRange('Last 30 days') : NO_RANGE }
    setFiltersOpen(false)
    if (searchRequest.box !== undefined) {
      const route = routeBoxInput(searchRequest.box, src.fields.filter((f) => f.searchable))
      if (route && !route.ok) { setBox(searchRequest.box); setBoxMessage(route.message) }
      else if (route) { setBox(''); setBoxMessage(''); runSearch({ ...base, c: { [route.field.key]: route.value } }); setBoxSearch({ key: route.field.key, value: route.value }) }
    } else if (searchRequest.criteria || searchRequest.incompleteOnly || searchRequest.receivedLast30Days) {
      setBox(''); setBoxMessage(''); runSearch({ ...base, c: searchRequest.criteria ?? {} })
    }
    if (searchRequest.openFilters) setFiltersOpen(true)
    setPendingSelect(searchRequest.selectRef ?? null)
  }, [searchRequest, sources])
  useEffect(() => {
    if (!pendingSelect || status !== 'live' || !records.some((r) => r.ref === pendingSelect)) return
    openRecord(pendingSelect)
    setPendingSelect(null)
  }, [pendingSelect, status, records])
  const openDoc = (r: RecordItem, docId: string) => {
    if (!can('VIEW_PDF')) return
    setFailure('')
    setSelectedRef(r.ref)
    setOpenDocId(docId)
  }

  // Escape closes the side panel; the row stays selected in the list.
  useEffect(() => {
    if (!showPanel || !wide || openDocId || filtersOpen) return
    const k = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null
      if (e.key === 'Escape' && !(t && /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) setPanelOpen(false)
    }
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [showPanel, wide, openDocId, filtersOpen])

  const appliedSource = (applied ? sources.find((s) => s.id === applied.sourceId) : source) ?? source
  const summary = selected ? summaries[selected.ref] : undefined
  const panelTitle = selected ? (summary?.preview[0]?.value || selected.identifiers.map((i) => i.value).join(' · ')) : ''

  return (
    <div className={`ws${showPanel && wide ? ' has-insp' : ''}`}>
      <div className="ws-main">
        <section className="ws-bar" aria-label="Record search">
          <form className="ws-searchbar" role="search" onSubmit={submitBox}>
            <Search size={18} aria-hidden className="faint" />
            {chips.map((c) => (
              <span key={c.key} className="ws-chip">{c.text}
                <button type="button" onClick={() => removeChip(c.key)} aria-label={`Remove filter: ${c.text}`}><X size={13} /></button>
              </span>
            ))}
            <input type="search" className="ws-searchinput" aria-label="Search records" value={box} autoComplete="off" spellCheck={false}
              placeholder={label ?? 'Use Filters to search this source'} aria-describedby={boxMessage ? 'ws-box-message' : undefined}
              onChange={(e) => { setBox(e.target.value); setBoxMessage('') }}
              onKeyDown={(e) => { if (e.key === 'Escape') { e.preventDefault(); setBox(''); setBoxMessage('') } }} />
          </form>
          <button ref={filtersButton} type="button" className="v-btn" aria-expanded={filtersOpen} aria-controls="ws-filters" onClick={() => setFiltersOpen((o) => !o)}>
            <SlidersHorizontal size={16} aria-hidden />Filters{chips.length > 0 && <span className="ws-count" aria-label={`${chips.length} active`}>{chips.length}</span>}
          </button>
          <button type="button" className="v-btn quiet" onClick={clearAll} disabled={!applied && !box}>Clear</button>
          {filtersOpen && sources.length > 0 && (
            <FiltersPanel sources={sources} initial={applied ?? emptyApplied(activeSourceId)}
              onApply={(next) => { setFiltersOpen(false); setBox(''); setBoxMessage(''); runSearch(next); filtersButton.current?.focus() }}
              onClose={() => { setFiltersOpen(false); filtersButton.current?.focus() }} />
          )}
        </section>
        {boxMessage && <p id="ws-box-message" className="ws-box-message" role="status">{boxMessage}</p>}

        <section className="ws-results" aria-label="Search results">
          {status === 'loading' ? <Skeleton rows={7} /> : status === 'error' ? <ErrorState what="search results" /> : !applied ? (
            <Empty title={label ? `Search by ${label}` : 'Search with Filters'} icon={<Search size={20} />}>
              Type in the search bar and press Enter, or open Filters to search by any field.
            </Empty>
          ) : results.length === 0 ? (
            suggestions.length > 0 && boxField && boxSearch ? (
              <Empty title={`No ${boxField.label} ${boxSearch.value}`} action={<div className="row wrap" style={{ justifyContent: 'center', gap: 8 }}>
                {suggestions.map((f) => <button key={f.key} type="button" className="v-btn sm primary" onClick={() => searchAs(f.key)}>Search it as {f.label} instead</button>)}
                <button type="button" className="v-btn sm" onClick={() => setFiltersOpen(true)}>Open Filters</button>
              </div>}>
                The search box read this number as a {boxField.label}. It may belong to another field.
              </Empty>
            ) : (
            <Empty title="No matching records" action={<button type="button" className="v-btn sm" onClick={() => setFiltersOpen(true)}>Open Filters</button>}>
              Check the number for typos, remove a filter, or search a shorter part of a name. Exact fields must match in full.
            </Empty>
            )
          ) : (
            <>
              <div className="ws-results-head">
                <h2>{results.length}{hasMore ? '+' : ''} record{results.length === 1 && !hasMore ? '' : 's'} <span className="faint">· {appliedSource?.name ?? 'SDV'}</span></h2>
                <span className="faint">Sorted by last updated</span>
              </div>
              <ResultsTable results={results} summaries={summaries} selectedRef={selectedRef} onOpen={openRecord} />
              <div className="ws-pager">
                <span>Showing {(page - 1) * SEARCH_PAGE_SIZE + 1}–{(page - 1) * SEARCH_PAGE_SIZE + results.length}{hasMore ? ' · more on the next page' : ''} · up to {SEARCH_PAGE_SIZE} per page</span>
                <span className="row" style={{ gap: 6 }}>
                  <button className="v-btn sm" type="button" disabled={page === 1} onClick={() => { setPage((p) => p - 1); setSelectedRef(null); setPanelOpen(false) }}>Previous</button>
                  <button className="v-btn sm" type="button" disabled={!hasMore} onClick={() => { setPage((p) => p + 1); setSelectedRef(null); setPanelOpen(false) }}>Next</button>
                </span>
              </div>
            </>
          )}
        </section>
      </div>

      {showPanel && selected && wide && (
        <aside className="ws-inspector" aria-label="Record inspector">
          <header className="ws-insp-head">
            <div className="grow stack" style={{ gap: 2 }}><span className="eyebrow">Record</span><h2>{panelTitle}</h2></div>
            <button type="button" className="v-btn quiet icon sm" onClick={() => setPanelOpen(false)} aria-label="Close record"><X size={18} /></button>
          </header>
          <div className="ws-insp-body">
            <RecordPanelBody record={selected} summary={summary} onOpen={(id) => openDoc(selected, id)} />
            {can('VIEW_PDF') && selected.docs[0] && <button type="button" className="v-btn primary block" onClick={() => openDoc(selected, selected.docs[0].id)}>Open documents<ArrowRight size={16} aria-hidden /></button>}
          </div>
        </aside>
      )}
      {showPanel && selected && !wide && !openDocId && (
        <Drawer eyebrow="Record" title={panelTitle} onClose={() => setPanelOpen(false)}
          footer={can('VIEW_PDF') && selected.docs[0] ? <button type="button" className="v-btn primary" onClick={() => openDoc(selected, selected.docs[0].id)}>Open documents<ArrowRight size={16} aria-hidden /></button> : undefined}>
          <RecordPanelBody record={selected} summary={summary} onOpen={(id) => openDoc(selected, id)} />
        </Drawer>
      )}
      {selected && openDocId && can('VIEW_PDF') && <Viewer record={selected} results={results} docId={openDocId} onClose={() => setOpenDocId(null)} />}
      {failure && <div role="alert" className="v-callout">{failure}</div>}
    </div>
  )
}

function FiltersPanel({ sources, initial, onApply, onClose }: { sources: Source[]; initial: Applied; onApply: (a: Applied) => void; onClose: () => void }) {
  const [srcId, setSrcId] = useState(initial.sourceId || sources[0].id)
  const [draft, setDraft] = useState<Criteria>(initial.c)
  const [received, setReceived] = useState<Range>(initial.received)
  const [updated, setUpdated] = useState<Range>(initial.updated)
  const [touched, setTouched] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const src = sources.find((s) => s.id === srcId) ?? sources[0]
  const fields = src.fields.filter((f) => f.searchable)
  const filled = hasCriteria({ c: draft, received, updated })

  useEffect(() => { ref.current?.querySelector<HTMLElement>('select, input')?.focus() }, [])
  const apply = () => { setTouched(true); if (filled) onApply({ sourceId: src.id, c: draft, received, updated }) }

  return (
    <div ref={ref} id="ws-filters" className="ws-filters" role="dialog" aria-modal="false" aria-labelledby="ws-filters-title"
      onKeyDown={(e) => { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onClose() } }}>
      <header className="ws-filters-head">
        <h3 id="ws-filters-title">Filters</h3>
        <span className="faint">Each field shows how it matches</span>
        <button type="button" className="v-btn quiet icon sm ws-filters-close" onClick={onClose} aria-label="Close filters"><X size={18} /></button>
      </header>
      <form className="ws-filters-body" onSubmit={(e) => { e.preventDefault(); apply() }} noValidate>
        {sources.length > 1 && (
          <label className="v-field"><span>Source</span>
            <select className="v-select" value={src.id} onChange={(e) => { setSrcId(e.target.value); setDraft({}); setTouched(false) }}>
              {sources.filter((s) => s.status === 'ACTIVE').map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
        )}
        <div className="ws-filter-fields">
          {fields.map((f) => (
            <label key={f.key} className="v-field">
              <span>{f.label}<span className="v-hint">{MODE_HINT[f.mode]}</span></span>
              <input className="v-input" type={f.type === 'DATE' ? 'date' : 'text'} value={draft[f.key] ?? ''} autoComplete="off" spellCheck={false}
                inputMode={f.normalizationRaw === 'DIGITS_ONLY' ? 'numeric' : undefined}
                onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))} />
            </label>
          ))}
        </div>
        <DateRangeField legend="Received" hint="When the submission arrived" value={received} onChange={setReceived} />
        <DateRangeField legend="Last updated" hint="Latest activity on the submission" value={updated} onChange={setUpdated} />
        {touched && !filled && <Callout tone="warn" title="Enter at least one filter">Fill in a field, or choose a Received or Last updated date range.</Callout>}
        <footer className="ws-filters-foot">
          <span className="faint">Up to {SEARCH_PAGE_SIZE} records per page. You only see records your role allows.</span>
          <button type="button" className="v-btn" onClick={() => { setDraft({}); setReceived(NO_RANGE); setUpdated(NO_RANGE); setTouched(false) }}>Clear</button>
          <button type="submit" className="v-btn primary">Show results</button>
        </footer>
      </form>
    </div>
  )
}

function DateRangeField({ legend, hint, value, onChange }: { legend: string; hint: string; value: Range; onChange: (r: Range) => void }) {
  return (
    <fieldset className="ws-dates">
      <legend className="v-label">{legend}<span className="faint ws-dates-hint">{hint}</span></legend>
      <div className="ws-quick" role="group" aria-label={`${legend} quick ranges`}>
        {QUICK_RANGES.map((q) => {
          const r = quickRange(q)
          const on = r.from === value.from && r.to === value.to
          return <button key={q} type="button" className="v-btn sm" aria-pressed={on} onClick={() => onChange(on ? NO_RANGE : r)}>{q}</button>
        })}
      </div>
      <label className="v-field"><span>From</span><input className="v-input" type="date" value={value.from} max={value.to || undefined} onChange={(e) => onChange({ ...value, from: e.target.value })} /></label>
      <label className="v-field"><span>To</span><input className="v-input" type="date" value={value.to} min={value.from || undefined} onChange={(e) => onChange({ ...value, to: e.target.value })} /></label>
    </fieldset>
  )
}

function ResultsTable({ results, summaries, selectedRef, onOpen }: { results: RecordItem[]; summaries: Record<string, Summary>; selectedRef: string | null; onOpen: (ref: string) => void }) {
  const rows = useRef<(HTMLTableRowElement | null)[]>([])
  const rowSummaries = results.map((r) => summaries[r.ref])
  // Each identifier once, then the preview fields the server sends, in the order it sends them.
  const idCols = uniqueLabels(rowSummaries.map((s) => s?.identifiers ?? []))
  const previewCols = uniqueLabels(rowSummaries.map((s) => s?.preview ?? [])).filter((l) => !idCols.includes(l))
  const selectedIdx = results.findIndex((r) => r.ref === selectedRef)
  const focusIdx = selectedIdx >= 0 ? selectedIdx : 0
  const move = (to: number) => rows.current[Math.max(0, Math.min(results.length - 1, to))]?.focus()
  // Design screen 2: "1 of 2 received" in the row; without counts, a plain "Missing documents".
  const missing = (s?: Summary) => <Badge tone="warn">{s && s.expected > 0 ? `${s.received} of ${s.expected} received` : 'Missing documents'}</Badge>
  return (
    <table className="v-table ws-table" role="grid" aria-label="Records">
      <thead><tr>
        {idCols.map((l) => <th key={`i-${l}`}>{l}</th>)}
        {previewCols.map((l) => <th key={`p-${l}`}>{l}</th>)}
        <th className="num">Documents</th><th>Updated</th>
      </tr></thead>
      <tbody>
        {results.map((r, idx) => {
          const s = rowSummaries[idx]
          return (
            <tr key={r.ref} ref={(el) => { rows.current[idx] = el }} className="clickable" tabIndex={idx === focusIdx ? 0 : -1} aria-selected={r.ref === selectedRef}
              onClick={() => onOpen(r.ref)}
              onKeyDown={(e) => {
                if (e.key === 'ArrowDown') { e.preventDefault(); move(idx + 1) }
                else if (e.key === 'ArrowUp') { e.preventDefault(); move(idx - 1) }
                else if (e.key === 'Home') { e.preventDefault(); move(0) }
                else if (e.key === 'End') { e.preventDefault(); move(results.length - 1) }
                else if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(r.ref) }
              }}>
              {idCols.map((l, i) => (
                <td key={`i-${l}`} className={`mono${i === 0 ? ' lead' : ''}`} data-label={l}>
                  <span className="ws-cell">{valueOf(s?.identifiers, l) || '—'}{i === 0 && previewCols.length === 0 && s?.incomplete && missing(s)}</span>
                </td>
              ))}
              {previewCols.map((l, i) => (
                <td key={`p-${l}`} className={i === 0 ? 'ws-name' : undefined} data-label={l}>
                  <span className="ws-cell">{valueOf(s?.preview, l) || '—'}{i === 0 && s?.incomplete && missing(s)}</span>
                </td>
              ))}
              <td className="num" data-label="Documents">{s?.docs ?? r.docs.length}</td>
              <td className="faint" data-label="Updated">{fmtRel(r.updatedAt)}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

function RecordPanelBody({ record, summary, onOpen }: { record: RecordItem; summary?: Summary; onOpen: (id: string) => void }) {
  const { can, liveSession } = useStore()
  const [more, setMore] = useState(false)
  const [includeViews, setIncludeViews] = useState(false)
  const [liveEvents, setLiveEvents] = useState<LiveDocumentActivity[]>([])
  const [activityFailure, setActivityFailure] = useState('')
  useEffect(() => {
    if (!liveSession || !record.docs.length) { setLiveEvents([]); return }
    let active = true
    setActivityFailure('')
    Promise.all(record.docs.map((d) => documentActivity(d.id, includeViews, 100)))
      .then((groups) => { if (active) setLiveEvents(groups.flat().sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))) })
      .catch((e) => { if (active) { setLiveEvents([]); setActivityFailure(String(e)) } })
    return () => { active = false }
  }, [liveSession, record.ref, record.docs.map((d) => d.id).join(','), includeViews])
  const recent = record.docs.flatMap((d) => d.activity.map((a) => ({ ...a, doc: d.kind }))).sort((a, b) => b.at.localeCompare(a.at)).slice(0, more ? 8 : 3)
  const inc = record.incomplete
  const loadingDocs = record.docs.length === 0 && (summary?.docs ?? 0) > 0
  return (
    <>
      <div className={`ws-ids${record.identifiers.length > 2 ? " wrap" : ""}`}>{record.identifiers.map((i) => <div key={i.label}><span>{i.label}</span><strong title={i.value}>{i.value}</strong></div>)}</div>
      {(inc || summary?.incomplete) && (
        inc && inc.expected > 0 ? (
          <Callout tone="warn" title={`${inc.received} of ${inc.expected} documents received`}>
            {inc.missing && inc.missing.length > 0 ? `Still expected: ${inc.missing.join(', ')}. ` : ''}The ones below are ready to open.
          </Callout>
        ) : <Callout tone="warn" title="Missing documents">Some expected documents have not arrived yet.</Callout>
      )}
      <div className="stack" style={{ gap: 10 }}>
        <div className="row between"><h3>Documents</h3><span className="faint" style={{ fontSize: 13 }}>{record.docs.length || summary?.docs || 0}</span></div>
        {loadingDocs ? <p className="faint">Loading documents…</p> : (
          <ul className="ws-docs">
            {record.docs.map((d) => (
              <li key={d.id}>
                <button type="button" className="ws-doc" onClick={() => onOpen(d.id)} disabled={!can('VIEW_PDF')}>
                  <span className="sheet" aria-hidden />
                  <span className="grow" style={{ minWidth: 0 }}>
                    <strong className="truncate">{d.kind}</strong>
                    <span>{d.pages > 0 ? `${d.pages} page${d.pages === 1 ? '' : 's'} · ` : ''}received {fmtDate(d.ingestedAt)}{d.redactions.length > 0 && ' · redacted'}</span>
                  </span>
                  {d.tags.length > 0 && <Badge>{d.tags.length} tag{d.tags.length === 1 ? '' : 's'}</Badge>}
                  <FileText size={16} aria-hidden className="faint" />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="stack" style={{ gap: 4 }}>
        <h3>Details</h3>
        <dl className="v-kv">{record.metadata.map((m) => <div key={m.label}><dt>{m.label}</dt><dd>{m.value}</dd></div>)}</dl>
      </div>
      <div className="stack" style={{ gap: 10 }}>
        <h3>Recent activity</h3>
        {liveSession && <label className="v-check"><input type="checkbox" checked={includeViews} onChange={(e) => setIncludeViews(e.target.checked)} />Include document view activity</label>}
        {activityFailure && <Callout tone="warn" title="Activity unavailable">{activityFailure}</Callout>}
        <ol className="v-timeline">
          {liveSession ? liveEvents.slice(0, more ? 100 : 3).map((a) => <li key={a.auditEventId}><span className={`pt ${a.outcome === 'SUCCESS' ? 'ok' : 'bad'}`} aria-hidden /><div className="stack" style={{ gap: 0 }}><strong>{humanize(a.action.replace('DOCUMENT_', ''))}</strong><span>{a.actorId} · {fmtRel(a.occurredAt)}</span></div></li>) :
            recent.map((a) => <li key={a.id}><span className={`pt ${a.simulated ? 'sim' : a.outcome === 'SUCCESS' ? 'ok' : 'bad'}`} aria-hidden /><div className="stack" style={{ gap: 0 }}><strong>{humanize(a.action.replace('DOCUMENT_', ''))} · {a.doc}</strong><span>{a.actor} · {fmtRel(a.at)}{a.simulated && ' · simulated'}</span></div></li>)}
        </ol>
        {liveSession && !activityFailure && liveEvents.length === 0 && <p className="faint">No matching activity.</p>}
        {(!liveSession || liveEvents.length > 3) && <button type="button" className="v-btn quiet sm" style={{ alignSelf: 'flex-start' }} onClick={() => setMore((m) => !m)}>{more ? <ChevronUp size={14} aria-hidden /> : <ChevronDown size={14} aria-hidden />}{more ? 'Show less' : 'Show more'}</button>}
      </div>
      {!can('VIEW_PDF') && <Callout tone="warn" title="Viewing isn't assigned"><AlertTriangle size={0} aria-hidden />Your role can find this record but not open its documents.</Callout>}
    </>
  )
}
