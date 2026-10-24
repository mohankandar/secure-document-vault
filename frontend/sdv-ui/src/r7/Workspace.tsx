import { useEffect, useState, type FormEvent } from 'react'
import { AlertTriangle, ArrowRight, ChevronDown, ChevronUp, FileText, Search, SlidersHorizontal, X } from 'lucide-react'
import { useStore } from './store'
import { BUSINESS_LIMITS, type RecordItem, type Source } from './data'
import { schema, search, record, toSource, toSummary, toRecord, documentActivity, type LiveDocumentActivity } from './liveApi'
import { Badge, Callout, Drawer, Empty, ErrorState, Skeleton, fmtDate, fmtRel, humanize } from './ui'
import { Viewer } from './Viewer'

const MODE_HINT = { EXACT: 'Exact', PREFIX: 'Starts with', PARTIAL: 'Contains' } as const
type Criteria = Record<string, string>

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

export function Workspace() {
  const { records, setRecords, selectedRef, setSelectedRef, openDocId, setOpenDocId, can, setResultsShown } = useStore()
  const [sources, setSources] = useState<Source[]>([])
  const [status, setStatus] = useState<'loading'|'live'|'error'>('loading')
  const [failure, setFailure] = useState('')
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(false)
  const [appliedCount, setAppliedCount] = useState(0)
  const [documentCounts, setDocumentCounts] = useState<Record<string, number>>({})
  useEffect(() => { let active=true; schema().then(s => { if(active) {setSources(s.sources.map(toSource));setStatus('live')} })
    .catch(e=>{if(active){setFailure(String(e));setStatus('error')}});return ()=>{active=false} }, [])
  useEffect(() => { if (!selectedRef) return; let active=true
    record(selectedRef).then(r => {if(active)setRecords(prev=>prev.map(x=>x.ref===selectedRef?toRecord(r):x))})
    .catch(e=>{if(active){setFailure(String(e));setStatus('error')}})
    return ()=>{active=false}
  }, [selectedRef,setRecords])
  const wide = useMediaQuery('(min-width: 1024px)')
  const [sourceId, setSourceId] = useState('')
  const source = sources.find((s) => s.id === sourceId) ?? sources[0]
  const fields = source?.fields.filter((f) => f.searchable) ?? []
  const [draft, setDraft] = useState<Criteria>({})
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [applied, setApplied] = useState<{ sourceId: string; c: Criteria; from: string; to: string } | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [touched, setTouched] = useState(false)

  useEffect(() => { if(!sources.length || sourceId) return; setSourceId(sources[0].id) }, [sources,sourceId])
  const results = records
  useEffect(() => {
    if (!applied) {setRecords([]);setDocumentCounts({});setAppliedCount(0);setHasMore(false);setStatus(sources.length?'live':'loading');return}
    let active=true
    setStatus('loading')
    search(applied.sourceId,applied.c,applied.from,applied.to,page).then(r=>{
      if(!active)return
      setRecords(r.records.map(x=>toSummary(x,applied.sourceId)))
      setDocumentCounts(Object.fromEntries(r.records.map(x => [x.recordRef, x.availableDocumentCount])))
      setHasMore(r.hasMore)
      setAppliedCount(r.records.length)
      setStatus('live')
    }).catch(e=>{if(active){setFailure(String(e));setRecords([]);setDocumentCounts({});setStatus('error')}})
    return ()=>{active=false}
  },[applied,page,setRecords,sources.length])
  useEffect(()=>{setResultsShown(!!applied && status==='live' && results.length>0);return()=>setResultsShown(false)},[applied,status,results.length,setResultsShown])
  const selected = records.find((r) => r.ref === selectedRef) ?? null
  const hasCriteria = Object.values(draft).some((v) => v.trim()) || from || to

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (!hasCriteria) return
    if (!sourceId) return
    setPage(1)
    setApplied({ sourceId, c: { ...draft }, from, to })
    setFormOpen(false)
    setSelectedRef(null)
  }
  const clear = () => { setDraft({}); setFrom(''); setTo(''); setApplied(null); setPage(1); setSelectedRef(null); setTouched(false) }
  const removeChip = (key: string) => {
    if (!applied) return
    const c = { ...applied.c }; delete c[key]
    const next = key === 'from' ? { ...applied, c, from: '' } : key === 'to' ? { ...applied, c, to: '' } : { ...applied, c }
    setDraft(next.c); setFrom(next.from); setTo(next.to)
    setPage(1); setApplied(Object.values(next.c).some(Boolean) || next.from || next.to ? next : null)
  }

  const openDoc = (r: RecordItem, docId: string) => {
    if (!can('VIEW_PDF')) return
    setFailure('')
    setSelectedRef(r.ref)
    setOpenDocId(docId)
  }

  const appliedSource = (applied ? sources.find((s) => s.id === applied.sourceId) : source) ?? source
  const chips = applied ? [
    ...Object.entries(applied.c).filter(([, v]) => v.trim()).map(([k, v]) => { const f = appliedSource?.fields.find((x) => x.key === k) ?? { label:k, mode:'EXACT' as const }; return { key: k, label: f.label, mode: MODE_HINT[f.mode], value: v } }),
    ...(applied.from ? [{ key: 'from', label: 'Updated from', mode: '', value: applied.from }] : []),
    ...(applied.to ? [{ key: 'to', label: 'Updated to', mode: '', value: applied.to }] : []),
  ] : []

  const collapsed = !wide && applied && !formOpen

  return (
    <div className={`ws${selected && wide ? ' has-insp' : ''}`}>
      <section className="ws-search" aria-label="Search records">
        <div className="ws-search-head">
          <div className="titles">
            <div className="eyebrow">Records workspace</div>
            <h1>Find a record</h1>
          </div>
          {collapsed ? (
            <button type="button" className="v-btn sm ws-search-toggle" onClick={() => setFormOpen(true)} aria-expanded="false"><SlidersHorizontal size={15} aria-hidden />Modify</button>
          ) : !wide && applied ? (
            <button type="button" className="v-btn quiet sm ws-search-toggle" onClick={() => setFormOpen(false)} aria-expanded="true"><ChevronUp size={15} aria-hidden />Hide</button>
          ) : null}
        </div>
        {!collapsed && (
          <form className="ws-search-form" onSubmit={submit} noValidate>
            <label className="v-field">
              <span>Source</span>
              <select className="v-select" value={sourceId} onChange={(e) => { setSourceId(e.target.value); setDraft({}); setTouched(false) }}>
                {sources.filter((s) => s.status === 'ACTIVE').map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </label>
            <div className="ws-fields">
              {fields.map((f) => (
                <label key={f.key} className="v-field">
                  <span>{f.label}<span className="v-hint">{MODE_HINT[f.mode]}</span></span>
                  <input className="v-input" value={draft[f.key] ?? ''} inputMode={f.normalization === 'DIGITS_ONLY' ? 'numeric' : undefined} autoComplete="off" spellCheck={false}
                    onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))} placeholder={f.identifier ? 'Identifier' : ''} />
                </label>
              ))}
            </div>
            <fieldset className="v-field" style={{ border: 0, padding: 0, margin: 0 }}>
              <legend className="v-label" style={{ marginBottom: 6 }}>Last updated</legend>
              <div className="ws-date-fields">
                <input className="v-input" type="date" aria-label="Last updated from" value={from} onChange={(e) => setFrom(e.target.value)} />
                <span className="faint" aria-hidden>–</span>
                <input className="v-input" type="date" aria-label="Last updated to" value={to} onChange={(e) => setTo(e.target.value)} />
              </div>
            </fieldset>
            {touched && !hasCriteria && <Callout tone="warn" title="Enter at least one criterion">Open-ended browsing isn&apos;t supported. Search by an identifier or field.</Callout>}
            <div className="row">
              <button type="submit" className="v-btn primary grow"><Search size={16} aria-hidden />Search</button>
              <button type="button" className="v-btn" onClick={clear}>Clear</button>
            </div>
            <small className="faint">Up to {BUSINESS_LIMITS.maxResults} matches. Results include only records your role is authorized to see.</small>
          </form>
        )}
        {collapsed && chips.length > 0 && (
          <div style={{ padding: '0 16px 14px' }} className="ws-chips">
            {chips.map((c) => <span key={c.key} className="ws-chip"><b>{c.label}</b>{c.value}</span>)}
          </div>
        )}
      </section>

      <section className="ws-results" aria-label="Search results">
        <ResultsHeader count={results.length} hasMore={hasMore} applied={!!applied} sourceName={appliedSource?.name ?? 'SDV'} chips={wide ? chips : []} onRemove={removeChip} />
        {status === 'loading' ? <Skeleton rows={7} /> : status === 'error' ? <ErrorState what="search results" /> : !applied ? (
          <Empty title="Search to begin" icon={<Search size={20} />}>Choose a source and enter an identifier, name or entity. Each field shows how it matches.</Empty>
        ) : results.length === 0 ? (
          <Empty title="No matching records" action={<button type="button" className="v-btn sm" onClick={() => { setFormOpen(true) }}>Modify search</button>}>
            Exact fields must match in full. Try fewer criteria, or a starts-with field such as last name.
          </Empty>
        ) : (
          <>
            <ul className="ws-list">
              {results.map((r) => (
                <li key={r.ref}>
                  <button type="button" className="ws-rec" aria-current={r.ref === selectedRef} onClick={() => setSelectedRef(r.ref === selectedRef && wide ? null : r.ref)}>
                    <span className="ws-stack" aria-hidden><i /><i /><i /><span>{documentCounts[r.ref] ?? r.docs.length}</span></span>
                    <span className="stack grow" style={{ gap: 4 }}>
                      <span className="ids">{r.identifiers.map((id) => <span key={id.label}><b>{id.label}</b>{id.value}</span>)}</span>
                      <span className="title">{r.title}</span>
                      <span className="meta">
                        <span>{documentCounts[r.ref] ?? r.docs.length} document{(documentCounts[r.ref] ?? r.docs.length) === 1 ? '' : 's'}</span>
                        <span>Updated {fmtRel(r.updatedAt)}</span>
                        {r.incomplete && <Badge tone="warn">Incomplete submission</Badge>}
                      </span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
            <div className="ws-pager"><span>Page {page} · {appliedCount} record{appliedCount === 1 ? '' : 's'} on this page{hasMore ? ' · more available' : ''}</span><span><button className="v-btn sm" type="button" disabled={page===1} onClick={()=>{setPage(p=>p-1);setSelectedRef(null)}}>Previous</button> <button className="v-btn sm" type="button" disabled={!hasMore} onClick={()=>{setPage(p=>p+1);setSelectedRef(null)}}>Next</button></span></div>
          </>
        )}
      </section>

      {selected && wide && <aside className="ws-inspector" aria-label="Record inspector"><Inspector record={selected} onClose={() => setSelectedRef(null)} onOpen={(id) => openDoc(selected, id)} /></aside>}
      {selected && !wide && !openDocId && (
        <Drawer eyebrow="Record" title={selected.identifiers[0].value} onClose={() => setSelectedRef(null)}
          footer={can('VIEW_PDF') && selected.docs[0] ? <button type="button" className="v-btn primary" onClick={() => openDoc(selected, selected.docs[0].id)}>Open documents<ArrowRight size={16} aria-hidden /></button> : undefined}>
          <InspectorBody record={selected} onOpen={(id) => openDoc(selected, id)} />
        </Drawer>
      )}
      {selected && openDocId && can('VIEW_PDF') && <Viewer record={selected} results={results} docId={openDocId} onClose={() => setOpenDocId(null)} />}
      {failure && <div role="alert" className="v-callout">{failure}</div>}
    </div>
  )
}

function ResultsHeader({ count, hasMore, applied, sourceName, chips, onRemove }: { count: number; hasMore: boolean; applied: boolean; sourceName: string; chips: { key: string; label: string; mode: string; value: string }[]; onRemove: (k: string) => void }) {
  return (
    <div className="ws-results-head">
      <div className="stack" style={{ gap: 2 }}>
        <h2>{applied ? `${count} record${count === 1 ? '' : 's'} on this page${hasMore ? ' · more available' : ''}` : 'Results'}</h2>
        <span className="faint" style={{ fontSize: 13 }}>{sourceName}</span>
      </div>
      {chips.length > 0 && (
        <div className="ws-chips" aria-label="Applied criteria">
          {chips.map((c) => (
            <span key={c.key} className="ws-chip"><b>{c.label}{c.mode && ` · ${c.mode.toLowerCase()}`}</b>{c.value}
              <button type="button" onClick={() => onRemove(c.key)} aria-label={`Remove ${c.label}`}><X size={13} /></button>
            </span>
          ))}
        </div>
      )}
    </div>
  )
}

function Inspector({ record, onClose, onOpen }: { record: RecordItem; onClose: () => void; onOpen: (id: string) => void }) {
  const { can } = useStore()
  return (
    <>
      <header className="ws-insp-head">
        <div className="grow stack" style={{ gap: 2 }}>
          <span className="eyebrow">Record</span>
          <h2>{record.title}</h2>
        </div>
        <button type="button" className="v-btn quiet icon sm" onClick={onClose} aria-label="Close record"><X size={18} /></button>
      </header>
      <div className="ws-insp-body">
        <InspectorBody record={record} onOpen={onOpen} />
        {can('VIEW_PDF') && record.docs[0] && <button type="button" className="v-btn primary block" onClick={() => onOpen(record.docs[0].id)}>Open documents<ArrowRight size={16} aria-hidden /></button>}
      </div>
    </>
  )
}

function InspectorBody({ record, onOpen }: { record: RecordItem; onOpen: (id: string) => void }) {
  const { can, liveSession } = useStore()
  const [more, setMore] = useState(false)
  const [includeViews, setIncludeViews] = useState(false)
  const [liveEvents, setLiveEvents] = useState<LiveDocumentActivity[]>([])
  const [activityFailure, setActivityFailure] = useState('')
  useEffect(() => {
    if (!liveSession || !record.docs.length) {setLiveEvents([]);return}
    let active=true
    setActivityFailure('')
    Promise.all(record.docs.map(d=>documentActivity(d.id,includeViews,100)))
      .then(groups=>{if(active)setLiveEvents(groups.flat().sort((a,b)=>b.occurredAt.localeCompare(a.occurredAt)))})
      .catch(e=>{if(active){setLiveEvents([]);setActivityFailure(String(e))}})
    return ()=>{active=false}
  },[liveSession,record.ref,record.docs.map(d=>d.id).join(','),includeViews])
  const recent = record.docs.flatMap((d) => d.activity.map((a) => ({ ...a, doc: d.kind }))).sort((a, b) => b.at.localeCompare(a.at)).slice(0, more ? 8 : 3)
  return (
    <>
      <div className="ws-ids">{record.identifiers.map((i) => <div key={i.label}><span>{i.label}</span><strong>{i.value}</strong></div>)}</div>
      {record.incomplete && (
        <Callout tone="warn" title={`Submission incomplete · ${record.incomplete.received} of ${record.incomplete.expected} documents`}>
          The producer hasn&apos;t delivered every expected document yet. Received documents are already available below.
        </Callout>
      )}
      <div className="stack" style={{ gap: 10 }}>
        <div className="row between"><h3>Documents</h3><span className="faint" style={{ fontSize: 13 }}>{record.docs.length}</span></div>
        <ul className="ws-docs">
          {record.docs.map((d) => (
            <li key={d.id}>
              <button type="button" className="ws-doc" onClick={() => onOpen(d.id)} disabled={!can('VIEW_PDF')}>
                <span className="sheet" aria-hidden />
                <span className="grow" style={{ minWidth: 0 }}>
                  <strong className="truncate">{d.kind}</strong>
                  <span>{d.pages > 0 ? `${d.pages} page${d.pages === 1 ? '' : 's'} · ` : ''}ingested {fmtDate(d.ingestedAt)}{d.redactions.length > 0 && ' · redacted'}</span>
                </span>
                {d.tags.length > 0 && <Badge>{d.tags.length} tag{d.tags.length === 1 ? '' : 's'}</Badge>}
                <FileText size={16} aria-hidden className="faint" />
              </button>
            </li>
          ))}
        </ul>
      </div>
      <div className="stack" style={{ gap: 4 }}>
        <h3>Details</h3>
        <dl className="v-kv">{record.metadata.map((m) => <div key={m.label}><dt>{m.label}</dt><dd>{m.value}</dd></div>)}</dl>
      </div>
      <div className="stack" style={{ gap: 10 }}>
        <h3>Recent activity</h3>
        {liveSession && <label className="v-check"><input type="checkbox" checked={includeViews} onChange={e=>setIncludeViews(e.target.checked)}/>Include document view activity</label>}
        {activityFailure && <Callout tone="warn" title="Activity unavailable">{activityFailure}</Callout>}
        <ol className="v-timeline">
          {liveSession ? liveEvents.slice(0,more?100:3).map(a=><li key={a.auditEventId}><span className={`pt ${a.outcome==='SUCCESS'?'ok':'bad'}`} aria-hidden/><div className="stack" style={{gap:0}}><strong>{humanize(a.action.replace('DOCUMENT_',''))}</strong><span>{a.actorId} · {fmtRel(a.occurredAt)}</span></div></li>) :
            recent.map((a) => <li key={a.id}><span className={`pt ${a.simulated ? 'sim' : a.outcome === 'SUCCESS' ? 'ok' : 'bad'}`} aria-hidden /><div className="stack" style={{ gap: 0 }}><strong>{humanize(a.action.replace('DOCUMENT_', ''))} · {a.doc}</strong><span>{a.actor} · {fmtRel(a.at)}{a.simulated && ' · simulated'}</span></div></li>)}
        </ol>
        {liveSession && !activityFailure && liveEvents.length===0 && <p className="faint">No matching activity.</p>}
        {(!liveSession || liveEvents.length>3) && <button type="button" className="v-btn quiet sm" style={{ alignSelf: 'flex-start' }} onClick={() => setMore((m) => !m)}>{more ? <ChevronUp size={14} aria-hidden /> : <ChevronDown size={14} aria-hidden />}{more ? 'Show less' : 'Show more'}</button>}
      </div>
      {!can('VIEW_PDF') && <Callout tone="warn" title="Viewing isn't assigned"><AlertTriangle size={0} aria-hidden />Your role can find this record but not open its documents.</Callout>}
    </>
  )
}
