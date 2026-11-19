import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { AlertTriangle, ArrowRight, Search, SlidersHorizontal, Sparkles } from 'lucide-react'
import { useStore } from './store'
import { failureText } from './CardError'
import type { Source } from './model'
import type { RecordSummary } from '../api'
import { recentRecords, schema, search, toSource, type RecentRecord } from './liveApi'
import { Badge, Callout, Skeleton, fmtRel } from './ui'
import { boxLabel, quickRange, routeBoxInput } from './searchRouting'
import { NOTHING_TO_SEARCH, defaultSource, searchableSources } from './sourceChoice'
import { extraField } from './recordColumns'

const showDevelopmentUtilities = import.meta.env.DEV
const RECENT_LIMIT = 8
const ATTENTION_LIMIT = 5

type Attention = { sourceId: string; record: RecordSummary }

const greetingFor = (hour: number) => (hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening')
const firstNameOf = (displayName?: string | null) => displayName?.trim().split(/\s+/)[0] || ''
const lower = (t: string) => t.charAt(0).toLowerCase() + t.slice(1)

/** Criteria that find exactly this record in Records: each of its searchable identifiers, matched exactly. */
function identifierCriteria(record: RecordSummary, source: Source | undefined): Record<string, string> {
  const searchable = new Set(source?.fields.filter((f) => f.searchable).map((f) => f.key) ?? [])
  return Object.fromEntries(record.identifiers.filter((i) => searchable.has(i.fieldKey) && i.value).map((i) => [i.fieldKey, i.value]))
}

export function Home() {
  const { liveSession, navigate, requestSearch } = useStore()
  const [sources, setSources] = useState<Source[]>([])
  const [sourcesLoaded, setSourcesLoaded] = useState(false)
  // The same rule as Records (sourceChoice.ts): only systems with a searchable field, and the same default.
  const candidates = useMemo(() => searchableSources(sources), [sources])
  const [recent, setRecent] = useState<RecentRecord[] | null>(null)
  const [recentError, setRecentError] = useState('')
  const [attention, setAttention] = useState<Attention[] | null>(null)
  const [attentionError, setAttentionError] = useState('')
  const [box, setBox] = useState('')
  const [boxMessage, setBoxMessage] = useState('')

  useEffect(() => { let active = true; schema().then((s) => { if (active) { setSources(s.sources.map(toSource)); setSourcesLoaded(true) } }).catch((e) => { if (active) setAttentionError(failureText('Home: search fields did not load', e)) }); return () => { active = false } }, [])
  useEffect(() => {
    let active = true
    recentRecords(RECENT_LIMIT).then((r) => { if (active) setRecent(r.records) }).catch((e) => { if (active) { setRecent([]); setRecentError(failureText('Home: recently viewed did not load', e)) } })
    return () => { active = false }
  }, [])
  // Needs attention: records with missing documents received in the last 30 days, from each source.
  useEffect(() => {
    if (!sourcesLoaded) return
    if (!candidates.length) { setAttention([]); return }
    let active = true
    const last30 = quickRange('Last 30 days')
    Promise.all(candidates.map((s) => search(s.id, {}, last30, { from: '', to: '' }, 1, { incompleteOnly: true, pageSize: ATTENTION_LIMIT })
      .then((r) => r.records.map((record) => ({ sourceId: s.id, record })))))
      .then((lists) => { if (active) setAttention(lists.flat().sort((a, b) => b.record.latestSubmissionAt.localeCompare(a.record.latestSubmissionAt)).slice(0, ATTENTION_LIMIT)) })
      .catch((e) => { if (active) { setAttention([]); setAttentionError(failureText('Home: needs attention did not load', e)) } })
    return () => { active = false }
  }, [sourcesLoaded, candidates])

  const source = defaultSource(sources)
  const nothingToSearch = sourcesLoaded && candidates.length === 0
  const fields = useMemo(() => source?.fields.filter((f) => f.searchable) ?? [], [source])
  const label = boxLabel(fields)
  const firstName = firstNameOf(liveSession?.displayName)
  const greeting = greetingFor(new Date().getHours())
  // The canvas's wording, from this system's own field labels ("Contract number or borrower"). The box searches
  // those fields only, so it doesn't promise "anything else".
  const parts = label ? label.split(' or ') : []
  const placeholder = nothingToSearch ? 'Nothing to search yet' : parts.length ? parts.map((p, i) => (i ? lower(p) : p)).join(' or ') : 'Use Filters to search'
  const lede = parts.length ? `Find a ${parts.map(lower).join(', a ')} or a document.` : 'Find a record or a document.'
  const sourceName = (id: string) => sources.find((s) => s.id === id)?.name ?? 'the sending system'

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!box.trim()) return
    if (nothingToSearch) { setBoxMessage(NOTHING_TO_SEARCH); return }
    // Before the source list has loaded, Records routes the text itself once its sources are known.
    if (!source) { requestSearch({ box }); navigate('workspace'); return }
    const route = routeBoxInput(box, fields)
    if (!route) return
    if (!route.ok) { setBoxMessage(route.message); return }
    requestSearch({ box, sourceId: source?.id })
    navigate('workspace')
  }
  const openInRecords = (sourceId: string, record: RecordSummary) => {
    requestSearch({ sourceId, criteria: identifierCriteria(record, sources.find((s) => s.id === sourceId)), selectRef: record.recordRef })
    navigate('workspace')
  }

  return (
    <div className="home">
      <header className="home-head">
        <h1>{firstName ? `${greeting}, ${firstName}` : greeting}</h1>
        <p className="muted">{lede}</p>
      </header>

      {/* One box with Filters and Search inside it, as on the canvas. */}
      <form className="home-search" role="search" aria-label="Record search" onSubmit={submit}>
        <Search size={20} aria-hidden />
        <input type="search" aria-label="Search records" value={box} autoComplete="off" spellCheck={false}
          placeholder={placeholder} aria-describedby={boxMessage ? 'home-box-message' : undefined}
          onChange={(e) => { setBox(e.target.value); setBoxMessage('') }}
          onKeyDown={(e) => { if (e.key === 'Escape') { e.preventDefault(); setBox(''); setBoxMessage('') } }} />
        <button type="button" className="v-btn quiet" disabled={nothingToSearch} onClick={() => { requestSearch({ openFilters: true, sourceId: source?.id }); navigate('workspace') }}><SlidersHorizontal size={16} aria-hidden />Filters</button>
        <button type="submit" className="v-btn primary">Search</button>
      </form>
      {boxMessage && <p id="home-box-message" className="ws-box-message" role="status">{boxMessage}</p>}

      <div className="home-grid">
        <section className="home-card" aria-labelledby="home-recent">
          <header className="home-card-head"><h2 id="home-recent">Recently viewed</h2><span className="faint">Only you see this list</span></header>
          {recent === null ? <Skeleton rows={4} /> : recentError ? <Callout tone="warn" title="Recently viewed is unavailable">{recentError}</Callout> : recent.length === 0 ? (
            <p className="home-empty">Records you open will show here.</p>
          ) : (
            <ul className="home-list">
              {recent.map((r) => {
                const preview = r.record.metadataPreview.map((f) => ({ fieldKey: f.fieldKey, label: f.displayLabel, value: f.value }))
                const extra = extraField(r.record.title, preview, sources.find((s) => s.id === r.sourceId))
                return (
                  <li key={r.record.recordRef}>
                    <button type="button" className="home-row" onClick={() => openInRecords(r.sourceId, r.record)}>
                      <span className="mono">{r.record.identifiers[0]?.value ?? '—'}</span>
                      <span className="home-name">{r.record.title || r.record.identifiers[0]?.value || '—'}</span>
                      <span className="faint home-type">{extra?.value ?? ''}</span>
                      <span className="faint home-when">{fmtRel(r.lastOpenedAt)}</span>
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
        </section>

        <div className="home-side">
          <section className="home-card home-pad" aria-labelledby="home-attention">
            <header className="home-side-head"><h2 id="home-attention">Needs attention</h2>
              {/* Task 0005's "Show all": every record with missing documents, in Records (not on the canvas; kept). */}
              {attention && attention.length > 0 && (
                <button type="button" className="v-btn quiet sm" onClick={() => { const first = attention[0]; requestSearch({ sourceId: first.sourceId, incompleteOnly: true, receivedLast30Days: true }); navigate('workspace') }}>
                  Show all<ArrowRight size={14} aria-hidden />
                </button>
              )}
            </header>
            {attention === null ? <Skeleton rows={2} /> : attentionError ? <Callout tone="warn" title="Needs attention is unavailable">{attentionError}</Callout> : attention.length === 0 ? (
              <p className="home-empty flat">Nothing needs attention.</p>
            ) : (
              <ul className="home-attention-list">
                {attention.map((a) => (
                  <li key={a.record.recordRef}>
                    <button type="button" className="v-callout warn home-attention" onClick={() => openInRecords(a.sourceId, a.record)}>
                      <AlertTriangle size={18} aria-hidden />
                      <span><strong>{a.record.identifiers[0]?.value ?? '—'}{a.record.title && <> · {a.record.title}</>}</strong><br />
                        {a.record.incompleteExpectedCount
                          ? `Only ${a.record.incompleteReceivedCount ?? 0} of ${a.record.incompleteExpectedCount} documents have arrived. The rest are still coming from ${sourceName(a.sourceId)}.`
                          : 'Some documents have not arrived yet.'}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {/* Reserved for "Ask Vero" (later release). Never shown in a product build; the slot keeps the layout stable. */}
          <div className="home-ask-slot" aria-hidden={!showDevelopmentUtilities}>
            {showDevelopmentUtilities && (
              <section className="home-card home-ask">
                <h2><Sparkles size={18} aria-hidden />Ask Vero <Badge tone="ok">Later release</Badge></h2>
                <p className="muted">Ask across your records, for example "contracts with a cancellation deadline this month". The box appears here when the AI layer ships.</p>
              </section>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
