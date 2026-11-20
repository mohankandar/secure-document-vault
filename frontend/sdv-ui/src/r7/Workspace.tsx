import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { AlertTriangle, ArrowRight, ChevronDown, ChevronUp, Search, SlidersHorizontal, X } from 'lucide-react'
import { useStore } from './store'
import type { RecordItem, Source } from './model'
import { schema, search, record, toSource, toSummary, toRecord, documentActivity, recentRecords as loadRecentRecords, RECENT_LIMIT, SEARCH_PAGE_SIZE, type LiveDocumentActivity } from './liveApi'
import { LoadErrorCard, failureText, loadFailed, type Failure } from './CardError'
import { Badge, Callout, Drawer, Empty, Skeleton, actorWords, auditEventLabel, fmtDate, fmtRel, fmtShortDate } from './ui'
import { Viewer } from './Viewer'
import { MODE_HINT, MODE_WORD, QUICK_RANGES, filterHint, boxLabel, quickRange, rangeLabel, type Range } from './searchRouting'
import { NOTHING_TO_SEARCH, defaultSource, rememberSourceChoice, searchableSources } from './sourceChoice'
import { extraField, orderedPreview, titleLabel } from './recordColumns'
import { anywhereProblem, hiddenMatch, identifierCriteria, type MatchedField } from './quickSearchRules'
import { Mark, useQuickSearch, type QuickPick } from './QuickSearch'

type Criteria = Record<string, string>
/**
 * received: when the submission arrived (server dateFrom/dateTo); updated: last activity (lastUpdatedFrom/To).
 * anywhere (task 0022): text searched in every searchable field.
 */
type Applied = { sourceId: string; c: Criteria; received: Range; updated: Range; /** only records with missing documents */ incompleteOnly?: boolean; anywhere?: string }
const NO_RANGE: Range = { from: '', to: '' }
const emptyApplied = (sourceId: string): Applied => ({ sourceId, c: {}, received: NO_RANGE, updated: NO_RANGE })
type FieldValue = { label: string; value: string }
/** What the search result said about a record; kept apart so loading the full record does not change the row. */
type Summary = { /** the server's record title, null when it has none */ title: string | null; identifiers: FieldValue[]; preview: FieldValue[]; docs: number; incomplete: boolean; expected: number; received: number; /** an "anywhere" search only */ matched: MatchedField[] }

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
const hasCriteria = (a: Pick<Applied, 'c' | 'received' | 'updated' | 'incompleteOnly' | 'anywhere'>) => Object.values(a.c).some((v) => v.trim()) || !!a.anywhere?.trim() || !!a.incompleteOnly || [a.received, a.updated].some((r) => !!r.from || !!r.to)
const valueOf = (list: FieldValue[] | undefined, label: string) => list?.find((f) => f.label === label)?.value ?? ''

export function Workspace() {
  const { records, setRecords, selectedRef, setSelectedRef, openDocId, setOpenDocId, can, setResultsShown, searchRequest, requestSearch, recentRecords, setRecentRecords, route } = useStore()
  const [sources, setSources] = useState<Source[]>([])
  const [status, setStatus] = useState<'loading' | 'live' | 'error'>('loading')
  // A failed load shows the shared card (plain words + reference); Try again re-runs the loads.
  const [failure, setFailure] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(false)
  const [summaries, setSummaries] = useState<Record<string, Summary>>({})
  const [sourceId, setSourceId] = useState('')
  const [applied, setApplied] = useState<Applied | null>(null)
  const [box, setBox] = useState('')
  const [boxMessage, setBoxMessage] = useState('')
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [panelOpen, setPanelOpen] = useState(false)
  const filtersButton = useRef<HTMLButtonElement>(null)
  const wide = useMediaQuery('(min-width: 1024px)')
  // Task 0022 follow-up: Records loads the person's Recently viewed list itself, once each time they come to Records,
  // with the same call and audit entry as Home. This component stays mounted, hidden, on other pages, so the load
  // follows the route, not the mount. The ref keeps it to one call per visit even when React runs the effect twice
  // (StrictMode in development). The result goes to the store, so it is kept even if the person has moved on.
  // If the load fails, the search list leaves out "Records you opened recently".
  const onRecords = route.area === 'workspace'
  const loadedThisVisit = useRef(false)
  useEffect(() => {
    if (!onRecords) { loadedThisVisit.current = false; return }
    if (loadedThisVisit.current) return
    loadedThisVisit.current = true
    loadRecentRecords(RECENT_LIMIT).then((r) => setRecentRecords(r.records)).catch(() => { /* section left out */ })
  }, [onRecords, setRecentRecords])

  useEffect(() => { let active = true; schema().then((s) => { if (active) { setSources(s.sources.map(toSource)); setStatus('live') } })
    .catch((e) => { if (active) { setFailure(loadFailed('Search fields did not load', e)); setStatus('error') } }); return () => { active = false } }, [reload])
  useEffect(() => { if (!selectedRef) return; let active = true
    record(selectedRef).then((r) => { if (active) setRecords((prev) => prev.map((x) => (x.ref === selectedRef ? toRecord(r) : x))) })
      .catch((e) => { if (active) { setFailure(loadFailed('The record did not load', e)); setStatus('error') } })
    return () => { active = false }
  }, [selectedRef, setRecords])
  // A record chosen elsewhere (e.g. "Go to") opens the record panel.
  useEffect(() => { if (selectedRef) setPanelOpen(true) }, [selectedRef])
  // One fixed rule for the default (sourceChoice.ts, task 0023), shared with Home.
  useEffect(() => { if (!sources.length || sourceId) return; const d = defaultSource(sources); if (d) setSourceId(d.id) }, [sources, sourceId])
  const candidates = useMemo(() => searchableSources(sources), [sources])
  const sourcesKnown = status !== 'loading' || sources.length > 0
  const nothingToSearch = sourcesKnown && status !== 'error' && candidates.length === 0

  const activeSourceId = applied?.sourceId ?? sourceId
  const source = sources.find((s) => s.id === activeSourceId) ?? defaultSource(sources)
  const fields = useMemo(() => source?.fields.filter((f) => f.searchable) ?? [], [source])
  const label = boxLabel(fields)
  // "Contract number or borrower", from the system's own labels (as on Home).
  const boxPlaceholder = label ? label.split(' or ').map((p, i) => (i ? p.charAt(0).toLowerCase() + p.slice(1) : p)).join(' or ') : 'Use Filters to search this source'

  useEffect(() => {
    if (!applied) { setRecords([]); setSummaries({}); setHasMore(false); setStatus(sources.length ? 'live' : 'loading'); return }
    let active = true
    setStatus('loading')
    search(applied.sourceId, applied.c, applied.received, applied.updated, page, { incompleteOnly: applied.incompleteOnly, anywhere: applied.anywhere }).then((r) => {
      if (!active) return
      setRecords(r.records.map((x) => toSummary(x, applied.sourceId)))
      setSummaries(Object.fromEntries(r.records.map((x) => [x.recordRef, {
        title: x.title ?? null,
        identifiers: x.identifiers.map((f) => ({ label: f.displayLabel, value: f.value })),
        preview: x.metadataPreview.map((f) => ({ label: f.displayLabel, value: f.value })),
        docs: x.availableDocumentCount, incomplete: x.incomplete,
        expected: x.incompleteExpectedCount ?? 0, received: x.incompleteReceivedCount ?? 0,
        matched: x.matchedFields ?? [],
      }])))
      setHasMore(r.hasMore)
      setStatus('live')
    }).catch((e) => { if (active) { setFailure(loadFailed('The search did not load', e)); setRecords([]); setSummaries({}); setStatus('error') } })
    return () => { active = false }
  }, [applied, page, setRecords, sources.length, reload])

  const results = records
  useEffect(() => { setResultsShown(!!applied && status === 'live' && results.length > 0); return () => setResultsShown(false) }, [applied, status, results.length, setResultsShown])
  const selected = records.find((r) => r.ref === selectedRef) ?? null
  const showPanel = !!selected && panelOpen

  const runSearch = (next: Applied | null) => {
    setPage(1)
    setApplied(next && hasCriteria(next) ? next : null)
    setSelectedRef(null)
    setPanelOpen(false)
  }
  // Task 0022: Enter searches every searchable field ("anywhere"), alongside the filters already applied. The list
  // under the box also offers one field (today's field search) or a recently opened record.
  const searchEverywhere = () => {
    const text = box.trim()
    if (!text) return
    // Enter before the sending systems have loaded: hand the text to the request below, which runs it once they are known.
    if (!sources.length && status !== 'error') { requestSearch({ anywhere: text }); return }
    if (nothingToSearch) { setBoxMessage(NOTHING_TO_SEARCH); return }
    const problem = anywhereProblem(text)
    if (problem) { setBoxMessage(problem); return }
    setBoxMessage('')
    runSearch({ ...(applied ?? emptyApplied(activeSourceId)), anywhere: text })
    setBox('')
  }
  const onPick = (pick: QuickPick) => {
    if (pick.kind === 'everywhere') return searchEverywhere()
    if (pick.kind === 'recent') {
      // As Home does: Records finds exactly that record and opens its panel.
      const { sourceId: recentSource, record: r } = pick.recent
      setBox(''); setBoxMessage('')
      requestSearch({ sourceId: recentSource, criteria: identifierCriteria(r, sources.find((s) => s.id === recentSource)), selectRef: r.recordRef })
      return
    }
    const base = applied ?? emptyApplied(activeSourceId)
    setBoxMessage('')
    runSearch({ ...base, c: { ...base.c, [pick.field.key]: box.trim() } })
    setBox('')
  }
  const quick = useQuickSearch({ text: nothingToSearch ? '' : box, fields, recent: recentRecords, onPick })
  const submitBox = (e?: FormEvent) => { e?.preventDefault(); searchEverywhere() }
  const removeChip = (key: string) => {
    if (!applied) return
    if (key === 'received' || key === 'updated') return runSearch({ ...applied, [key]: NO_RANGE })
    if (key === 'incomplete') return runSearch({ ...applied, incompleteOnly: false })
    if (key === 'anywhere') return runSearch({ ...applied, anywhere: undefined })
    const c = { ...applied.c }
    delete c[key]
    runSearch({ ...applied, c })
  }
  const clearAll = () => { setBox(''); setBoxMessage(''); runSearch(null) }

  const appliedSourceName = (applied ? sources.find((s) => s.id === applied.sourceId) : source)?.name
  // Canvas board 2: each chip is "<b>What</b> value"; a quick range reads as its name ("Updated last 30 days").
  // With more than one sending system, a Source chip says which one is searched (it can't be removed: change it in Filters).
  type Chip = { key: string; label: string; value: string; text: string; removable: boolean }
  const chip = (key: string, label: string, value: string, removable = true): Chip => ({ key, label, value, text: value ? `${label} ${value}` : label, removable })
  const rangeWords = (r: Range, name: string) => {
    const quick = QUICK_RANGES.find((q) => { const x = quickRange(q); return x.from === r.from && x.to === r.to })
    return quick ? quick.charAt(0).toLowerCase() + quick.slice(1) : (rangeLabel(name, r) ?? '').slice(name.length + 1)
  }
  const chips: Chip[] = applied ? [
    // Quick search board 2: "Anywhere patel".
    ...(applied.anywhere ? [chip('anywhere', 'Anywhere', applied.anywhere)] : []),
    ...Object.entries(applied.c).filter(([, v]) => v.trim()).map(([key, v]) => {
      const f = source?.fields.find((x) => x.key === key)
      return f ? chip(key, f.label, `${MODE_WORD[f.mode]} ${v.trim()}`) : chip(key, key, v.trim())
    }),
    ...(applied.incompleteOnly ? [chip('incomplete', 'Missing documents', '')] : []),
    ...(['received', 'updated'] as const).flatMap((key) => {
      const name = key === 'received' ? 'Received' : 'Updated'
      return rangeLabel(name, applied[key]) ? [chip(key, name, rangeWords(applied[key], name))] : []
    }),
    ...(candidates.length > 1 && appliedSourceName ? [chip('source', 'Source', appliedSourceName, false)] : []),
  ] : []

  const openRecord = (ref: string) => { setSelectedRef(ref); setPanelOpen(true) }

  // Searches started elsewhere (Home): run them here once the sources are known; optionally select a record.
  const handledRequest = useRef(0)
  const [pendingSelect, setPendingSelect] = useState<string | null>(null)
  useEffect(() => {
    if (!searchRequest || handledRequest.current === searchRequest.id || !sources.length) return
    handledRequest.current = searchRequest.id
    // A named system (a record opened from Home) is searched even if it can't be the default; otherwise the rule.
    const src = sources.find((s) => s.id === searchRequest.sourceId) ?? sources.find((s) => s.id === sourceId) ?? defaultSource(sources)
    if (!src) { if (searchRequest.anywhere !== undefined) { setBox(searchRequest.anywhere); setBoxMessage(NOTHING_TO_SEARCH) } return }
    const base: Applied = { ...emptyApplied(src.id), incompleteOnly: !!searchRequest.incompleteOnly, received: searchRequest.receivedLast30Days ? quickRange('Last 30 days') : NO_RANGE }
    setFiltersOpen(false)
    if (searchRequest.anywhere !== undefined) {
      const problem = anywhereProblem(searchRequest.anywhere)
      if (problem) { setBox(searchRequest.anywhere); setBoxMessage(problem) }
      else { setBox(''); setBoxMessage(''); runSearch({ ...base, anywhere: searchRequest.anywhere.trim() }) }
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
    setFailure(null)
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
  // The server names the record (Record title setting); the same title shows in the viewer.
  const panelTitle = selected ? selected.title : ''

  return (
    <div className={`ws${showPanel && wide ? ' has-insp' : ''}`}>
      <div className={`ws-main${filtersOpen ? ' filtering' : ''}`}>
        {/* Canvas board 2: one bordered bar holding the chips, the box, Filters and Clear. */}
        <section className="ws-bar" aria-label="Record search">
          <form className="ws-searchbar" role="search" onSubmit={submitBox}>
            <Search size={18} aria-hidden />
            {chips.map((c) => (
              <span key={c.key} className="ws-chip"><b>{c.label}</b>{c.value && ` ${c.value}`}
                {c.removable && <button type="button" onClick={() => removeChip(c.key)} aria-label={`Remove filter: ${c.text}`}><X size={14} /></button>}
              </span>
            ))}
            <input type="search" className="ws-searchinput" aria-label="Search records" value={box} autoComplete="off" spellCheck={false} {...quick.inputProps}
              placeholder={nothingToSearch ? 'Nothing to search yet' : chips.length ? '' : boxPlaceholder} aria-describedby={boxMessage ? 'ws-box-message' : undefined}
              onChange={(e) => { setBox(e.target.value); setBoxMessage(''); quick.typing(e.target.value) }}
              onKeyDown={(e) => { if (quick.onKeyDown(e)) return; if (e.key === 'Escape') { e.preventDefault(); setBox(''); setBoxMessage('') } }} />
          </form>
          {quick.list}
          <button ref={filtersButton} type="button" className="v-btn quiet sm" aria-expanded={filtersOpen} aria-controls="ws-filters" disabled={nothingToSearch} onClick={() => setFiltersOpen((o) => !o)}>
            <SlidersHorizontal size={16} aria-hidden /><span>Filters{chips.length > 0 && <span aria-label={`${chips.length} active`}> · {chips.length}</span>}</span>
          </button>
          <button type="button" className="v-btn sm" onClick={clearAll} disabled={!applied && !box}>Clear</button>
          {filtersOpen && candidates.length > 0 && (
            <FiltersPanel sources={candidates} initial={applied ?? emptyApplied(activeSourceId)}
              // An "anywhere" search stays when Filters are applied to the same sending system (task 0022).
              onApply={(next) => { setFiltersOpen(false); setBox(''); setBoxMessage(''); rememberSourceChoice(next.sourceId); setSourceId(next.sourceId); runSearch({ ...next, anywhere: applied?.sourceId === next.sourceId ? applied.anywhere : undefined }); filtersButton.current?.focus() }}
              onClose={() => { setFiltersOpen(false); filtersButton.current?.focus() }} />
          )}
        </section>
        {boxMessage && <p id="ws-box-message" className="ws-box-message" role="status">{boxMessage}</p>}

        <section className="ws-results" aria-label="Search results">
          {status === 'loading' ? <Skeleton rows={7} /> : status === 'error' ? (failure ? <LoadErrorCard title={failure.title ?? "The search didn't load"} failure={failure} onRetry={() => { setFailure(null); setReload((n) => n + 1) }} /> : null) : !applied && nothingToSearch ? (
            <Empty title="There’s nothing to search yet." icon={<Search size={20} />}>Records appear here once a sending system delivers documents.</Empty>
          ) : !applied ? (
            <Empty title={label ? `Search by ${label}` : 'Search with Filters'} icon={<Search size={20} />}>
              Type in the search bar and press Enter, or open Filters to search by any field.
            </Empty>
          ) : results.length === 0 ? (
            <Empty title="No matching records" action={<button type="button" className="v-btn sm" onClick={() => setFiltersOpen(true)}>Open Filters</button>}>
              {applied.anywhere
                ? `Nothing matched “${applied.anywhere}” in any searchable field. Check for typos, remove a filter, or try a shorter part of a name.`
                : 'Check the number for typos, remove a filter, or search a shorter part of a name. Exact fields must match in full.'}
            </Empty>
          ) : (
            <>
              {/* Canvas board 2: the count line above the card, the table in the card, "Showing …" below it. */}
              <div className="ws-results-head">
                <h2>{results.length}{hasMore ? '+' : ''} record{results.length === 1 && !hasMore ? '' : 's'} <span className="faint">· {applied.anywhere ? `“${applied.anywhere}” in any searchable field · ` : ''}{appliedSource?.name ?? 'SDV'}</span></h2>
                <span className="faint">{applied.anywhere ? 'Best match first' : 'Sorted by last updated'}</span>
              </div>
              <div className="ws-card">
                <ResultsTable results={results} summaries={summaries} selectedRef={selectedRef} onOpen={openRecord} source={appliedSource} needle={applied.anywhere} />
              </div>
              <div className="ws-pager">
                <span>Showing {page === 1 && !hasMore ? `${results.length} of ${results.length}` : `${(page - 1) * SEARCH_PAGE_SIZE + 1}–${(page - 1) * SEARCH_PAGE_SIZE + results.length}`}{hasMore ? ' · more on the next page' : ''} · up to {SEARCH_PAGE_SIZE} per page{applied.anywhere ? ' · Narrow it with Filters, or pick one field from the search list.' : ''}</span>
                {(page > 1 || hasMore) && (
                  <span className="row" style={{ gap: 6 }}>
                    <button className="v-btn sm" type="button" disabled={page === 1} onClick={() => { setPage((p) => p - 1); setSelectedRef(null); setPanelOpen(false) }}>Previous</button>
                    <button className="v-btn sm" type="button" disabled={!hasMore} onClick={() => { setPage((p) => p + 1); setSelectedRef(null); setPanelOpen(false) }}>Next</button>
                  </span>
                )}
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
            <RecordPanelBody record={selected} summary={summary} source={appliedSource} needle={applied?.anywhere} onOpen={(id) => openDoc(selected, id)}
              action={can('VIEW_PDF') && selected.docs[0] ? <button type="button" className="v-btn primary block ws-open" onClick={() => openDoc(selected, selected.docs[0].id)}>Open documents<ArrowRight size={16} aria-hidden /></button> : undefined} />
          </div>
        </aside>
      )}
      {showPanel && selected && !wide && !openDocId && (
        <Drawer eyebrow="Record" title={panelTitle} onClose={() => setPanelOpen(false)}
          footer={can('VIEW_PDF') && selected.docs[0] ? <button type="button" className="v-btn primary" onClick={() => openDoc(selected, selected.docs[0].id)}>Open documents<ArrowRight size={16} aria-hidden /></button> : undefined}>
          <RecordPanelBody record={selected} summary={summary} source={appliedSource} needle={applied?.anywhere} onOpen={(id) => openDoc(selected, id)} />
        </Drawer>
      )}
      {selected && openDocId && can('VIEW_PDF') && <Viewer record={selected} results={results} docId={openDocId} onClose={() => setOpenDocId(null)} recordTitle={panelTitle} />}
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
              {sources.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
        )}
        <div className="ws-filter-fields">
          {fields.map((f) => (
            <label key={f.key} className="v-field">
              <span>{f.label}<span className="v-hint">{MODE_HINT[f.mode]}</span></span>
              <input className="v-input" type={f.type === 'DATE' ? 'date' : 'text'} value={draft[f.key] ?? ''} autoComplete="off" spellCheck={false}
                inputMode={f.normalizationRaw === 'DIGITS_ONLY' ? 'numeric' : undefined} placeholder={filterHint(f)}
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

/**
 * Canvas board 2: the number once, the record's title, one more field (recordColumns.ts), Documents, Updated.
 * The other identifiers and fields are in the record panel.
 */
function ResultsTable({ results, summaries, selectedRef, onOpen, source, needle }: { results: RecordItem[]; summaries: Record<string, Summary>; selectedRef: string | null; onOpen: (ref: string) => void; source?: Source; /** "anywhere" text: marked in the row (task 0022) */ needle?: string }) {
  const rows = useRef<(HTMLTableRowElement | null)[]>([])
  const rowSummaries = results.map((r) => summaries[r.ref])
  const numberLabel = rowSummaries.find((s) => s?.identifiers[0])?.identifiers[0].label ?? 'Number'
  const titled = rowSummaries.map((s) => ({ title: s?.title ?? null, preview: s?.preview ?? [] }))
  const titleHead = titleLabel(titled)
  const extraLabel = titled.map((t) => extraField(t.title, t.preview, source)?.label).find(Boolean)
  const selectedIdx = results.findIndex((r) => r.ref === selectedRef)
  const focusIdx = selectedIdx >= 0 ? selectedIdx : 0
  const move = (to: number) => rows.current[Math.max(0, Math.min(results.length - 1, to))]?.focus()
  // Design screen 2: "2 of 4 received" in the row; without counts, a plain "Missing documents".
  const missing = (s?: Summary) => <Badge tone="warn">{s && s.expected > 0 ? `${s.received} of ${s.expected} received` : 'Missing documents'}</Badge>
  return (
    <table className="v-table ws-table" role="grid" aria-label="Records">
      <thead><tr>
        <th className="ws-col-num">{numberLabel}</th>
        <th>{titleHead}</th>
        {extraLabel && <th className="ws-col-extra">{extraLabel}</th>}
        <th className="ws-col-docs">Documents</th><th className="ws-col-when">Updated</th>
      </tr></thead>
      <tbody>
        {results.map((r, idx) => {
          const s = rowSummaries[idx]
          const number = s?.identifiers[0]?.value || r.ref
          const hidden = needle ? hiddenMatch(s?.matched, { numberLabel, extraLabel, title: s?.title }) : null
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
              <td className="mono lead" data-label={numberLabel}><Mark text={number} needle={needle} /></td>
              <td className="ws-name" data-label={titleHead}>
                <span className="ws-cell">{s?.title ? <Mark text={s.title} needle={needle} /> : "—"}{s?.incomplete && missing(s)}</span>
                {/* Quick search board 2: a match in a field that is not a column, under the title. */}
                {hidden && <span className="ws-matched">Matched <b>{hidden.displayLabel}</b>: <Mark text={hidden.value} needle={needle} /></span>}
              </td>
              {extraLabel && <td data-label={extraLabel}>{valueOf(s?.preview, extraLabel) ? <Mark text={valueOf(s?.preview, extraLabel)} needle={needle} /> : "—"}</td>}
              <td data-label="Documents">{s?.docs ?? r.docs.length}</td>
              <td className="faint" data-label="Updated">{fmtRel(r.updatedAt)}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

function RecordPanelBody({ record, summary, onOpen, source, action, needle }: { record: RecordItem; summary?: Summary; onOpen: (id: string) => void; source?: Source; /** Shown after Details (the wide panel's Open documents) */ action?: ReactNode; /** "anywhere" text: marked in Details (task 0022) */ needle?: string }) {
  const { can } = useStore()
  // Details: the record's fields in the sending system's own order, without its identifiers; dates as "30 Sep 2026".
  const idLabels = new Set(record.identifiers.map((i) => i.label))
  const details = orderedPreview(record.metadata.filter((m) => !idLabels.has(m.label)), source)
    .map((m) => (source?.fields.find((f) => f.label === m.label)?.type === 'DATE' ? { ...m, value: fmtDate(m.value) } : m))
  const [more, setMore] = useState(false)
  const [includeViews, setIncludeViews] = useState(false)
  const [liveEvents, setLiveEvents] = useState<(LiveDocumentActivity & { filename: string })[]>([])
  const [activityFailure, setActivityFailure] = useState('')
  useEffect(() => {
    if (!record.docs.length) { setLiveEvents([]); return }
    let active = true
    setActivityFailure('')
    Promise.all(record.docs.map((d) => documentActivity(d.id, includeViews, 100).then((rows) => rows.map((e) => ({ ...e, filename: d.filename })))))
      .then((groups) => { if (active) setLiveEvents(groups.flat().sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))) })
      .catch((e) => { if (active) { setLiveEvents([]); setActivityFailure(failureText('Record panel: activity did not load', e)) } })
    return () => { active = false }
  }, [record.ref, record.docs.map((d) => d.id).join(','), includeViews])
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
      <div className="stack" style={{ gap: 8 }}>
        <h3>Documents · {record.docs.length || summary?.docs || 0}</h3>
        {loadingDocs ? <p className="faint">Loading documents…</p> : (
          <ul className="ws-docs">
            {record.docs.map((d) => (
              <li key={d.id}>
                <button type="button" className="ws-doc" onClick={() => onOpen(d.id)} disabled={!can('VIEW_PDF')}>
                  <span className="sheet" aria-hidden />
                  <span className="grow" style={{ minWidth: 0 }}>
                    <strong className="truncate">{d.kind}</strong>
                    <span>{d.pages > 0 ? `${d.pages} page${d.pages === 1 ? '' : 's'} · ` : ''}received {fmtShortDate(d.ingestedAt)}{d.redacted && ' · redacted'}</span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      {details.length > 0 && (
        <div className="stack" style={{ gap: 10 }}>
          <h3>Details</h3>
          {/* Canvas board 2: label beside value; the number is in the box above, so it isn't repeated. */}
          <dl className="ws-kv">{details.map((m) => <div key={m.label}><dt>{m.label}</dt><dd><Mark text={m.value} needle={needle} /></dd></div>)}</dl>
        </div>
      )}
      {action}
      <div className="stack" style={{ gap: 10 }}>
        <h3>Recent activity</h3>
        <label className="v-check"><input type="checkbox" checked={includeViews} onChange={(e) => setIncludeViews(e.target.checked)} />Include document view activity</label>
        {activityFailure && <Callout tone="warn" title="Activity unavailable">{activityFailure}</Callout>}
        <ol className="v-timeline">
          {liveEvents.slice(0, more ? 100 : 3).map((a) => <li key={a.auditEventId}><span className={`pt ${a.outcome === 'SUCCESS' ? 'ok' : 'bad'}`} aria-hidden /><div className="stack" style={{ gap: 0 }}><strong>{auditEventLabel(a.action, a.safeDetailsJson, a.filename, true)}</strong><span>{actorWords(a.actorType)} · {fmtRel(a.occurredAt)}</span></div></li>)}
        </ol>
        {!activityFailure && liveEvents.length === 0 && <p className="faint">No matching activity.</p>}
        {liveEvents.length > 3 && <button type="button" className="v-btn quiet sm" style={{ alignSelf: 'flex-start' }} onClick={() => setMore((m) => !m)}>{more ? <ChevronUp size={14} aria-hidden /> : <ChevronDown size={14} aria-hidden />}{more ? 'Show less' : 'Show more'}</button>}
      </div>
      {!can('VIEW_PDF') && <Callout tone="warn" title="Viewing isn't assigned"><AlertTriangle size={0} aria-hidden />Your role can find this record but not open its documents.</Callout>}
    </>
  )
}
