import { Fragment, useEffect, useRef, useState } from 'react'
import { ChevronDown, ChevronRight, Download } from 'lucide-react'
import { ApiRequestError } from '../api'
import { FailureNotice, loadFailed, type Failure } from './CardError'
import { intakeReport, intakeReportCsv, type IntakeDeliveryLine, type IntakeFilter, type IntakeReport, type IntakeSystemBlock } from './liveApi'

/**
 * Task 0032, board Reports-Intake: the Business admin's Reports tab. Daily intake now; Sensitivity comes in task 0033
 * and is shown disabled until then.
 *
 * Counts only: a line shows at most a delivery's request ID (as its sending system sent it), its time, its record's
 * primary identifier and counts. Never a title, file name, name or content. Days are the tenant's (its time zone,
 * shown read-only beside the filters). The CSV has one line per document and is built and audited by the server.
 */

const MAX_DAYS = 31
const n = (v: number) => v.toLocaleString('en-US')
const plural = (v: number, word: string) => `${n(v)} ${word}${v === 1 ? '' : 's'}`

/** "Contract number" -> { one: "Contract", many: "Contracts" }; "Patient ID" -> Patient(s); otherwise Record(s). */
export function recordWords(label: string | null | undefined): { one: string; many: string } {
  const m = /^(.+?)\s+(number|no\.?|id|reference|ref)$/i.exec((label ?? '').trim())
  const word = (m ? m[1] : '').trim()
  if (!word || word.split(/\s+/).length > 2) return { one: 'Record', many: 'Records' }
  const one = word.charAt(0).toUpperCase() + word.slice(1).toLowerCase()
  return { one, many: /s$/i.test(one) ? one : one + 's' }
}

/** "America/New_York" -> "Eastern time"; "UTC" stays "UTC". */
export function zoneWords(zone: string): string {
  if (zone === 'UTC' || zone === 'Etc/UTC') return 'UTC'
  try {
    const name = new Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'longGeneric' }).formatToParts(new Date()).find((p) => p.type === 'timeZoneName')?.value
    return name ? name.replace(/ Time$/, ' time') : zone
  } catch { return zone }
}

/** A day (YYYY-MM-DD) in words, without moving it through this computer's time zone: "Fri 20 Nov". */
export function dayWords(day: string): string {
  const [y, m, d] = day.split('-').map(Number)
  const parts = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }).formatToParts(new Date(Date.UTC(y, m - 1, d)))
  const part = (t: string) => parts.find((p) => p.type === t)?.value ?? ''
  return `${part('weekday')} ${part('day')} ${part('month')}`
}
const shiftDay = (day: string, by: number) => { const [y, m, d] = day.split('-').map(Number); return new Date(Date.UTC(y, m - 1, d + by)).toISOString().slice(0, 10) }
const daysBetween = (a: string, b: string) => Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / 86_400_000)

/** "Today, Fri 20 Nov", "Yesterday, Thu 19 Nov", "Mon 16 Nov", "Sun 1 Nov – Fri 20 Nov". */
export function rangeWords(from: string, to: string, today: string): string {
  if (from !== to) return `${dayWords(from)} – ${dayWords(to)}`
  if (from === today) return `Today, ${dayWords(from)}`
  if (from === shiftDay(today, -1)) return `Yesterday, ${dayWords(from)}`
  return dayWords(from)
}

/** A long request ID shortened in the middle, as on the board ("7c1e-90a2-…-4f18"); the full ID is its title. */
export function shortId(id: string): string {
  return id.length > 20 ? `${id.slice(0, 10)}…${id.slice(-5)}` : id
}

function Codes({ line }: { line: IntakeDeliveryLine }) {
  if (!line.refused) return <>0</>
  return <span className="rpt-codes">{line.refusedCodes.map((c) => <span key={c} className="rpt-code">{c}</span>)}{line.refused > line.refusedCodes.length && <span className="faint"> ×{line.refused}</span>}</span>
}

export function Reports() {
  const [filter, setFilter] = useState<IntakeFilter>({})
  const [report, setReport] = useState<IntakeReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [failure, setFailure] = useState<Failure | null>(null)
  const [refusal, setRefusal] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  const [open, setOpen] = useState<Record<string, boolean>>({})
  const [dayOpen, setDayOpen] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [exportNote, setExportNote] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => {
    let active = true
    setLoading(true); setFailure(null); setRefusal(null)
    intakeReport(filter).then((r) => { if (active) setReport(r) }).catch((e) => {
      if (!active) return
      // A coded refusal (SDV-RPT-…) is the server's answer to these filters: shown in its words, the report kept.
      if (e instanceof ApiRequestError && e.code) setRefusal(`${e.message} (${e.code})`)
      else setFailure(loadFailed('The Daily intake report', e, "The Daily intake report didn't load"))
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [filter, reload])

  const exportCsv = async () => {
    if (!report) return
    setExporting(true); setExportNote(null)
    const scope = { from: report.from, to: report.to, sourceId: report.sourceId ?? undefined }
    try {
      const blob = await intakeReportCsv(scope)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url; link.download = `vero-daily-intake-${report.from}${report.to === report.from ? '' : '-to-' + report.to}.csv`
      document.body.appendChild(link); link.click(); link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      setExportNote({ ok: true, text: 'The file is saved. The export is recorded in the audit.' })
    } catch (e) {
      setExportNote({ ok: false, text: e instanceof ApiRequestError && e.code ? `${e.message} (${e.code})` : 'The export didn’t work. Try again; if it keeps failing, ask your IT team.' })
    } finally { setExporting(false) }
  }

  const words = recordWords(report?.primaryIdentifierLabel)
  // The first sending system starts open, the others collapsed (as on the board); a choice is kept while filtering.
  const isOpen = (b: IntakeSystemBlock, i: number) => open[b.sourceId] ?? i === 0
  const oneDay = report ? report.from === report.to : true
  const time = (l: IntakeDeliveryLine) => (oneDay ? l.receivedLocal.slice(11, 16) : `${dayWords(l.receivedLocal.slice(0, 10)).replace(/^\w+ /, '')} ${l.receivedLocal.slice(11, 16)}`)
  const t = report?.totals

  return <>
    <div className="rpt-bar">
      <div className="rpt-subtabs" role="tablist" aria-label="Reports">
        <button type="button" role="tab" aria-selected="true" className="on">Daily intake</button>
        <button type="button" role="tab" aria-selected="false" aria-disabled="true" disabled title="Comes in the next update">Sensitivity</button>
      </div>
      <span className="grow" />
      {report && <DayChip report={report} open={dayOpen} onOpen={setDayOpen} onChoose={(from, to) => { setDayOpen(false); setFilter((f) => ({ ...f, from, to })) }} />}
      {report && <label className="rpt-chip rpt-select"><b>Sending system</b>
        <select aria-label="Sending system" value={filter.sourceId ?? ''} onChange={(e) => setFilter((f) => ({ ...f, from: report.from, to: report.to, sourceId: e.target.value || undefined }))}>
          <option value="">All</option>
          {report.sendingSystems.map((s) => <option key={s.sourceId} value={s.sourceId}>{s.displayName}</option>)}
        </select><ChevronDown size={12} strokeWidth={2.4} aria-hidden /></label>}
      {report && <span className="faint rpt-zone" title={`${report.timeZone}: ${report.timeZoneSource === 'TENANT' ? 'this tenant’s own setting' : 'the default from the install configuration'}. Read-only.`}>{zoneWords(report.timeZone)} (entity)</span>}
      <button type="button" className="v-btn" disabled={!report || exporting} onClick={() => void exportCsv()}><Download size={15} aria-hidden />{exporting ? 'Exporting…' : 'Export CSV'}</button>
    </div>
    {exportNote && <p role="status" className={exportNote.ok ? 'faint' : 'rpt-bad'} style={{ fontSize: 13, margin: 0 }}>{exportNote.text}</p>}
    {refusal && <p role="alert" className="rpt-bad" style={{ fontSize: 13, margin: 0 }}>{refusal}</p>}
    {failure && <FailureNotice failure={failure} loadTitle="The Daily intake report didn't load" onRetry={() => setReload((x) => x + 1)} />}
    {loading && !report && !failure && <p className="faint">Loading the Daily intake report…</p>}

    {report && t && <>
      <div className="rpt-kpis" aria-busy={loading}>
        <div className="rpt-kpi"><span>Deliveries received</span><b>{n(t.deliveries)}</b><i>from {plural(t.sendingSystems, 'sending system')}</i></div>
        <div className="rpt-kpi"><span>Documents accepted</span><b>{n(t.documents)}</b><i>{plural(t.pages, 'page')} · plus {n(t.duplicates)} exact duplicate{t.duplicates === 1 ? '' : 's'} (not stored again)</i></div>
        <div className="rpt-kpi"><span>{words.many}</span><b>{n(t.contracts)}</b><i>{n(t.newContracts)} new · {n(t.updatedContracts)} updated</i></div>
        <div className={`rpt-kpi${t.refused ? ' bad' : ''}`}><span>Refused at intake</span><b>{n(t.refused)}</b><i title={t.refusedCodes.map((c) => `${c.code}: ${c.count}`).join(', ')}>{t.refused ? 'see codes below' : 'none'}</i></div>
        <div className={`rpt-kpi${t.failed ? ' bad' : ''}`}><span>Failed after receipt</span><b>{n(t.failed)}</b><i>shown to Tenant IT</i></div>
      </div>
      <section className="v-panel flush rpt-card" aria-label="Deliveries by sending system">
        <table className="rpt-table">
          <thead><tr><th>Sending system · delivery</th><th>Received</th><th>{words.one}</th><th>Documents</th><th>Pages</th><th>Duplicates</th><th>Refused</th><th>Failed</th></tr></thead>
          <tbody>
            {report.systems.length === 0 && <tr><td colSpan={8} className="faint rpt-note">Nothing was received {oneDay ? 'on this day' : 'in these days'}.</td></tr>}
            {report.systems.map((b, i) => {
              const expanded = isOpen(b, i)
              return <Fragment key={b.sourceId}>
                <tr className="sys">
                  <td><span className="rpt-sys"><button type="button" className="v-btn xs rpt-tg" aria-label={`${expanded ? 'Collapse' : 'Expand'} ${b.displayName}`} aria-expanded={expanded} onClick={() => setOpen((o) => ({ ...o, [b.sourceId]: !expanded }))}>{expanded ? <ChevronDown size={12} strokeWidth={2.4} /> : <ChevronRight size={12} strokeWidth={2.4} />}</button>{b.displayName}</span></td>
                  <td>{n(b.totals.deliveries)}</td><td>{n(b.totals.contracts)}</td><td>{n(b.totals.documents)}</td><td>{n(b.totals.pages)}</td><td>{n(b.totals.duplicates)}</td>
                  <td className={b.totals.refused ? 'rpt-bad' : undefined}>{n(b.totals.refused)}</td><td className={b.totals.failed ? 'rpt-bad' : undefined}>{n(b.totals.failed)}</td>
                </tr>
                {expanded && b.deliveries.map((l, j) => <tr key={j}>
                  <td className="mono faint" title={l.requestId ?? 'No request ID: refused before a delivery existed'}>req {l.requestId ? shortId(l.requestId) : '—'}</td>
                  <td title={l.receivedLocal.replace('T', ' ')}>{time(l)}</td>
                  <td className="mono">{l.primaryIdentifier ?? '—'}</td>
                  <td>{n(l.documents)}</td><td>{n(l.pages)}</td><td>{n(l.duplicates)}</td><td><Codes line={l} /></td><td>{n(l.failed)}</td>
                </tr>)}
                {expanded && b.moreDeliveries > 0 && <tr><td colSpan={8} className="faint rpt-note">… {n(b.moreDeliveries)} more {b.moreDeliveries === 1 ? 'delivery' : 'deliveries'} · shown in full in the CSV</td></tr>}
              </Fragment>
            })}
            <tr className="tot"><td>All sending systems</td><td>{n(t.deliveries)}</td><td>{n(t.contracts)}</td><td>{n(t.documents)}</td><td>{n(t.pages)}</td><td>{n(t.duplicates)}</td>
              <td className={t.refused ? 'rpt-bad' : undefined}>{n(t.refused)}</td><td className={t.failed ? 'rpt-bad' : undefined}>{n(t.failed)}</td></tr>
          </tbody>
        </table>
      </section>
      <p className="faint rpt-foot">Each delivery shows the request ID its sending system used, so the line can be matched with that system's own report. Duplicates are files whose checksum matches one already received; they are acknowledged to the sender and counted; no second copy is kept. Refusal codes are explained in the error code list.</p>
    </>}
  </>
}

/** The Day filter: one day, or a range of up to 31 days, in the tenant's days. */
function DayChip({ report, open, onOpen, onChoose }: { report: IntakeReport; open: boolean; onOpen: (v: boolean) => void; onChoose: (from: string, to: string) => void }) {
  const [from, setFrom] = useState(report.from)
  const [to, setTo] = useState(report.to)
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => { if (open) { setFrom(report.from); setTo(report.to) } }, [open, report.from, report.to])
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => { if (box.current && !box.current.contains(e.target as Node)) onOpen(false) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') onOpen(false) }
    document.addEventListener('mousedown', close); window.addEventListener('keydown', esc)
    return () => { document.removeEventListener('mousedown', close); window.removeEventListener('keydown', esc) }
  }, [open, onOpen])
  const span = from && to ? daysBetween(from, to) + 1 : 0
  const problem = !from || !to ? 'Choose both days.' : span < 1 ? 'The last day is before the first.' : span > MAX_DAYS ? `At most ${MAX_DAYS} days.` : null
  return (
    <div className="rpt-daywrap" ref={box}>
      <button type="button" className="rpt-chip" aria-haspopup="dialog" aria-expanded={open} onClick={() => onOpen(!open)}><b>Day</b> {rangeWords(report.from, report.to, report.today)} <ChevronDown size={12} strokeWidth={2.4} aria-hidden /></button>
      {open && <div className="rpt-pop" role="dialog" aria-label="Choose the days">
        <div className="row" style={{ gap: 6 }}>
          <button type="button" className="v-btn sm" onClick={() => onChoose(report.today, report.today)}>Today</button>
          <button type="button" className="v-btn sm" onClick={() => { const y = shiftDay(report.today, -1); onChoose(y, y) }}>Yesterday</button>
          <button type="button" className="v-btn sm" onClick={() => onChoose(shiftDay(report.today, -6), report.today)}>Last 7 days</button>
        </div>
        <label className="rpt-field">From<input type="date" value={from} max={report.today} onChange={(e) => { setFrom(e.target.value); if (!to || e.target.value > to) setTo(e.target.value) }} /></label>
        <label className="rpt-field">To<input type="date" value={to} max={report.today} onChange={(e) => setTo(e.target.value)} /></label>
        <span className={problem ? 'rpt-bad' : 'faint'} style={{ fontSize: 12 }}>{problem ?? `${span} day${span === 1 ? '' : 's'}, in the tenant's time zone. One day, or up to ${MAX_DAYS}.`}</span>
        <div className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
          <button type="button" className="v-btn sm quiet" onClick={() => onOpen(false)}>Cancel</button>
          <button type="button" className="v-btn sm primary" disabled={!!problem} onClick={() => onChoose(from, to)}>Show</button>
        </div>
      </div>}
    </div>
  )
}
