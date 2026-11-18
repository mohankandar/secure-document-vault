import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { AlertTriangle, ArrowRight, ChevronDown, ChevronUp, FileText, Search, SlidersHorizontal, X } from 'lucide-react'
import { useStore } from './store'
import type { RecordItem, Source } from './data'
import { schema, search, record, toSource, toSummary, toRecord, documentActivity, SEARCH_PAGE_SIZE, type LiveDocumentActivity } from './liveApi'
import { Badge, Callout, Drawer, Empty, ErrorState, Skeleton, fmtDate, fmtRel, humanize } from './ui'
import { Viewer } from './Viewer'
import { MODE_HINT, MODE_WORD, boxLabel, routeBoxInput } from './searchRouting'

type Criteria = Record<string, string>
type Applied = { sourceId: string; c: Criteria; from: string; to: string }
type FieldValue = { label: string; value: string }
/** What the search result said about a record; kept apart so loading the full record does not change the row. */
type Summary = { identifiers: FieldValue[]; preview: FieldValue[]; docs: number; incomplete: boolean }

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

const hasCriteria = (a: Pick<Applied, 'c' | 'from' | 'to'>) => Object.values(a.c).some((v) => v.trim()) || !!a.from || !!a.to
const uniqueLabels = (rows: FieldValue[][]) => [...new Set(rows.flat().map((f) => f.label))]
const valueOf = (list: FieldValue[] | undefined, label: string) => list?.find((f) => f.label === label)?.value ?? ''

export function Workspace() {
  const { records, setRecords, selectedRef, setSelectedRef, openDocId, setOpenDocId, can, setResultsShown } = useStore()
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
    search(applied.sourceId, applied.c, applied.from, applied.to, page).then((r) => {
      if (!active) return
      setRecords(r.records.map((x) => toSummary(x, applied.sourceId)))
      setSummaries(Object.fromEntries(r.records.map((x) => [x.recordRef, {
        identifiers: x.identifiers.map((f) => ({ label: f.displayLabel, value: f.value })),
        preview: x.metadataPreview.map((f) => ({ label: f.displayLabel, value: f.value })),
        docs: x.availableDocumentCount, incomplete: x.incomplete,
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
  }
  const submitBox = (e?: FormEvent) => {
    e?.preventDefault()
    const route = routeBoxInput(box, fields)
    if (!route) return
    if (!route.ok) { setBoxMessage(route.message); return }
    setBoxMessage('')
    const base = applied ?? { sourceId: activeSourceId, c: {}, from: '', to: '' }
    runSearch({ ...base, c: { ...base.c, [route.field.key]: route.value } })
    setBox('')
  }
  const removeChip = (key: string) => {
    if (!applied) return
    if (key === 'from') return runSearch({ ...applied, from: '' })
    if (key === 'to') return runSearch({ ...applied, to: '' })
    const c = { ...applied.c }
    delete c[key]
    runSearch({ ...applied, c })
  }
  const clearAll = () => { setBox(''); setBoxMessage(''); runSearch(null) }

  const chips = applied ? [
    ...Object.entries(applied.c).filter(([, v]) => v.trim()).map(([key, v]) => {
      const f = source?.fields.find((x) => x.key === key)
      return { key, text: f ? `${f.label} ${MODE_WORD[f.mode]} ${v.trim()}` : `${key} ${v.trim()}` }
    }),
    ...(applied.from ? [{ key: 'from', text: `Updated from ${fmtDate(applied.from)}` }] : []),
    ...(applied.to ? [{ key: 'to', text: `Updated to ${fmtDate(applied.to)}` }] : []),
  ] : []

  const openRecord = (ref: string) => { setSelectedRef(ref); setPanelOpen(true) }
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
            <FiltersPanel sources={sources} initial={applied ?? { sourceId: activeSourceId, c: {}, from: '', to: '' }}
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
            <Empty title="No matching records" action={<button type="button" className="v-btn sm" onClick={() => setFiltersOpen(true)}>Open Filters</button>}>
              Check the number for typos, remove a filter, or search a shorter part of a name. Exact fields must match in full.
            </Empty>
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
  const [from, setFrom] = useState(initial.from)
  const [to, setTo] = useState(initial.to)
  const [touched, setTouched] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const src = sources.find((s) => s.id === srcId) ?? sources[0]
  const fields = src.fields.filter((f) => f.searchable)
  const filled = hasCriteria({ c: draft, from, to })

  useEffect(() => { ref.current?.querySelector<HTMLElement>('select, input')?.focus() }, [])
  const apply = () => { setTouched(true); if (filled) onApply({ sourceId: src.id, c: draft, from, to }) }

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
        <fieldset className="ws-dates">
          <legend className="v-label">Last updated</legend>
          <label className="v-field"><span>From</span><input className="v-input" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
          <label className="v-field"><span>To</span><input className="v-input" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
        </fieldset>
        {touched && !filled && <Callout tone="warn" title="Enter at least one filter">Open-ended browsing isn&apos;t supported. Search by an identifier or field.</Callout>}
        <footer className="ws-filters-foot">
          <span className="faint">Up to {SEARCH_PAGE_SIZE} records per page. You only see records your role allows.</span>
          <button type="button" className="v-btn" onClick={() => { setDraft({}); setFrom(''); setTo(''); setTouched(false) }}>Clear</button>
          <button type="submit" className="v-btn primary">Show results</button>
        </footer>
      </form>
    </div>
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
  const missing = <Badge tone="warn">Missing documents</Badge>
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
                  <span className="ws-cell">{valueOf(s?.identifiers, l) || '—'}{i === 0 && previewCols.length === 0 && s?.incomplete && missing}</span>
                </td>
              ))}
              {previewCols.map((l, i) => (
                <td key={`p-${l}`} className={i === 0 ? 'ws-name' : undefined} data-label={l}>
                  <span className="ws-cell">{valueOf(s?.preview, l) || '—'}{i === 0 && s?.incomplete && missing}</span>
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
