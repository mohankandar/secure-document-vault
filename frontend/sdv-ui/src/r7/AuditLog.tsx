import { useEffect, useMemo, useRef, useState } from 'react'
import { FailureNotice, exportFailureText, loadFailed, type Failure } from './CardError'
import { Drawer, TechDetails, fmtDateTime, levelWords } from './ui'
import { fmtWhen } from './opsWords'
import { opsAudit, opsAuditExport, type OpsAuditEvent } from './liveApi'
import { ACTIVITY_GROUPS, RESULT_WORDS, actorName, auditSentence } from './auditWords'

// Task 0011 Part B (finding 17): the audit as sentences, everyday filters, and codes only under Technical details.

/** Routine events: hidden unless "Include view events" is ticked or that action is filtered for explicitly (as before). */
const ROUTINE = new Set(['AUDIT_VIEWED', 'BUSINESS_RECENT_RECORDS_LISTED'])
type Period = 'today' | '7' | '30' | 'custom'
type Advanced = { action: string; outcome: string; actorType: string; actorId: string; targetType: string; targetId: string; correlationId: string }
const NO_ADVANCED: Advanced = { action: '', outcome: '', actorType: '', actorId: '', targetType: '', targetId: '', correlationId: '' }

function periodRange(p: Period, from: string, to: string): { from?: string; to?: string } {
  const start = new Date(); start.setHours(0, 0, 0, 0)
  if (p === 'today') return { from: start.toISOString() }
  if (p === '7' || p === '30') { start.setDate(start.getDate() - (Number(p) - 1)); return { from: start.toISOString() } }
  return { from: from ? new Date(from + 'T00:00:00').toISOString() : undefined, to: to ? new Date(to + 'T23:59:59.999').toISOString() : undefined }
}

export function AuditLog() {
  const [events, setEvents] = useState<OpsAuditEvent[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [loading, setLoading] = useState(false)
  const [activity, setActivity] = useState('')
  const [person, setPerson] = useState('')
  const [people, setPeople] = useState<Record<string, string>>({})
  // Last 7 days by default, as on the canvas (owner, task 0019 follow-up).
  const [period, setPeriod] = useState<Period>('7')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')
  const [recordNumber, setRecordNumber] = useState('')
  // Task 0030 (item 6): the level each event recorded for its document when it happened.
  const [level, setLevel] = useState('')
  const [advanced, setAdvanced] = useState<Advanced>(NO_ADVANCED)
  const [moreOpen, setMoreOpen] = useState(false)
  const [includeRoutine, setIncludeRoutine] = useState(false)
  const [selected, setSelected] = useState<OpsAuditEvent | null>(null)
  const [query, setQuery] = useState(0)

  const group = ACTIVITY_GROUPS.find((g) => g.id === activity)
  const filters = useMemo(() => {
    const range = periodRange(period, customFrom, customTo)
    return {
      ...advanced,
      outcome: advanced.outcome || group?.outcome || '',
      actorId: advanced.actorId || person,
      recordNumber: recordNumber.trim(),
      sensitivityLevel: level,
      from: range.from ?? '', to: range.to ?? '',
    }
  }, [advanced, group, person, recordNumber, level, period, customFrom, customTo, query]) // eslint-disable-line react-hooks/exhaustive-deps
  const remember = (rows: OpsAuditEvent[]) => setPeople((all) => { const next = { ...all }; for (const e of rows) if (e.actorType === 'USER' && !next[e.actorId]) next[e.actorId] = actorName(e); return next })

  // Each filter change starts a new generation; an answer for an older generation (a first page or "older events"
  // still on its way when the filters changed) is dropped, so rows from the previous filters never mix in.
  const generation = useRef(0)
  useEffect(() => {
    const mine = ++generation.current
    setLoading(true); setError(null)
    opsAudit(undefined, filters).then((r) => { if (generation.current === mine) { setEvents(r.events); setCursor(r.nextCursor); remember(r.events) } })
      .catch((e) => { if (generation.current === mine) setError(loadFailed('The audit did not load', e)) })
      .finally(() => { if (generation.current === mine) setLoading(false) })
  }, [filters]) // eslint-disable-line react-hooks/exhaustive-deps
  const more = async () => {
    if (!cursor) return
    const mine = generation.current
    setLoading(true)
    try { const r = await opsAudit(cursor, filters); if (generation.current === mine) { setEvents((x) => [...x, ...r.events]); setCursor(r.nextCursor); remember(r.events) } }
    catch (e) { if (generation.current === mine) setError(loadFailed('Older audit events did not load', e, "Older events didn't load")) }
    finally { if (generation.current === mine) setLoading(false) }
  }
  // Activity groups with several actions are applied to the loaded events, as routine events always were.
  const shown = events.filter((e) => (includeRoutine || !ROUTINE.has(e.action) || advanced.action === e.action) && (!group?.actions || group.actions.includes(e.action)))
  // A group with nothing in the loaded events looks a few pages further back by itself.
  const [lookBack, setLookBack] = useState(0)
  useEffect(() => { setLookBack(0) }, [activity, filters])
  useEffect(() => { if (group?.actions && shown.length === 0 && cursor && !loading && lookBack < 4) { setLookBack((n) => n + 1); void more() } }, [group, shown.length, cursor, loading, lookBack]) // eslint-disable-line react-hooks/exhaustive-deps
  const badDates = period === 'custom' && !!customFrom && !!customTo && customFrom > customTo

  // Export: the server builds the file from these same filters over the whole period, and records the export.
  const [exporting, setExporting] = useState(false)
  const [exportNote, setExportNote] = useState<{ ok: boolean; text: string } | null>(null)
  const exportCsv = async () => {
    setExporting(true); setExportNote(null)
    try {
      const hidden = includeRoutine ? [] : [...ROUTINE].filter((a) => a !== advanced.action)
      const blob = await opsAuditExport(filters, group?.actions, hidden)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url; link.download = `vero-audit-${new Date().toISOString().slice(0, 10)}.csv`
      document.body.appendChild(link); link.click(); link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      setExportNote({ ok: true, text: 'The file is saved. The export is recorded in the audit.' })
    } catch (e) {
      setExportNote({ ok: false, text: `The export didn’t work. ${exportFailureText('The audit export failed', e)}` })
    } finally { setExporting(false) }
  }

  // Canvas: the filters on one line without labels (their names are their accessible labels), Export on the right;
  // the list with the person's name in bold and times as "Today 09:12"; a note on what a row holds.
  return <>
    {error && <FailureNotice failure={error} loadTitle="The audit didn't load" onRetry={() => setQuery((x) => x + 1)} />}
    <section aria-label="Audit filters" className="au-bar">
      <select className="v-select au-activity" aria-label="Activity" value={activity} onChange={(e) => setActivity(e.target.value)}>
        <option value="">All activity</option>{ACTIVITY_GROUPS.map((g) => <option key={g.id} value={g.id}>{g.label}</option>)}</select>
      <select className="v-select au-person" aria-label="Person" value={person} onChange={(e) => setPerson(e.target.value)}>
        <option value="">Person</option>{Object.entries(people).sort((a, b) => a[1].localeCompare(b[1])).map(([id, name]) => <option key={id} value={id}>{name === id ? id : `${name} (${id})`}</option>)}</select>
      <select className="v-select au-period" aria-label="Period" value={period} onChange={(e) => setPeriod(e.target.value as typeof period)}>
        <option value="today">Today</option><option value="7">Last 7 days</option><option value="30">Last 30 days</option><option value="custom">Dates…</option></select>
      <select className="v-select au-level" aria-label="Sensitivity" value={level} onChange={(e) => setLevel(e.target.value)} title="Events that recorded this level for their document when they happened: openings, page views, downloads, prints, refused openings and requests. Older openings and page views recorded no level, so they are not found.">
        <option value="">Any sensitivity</option><option value="CONFIDENTIAL">Confidential</option><option value="HIGHLY_CONFIDENTIAL">Highly Confidential</option></select>
      <form className="au-record" onSubmit={(e) => { e.preventDefault(); setQuery((x) => x + 1) }}>
        <input id="au-record" className="v-input mono" aria-label="Record number" inputMode="numeric" value={recordNumber} onChange={(e) => setRecordNumber(e.target.value)} placeholder="Record number" />
      </form>
      <button type="button" className="v-btn quiet sm" aria-expanded={moreOpen} onClick={() => setMoreOpen((v) => !v)}>{moreOpen ? 'Fewer filters' : 'More filters'}{Object.values(advanced).some(Boolean) || includeRoutine ? ' · set' : ''}</button>
      <span className="grow" />
      <button type="button" className="v-btn sm" disabled={exporting || badDates} onClick={() => void exportCsv()}>{exporting ? 'Exporting…' : 'Export'}</button>
    </section>
    {period === 'custom' && <div className="row" style={{ gap: 8 }}>
      <label className="v-field"><span>From</span><input className="v-input" type="date" value={customFrom} max={customTo || undefined} onChange={(e) => setCustomFrom(e.target.value)} /></label>
      <label className="v-field"><span>To</span><input className="v-input" type="date" value={customTo} min={customFrom || undefined} onChange={(e) => setCustomTo(e.target.value)} /></label>
    </div>}
    {moreOpen && <section className="v-panel au-advanced" aria-label="More filters">
      <label className="row" style={{ gap: 8, gridColumn: '1 / -1' }}><input type="checkbox" checked={includeRoutine} onChange={(e) => setIncludeRoutine(e.target.checked)} />Include view events</label>
      {([['action', 'Action code'], ['actorType', 'Actor type'], ['actorId', 'Actor ID'], ['targetType', 'Target type'], ['targetId', 'Target ID'], ['correlationId', 'Correlation ID']] as [keyof Advanced, string][]).map(([k, label]) =>
        <label key={k} className="v-field"><span>{label}</span><input className="v-input mono" value={advanced[k]} onChange={(e) => setAdvanced((a) => ({ ...a, [k]: e.target.value.trim() }))} /></label>)}
      <label className="v-field"><span>Result</span><select className="v-select" value={advanced.outcome} onChange={(e) => setAdvanced((a) => ({ ...a, outcome: e.target.value }))}>
        <option value="">Any</option><option value="SUCCESS">Done</option><option value="DENIED">Refused</option><option value="FAILED">Failed</option></select></label>
    </section>}
    {exportNote && <p className={exportNote.ok ? 'muted au-export-note' : 'au-export-note bad'} role={exportNote.ok ? 'status' : 'alert'}>{exportNote.text}</p>}

    <section className="v-panel flush" aria-label="Audit events">
      {loading && events.length === 0 ? <p className="muted" style={{ padding: 16 }}>Loading…</p> : shown.length === 0 ? <p className="muted" style={{ padding: 16 }}>Nothing matches in the events loaded so far.{cursor ? ' Load older events to look further back.' : ''}</p> :
      <table className="v-table au-table" aria-label="Audit list"><thead><tr><th>When</th><th>What happened</th><th>Record</th><th>Result</th></tr></thead>
        <tbody>{shown.map((e) => <tr key={e.auditEventId} className="clickable" tabIndex={0} onClick={() => setSelected(e)} onKeyDown={(k) => { if (k.key === 'Enter' || k.key === ' ') { k.preventDefault(); setSelected(e) } }}>
          <td data-label="When" className="faint">{fmtWhen(e.occurredAt)}</td>
          <td data-label="What happened" className="lead"><AuditWords e={e} /></td>
          <td data-label="Record" className="mono">{e.recordNumber ?? '—'}</td>
          <td data-label="Result"><span className={`v-dot ${e.outcome === 'SUCCESS' ? 'g' : 'r'}`} aria-hidden />{RESULT_WORDS[e.outcome] ?? e.outcome}</td>
        </tr>)}</tbody></table>}
      {cursor && <div style={{ padding: 12 }}><button type="button" className="v-btn" disabled={loading} onClick={() => void more()}>{loading ? 'Loading…' : 'Load older events'}</button></div>}
    </section>
    <p className="faint au-note">Click a row to see the full entry; its codes and IDs are under Technical details.</p>

    {selected && <Drawer eyebrow="Audit" title={auditSentence(selected)} onClose={() => setSelected(null)}>
      <dl className="v-kv">
        <div><dt>When</dt><dd>{fmtDateTime(selected.occurredAt)}</dd></div>
        <div><dt>Result</dt><dd><span className={`v-dot ${selected.outcome === 'SUCCESS' ? 'g' : 'r'}`} aria-hidden />{RESULT_WORDS[selected.outcome] ?? selected.outcome}</dd></div>
        {selected.recordNumber && <div><dt>Record</dt><dd className="mono">{selected.recordNumber}</dd></div>}
        {selected.documentId && <>
          <div><dt>Level</dt><dd>{levelWords(selected.sensitivityLevel)}</dd></div>
          <div><dt>Received</dt><dd>{fmtDateTime(selected.documentReceivedAt)}</dd></div>
          <div><dt>Pages</dt><dd>{selected.documentPageCount ?? '—'}</dd></div>
        </>}
      </dl>
      <TechDetails flat hint="action code, actor, target, IDs">
        <dl className="v-kv">
          <div><dt>Action code</dt><dd className="mono">{selected.action}</dd></div>
          <div><dt>Actor</dt><dd className="mono">{selected.actorType} · {selected.actorId}</dd></div>
          <div><dt>Target</dt><dd className="mono">{selected.targetType} · {selected.targetId}</dd></div>
          <div><dt>Event ID</dt><dd className="mono">{selected.auditEventId}</dd></div>
          <div><dt>Correlation ID</dt><dd className="mono">{selected.correlationId ?? '—'}</dd></div>
        </dl>
        <pre className="mono op-audit-details">{selected.safeDetailsJson ?? '—'}</pre>
      </TechDetails>
    </Drawer>}
  </>
}

/** An audit sentence with the person's (or system's) name in bold, as on the canvas. */
export function AuditWords({ e }: { e: OpsAuditEvent }) {
  const sentence = auditSentence(e), name = actorName(e)
  return name && sentence.startsWith(name) ? <><b>{name}</b>{sentence.slice(name.length)}</> : <>{sentence}</>
}
