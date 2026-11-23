import { Fragment, useEffect, useRef, useState, type ReactNode } from 'react'
import { ChevronDown, ChevronRight, Download } from 'lucide-react'
import { FailureNotice, describeFailure, exportFailureText, type Failure } from './CardError'
import { intakeReport, intakeReportCsv, sensitivityReport, sensitivityReportCsv, storageReport, storageReportCsv, activityReport, activityReportCsv, type ActivityReport, type IntakeDeliveryLine, type IntakeFilter, type IntakeReport, type IntakeSystemBlock, type SensitivityReport, type SensitivityRequestKind, type StorageReport } from './liveApi'
import type { SensitivityLevel } from '../api'
import { bytesWords, countedWords, monthWords, ruleWords } from './storageWords'

/**
 * Task 0032, board Reports-Intake: the Business admin's Reports tab, Daily intake. Task 0033, board
 * Reports-Sensitivity: the Sensitivity sub-tab (counts only; who and when stays in the audit trail).
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

/** Task 0039: failed after receipt, or at intake on Vero's side (with the code the sending system got). */
function FailedCell({ line }: { line: IntakeDeliveryLine }) {
  const codes = line.failedCodes ?? []
  if (!codes.length) return <>{n(line.failed)}</>
  return <span className="rpt-codes">{codes.map((c) => <span key={c} className="rpt-code">{c}</span>)}{line.failed > codes.length && <span className="faint"> ×{line.failed}</span>}</span>
}

/** Task 0033: the Reports tab with its two sub-tabs. The sub-tab is kept while the tab is open. */
export function Reports() {
  const [tab, setTab] = useState<'intake' | 'sensitivity' | 'storage' | 'activity'>('intake')
  const tabs = <div className="rpt-subtabs" role="tablist" aria-label="Reports">
    <button type="button" role="tab" aria-selected={tab === 'intake'} className={tab === 'intake' ? "on" : undefined} onClick={() => setTab('intake')}>Daily intake</button>
    <button type="button" role="tab" aria-selected={tab === 'sensitivity'} className={tab === 'sensitivity' ? "on" : undefined} onClick={() => setTab('sensitivity')}>Sensitivity</button>
    <button type="button" role="tab" aria-selected={tab === 'storage'} className={tab === 'storage' ? "on" : undefined} onClick={() => setTab('storage')}>Storage and retention</button>
    <button type="button" role="tab" aria-selected={tab === 'activity'} className={tab === 'activity' ? "on" : undefined} onClick={() => setTab('activity')}>Activity</button>
  </div>
  return tab === 'intake' ? <DailyIntake tabs={tabs} /> : tab === 'sensitivity' ? <SensitivityReportView tabs={tabs} /> : tab === 'storage' ? <StorageReportView tabs={tabs} /> : <ActivityReportView tabs={tabs} />
}

function DailyIntake({ tabs }: { tabs: ReactNode }) {
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
      // A coded refusal (SDV-RPT-…) is the server's answer to these filters: shown in the catalog's words, the report
      // kept. Anything else (not allowed, Vero's fault, no answer) is the card with Try again.
      const words = describeFailure('The Daily intake report did not load', e)
      if (words.kind === 'refusal' && words.code) setRefusal(words.text)
      else setFailure({ words, kind: 'load', title: "The Daily intake report didn't load" })
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
      setExportNote({ ok: false, text: `The export didn’t work. ${exportFailureText('A report export failed', e)}` })
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
      {tabs}
      <span className="grow" />
      {report && <DayChip report={report} open={dayOpen} onOpen={setDayOpen} onChoose={(from, to) => { setDayOpen(false); setFilter((f) => ({ ...f, from, to })) }} />}
      {report && <label className="rpt-chip rpt-select"><b>Sending system</b>
        <select aria-label="Sending system" value={filter.sourceId ?? ''} onChange={(e) => setFilter((f) => ({ ...f, from: report.from, to: report.to, sourceId: e.target.value || undefined }))}>
          <option value="">All</option>
          {report.sendingSystems.map((s) => <option key={s.sourceId} value={s.sourceId}>{s.displayName}</option>)}
        </select><ChevronDown size={12} strokeWidth={2.4} aria-hidden /></label>}
      {report && <span className="faint rpt-zone" title={`${report.timeZone}: ${report.timeZoneSource === 'TENANT' ? 'this tenant’s own setting' : 'the default from the install configuration'}. Read-only.`}>{zoneWords(report.timeZone)} (entity)</span>}
      <button type="button" className="v-btn" disabled={!report || loading || exporting} onClick={() => void exportCsv()}><Download size={15} aria-hidden />{exporting ? 'Exporting…' : 'Export CSV'}</button>
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
        <div className={`rpt-kpi${t.failed ? ' bad' : ''}`}><span>Failed</span><b>{n(t.failed)}</b><i title={(t.failedCodes ?? []).map((c) => `${c.code}: ${c.count}`).join(', ')}>{n(t.failed - (t.failedAtIntake ?? 0))} after receipt · {n(t.failedAtIntake ?? 0)} at intake</i></div>
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
                  <td>{n(l.documents)}</td><td>{n(l.pages)}</td><td>{n(l.duplicates)}</td><td><Codes line={l} /></td><td><FailedCell line={l} /></td>
                </tr>)}
                {expanded && b.moreDeliveries > 0 && <tr><td colSpan={8} className="faint rpt-note">… {n(b.moreDeliveries)} more {b.moreDeliveries === 1 ? 'delivery' : 'deliveries'} · shown in full in the CSV</td></tr>}
              </Fragment>
            })}
            <tr className="tot"><td>All sending systems</td><td>{n(t.deliveries)}</td><td>{n(t.contracts)}</td><td>{n(t.documents)}</td><td>{n(t.pages)}</td><td>{n(t.duplicates)}</td>
              <td className={t.refused ? 'rpt-bad' : undefined}>{n(t.refused)}</td><td className={t.failed ? 'rpt-bad' : undefined}>{n(t.failed)}</td></tr>
          </tbody>
        </table>
      </section>
      <p className="faint rpt-foot">Refused and failed deliveries from a known sending system are counted here, with the code each got; only failures while Vero&apos;s database was away can&apos;t be (they are in Vero&apos;s log). Attempts Vero couldn&apos;t link to a sending system (for example a failed sign-in) are listed for IT under Problems.</p>
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

// ---------------------------------------------------------------- task 0033: Sensitivity

const MAX_PERIOD_DAYS = 366
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']
const monthStart = (day: string) => day.slice(0, 8) + '01'
const monthEnd = (day: string) => { const [y, m] = day.split('-').map(Number); return new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10) }
const lastMonth = (today: string) => { const [y, m] = today.split('-').map(Number); const first = new Date(Date.UTC(y, m - 2, 1)).toISOString().slice(0, 10); return { from: first, to: monthEnd(first) } }
const monthName = (day: string) => MONTHS[Number(day.slice(5, 7)) - 1]

/** "This month (November)", "Last month (October)", "September 2026", "Today, Sat 21 Nov", or "Sun 1 Nov – Fri 20 Nov". */
export function periodWords(from: string, to: string, today: string): string {
  if (from === monthStart(today) && to === today) return `This month (${monthName(today)})`
  const last = lastMonth(today)
  if (from === last.from && to === last.to) return `Last month (${monthName(from)})`
  if (from === monthStart(from) && to === monthEnd(from)) return `${monthName(from)} ${from.slice(0, 4)}`
  return rangeWords(from, to, today)
}

/** The card headings' form: "this month", "last month", "today", or the period's own words. */
export function periodHint(from: string, to: string, today: string): string {
  const words = periodWords(from, to, today)
  if (words.startsWith('This month')) return 'this month'
  if (words.startsWith('Last month')) return 'last month'
  if (words.startsWith('Today')) return 'today'
  return words
}

const ROLE_WORDS: Record<string, string> = {
  BUSINESS_USER: 'Business user', DOCUMENT_STEWARD: 'Document steward', RESTRICTED_STEWARD: 'Restricted steward',
  BUSINESS_ADMIN: 'Business admin', IT_ADMIN: 'IT admin', PLATFORM_OPERATIONS_ADMIN: 'Platform operations admin',
}
/** The roles an attempt was made with, in words; attempts recorded before task 0033 name none. */
export const attemptRoleWords = (roles: string[]) => (roles.length ? roles.map((r) => ROLE_WORDS[r] ?? r).join(' and ') : 'Role not recorded')

const kindWords = (kind: SensitivityRequestKind, record: string) => (kind === 'DOWNLOAD' ? 'Download' : kind === 'PRINT' ? 'Print'
  : kind === 'LOWER_LEVEL' ? 'Lower level (one document)' : `Lower level (whole ${record})`)

/** A bar's width: its share of all stored documents; anything above none shows at least a sliver. */
const share = (v: number, total: number) => (total > 0 ? Math.max(v > 0 ? 1 : 0, Math.round((v / total) * 100)) : 0)

function SensitivityReportView({ tabs }: { tabs: ReactNode }) {
  const [filter, setFilter] = useState<IntakeFilter>({})
  const [report, setReport] = useState<SensitivityReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [failure, setFailure] = useState<Failure | null>(null)
  const [refusal, setRefusal] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  const [periodOpen, setPeriodOpen] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [exportNote, setExportNote] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => {
    let active = true
    setLoading(true); setFailure(null); setRefusal(null)
    sensitivityReport(filter).then((r) => { if (active) setReport(r) }).catch((e) => {
      if (!active) return
      const words = describeFailure('The Sensitivity report did not load', e)
      if (words.kind === 'refusal' && words.code) setRefusal(words.text)
      else setFailure({ words, kind: 'load', title: "The Sensitivity report didn't load" })
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [filter, reload])

  const exportCsv = async () => {
    if (!report) return
    setExporting(true); setExportNote(null)
    try {
      const blob = await sensitivityReportCsv({ from: report.from, to: report.to, sourceId: report.sourceId ?? undefined })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url; link.download = `vero-sensitivity-${report.from}-to-${report.to}.csv`
      document.body.appendChild(link); link.click(); link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      setExportNote({ ok: true, text: 'The file is saved. The export is recorded in the audit.' })
    } catch (e) {
      setExportNote({ ok: false, text: `The export didn’t work. ${exportFailureText('A report export failed', e)}` })
    } finally { setExporting(false) }
  }

  const words = recordWords(report?.primaryIdentifierLabel)
  const record = words.one.toLowerCase()
  const d = report?.documents
  const hint = report ? periodHint(report.from, report.to, report.today) : ''
  const levels: [string, string, number][] = d ? [['s', 'Standard', d.standard], ['c', 'Confidential', d.confidential], ['hc', 'Highly Confidential', d.highlyConfidential]] : []

  return <>
    <div className="rpt-bar">
      {tabs}
      <span className="grow" />
      {report && <PeriodChip report={report} open={periodOpen} onOpen={setPeriodOpen} onChoose={(from, to) => { setPeriodOpen(false); setFilter((f) => ({ ...f, from, to })) }} />}
      {report && <label className="rpt-chip rpt-select"><b>Sending system</b>
        <select aria-label="Sending system" value={filter.sourceId ?? ''} onChange={(e) => setFilter((f) => ({ ...f, from: report.from, to: report.to, sourceId: e.target.value || undefined }))}>
          <option value="">All</option>
          {report.sendingSystems.map((s) => <option key={s.sourceId} value={s.sourceId}>{s.displayName}</option>)}
        </select><ChevronDown size={12} strokeWidth={2.4} aria-hidden /></label>}
      <button type="button" className="v-btn" disabled={!report || loading || exporting} onClick={() => void exportCsv()}><Download size={15} aria-hidden />{exporting ? 'Exporting…' : 'Export CSV'}</button>
    </div>
    {exportNote && <p role="status" className={exportNote.ok ? 'faint' : 'rpt-bad'} style={{ fontSize: 13, margin: 0 }}>{exportNote.text}</p>}
    {refusal && <p role="alert" className="rpt-bad" style={{ fontSize: 13, margin: 0 }}>{refusal}</p>}
    {failure && <FailureNotice failure={failure} loadTitle="The Sensitivity report didn't load" onRetry={() => setReload((x) => x + 1)} />}
    {loading && !report && !failure && <p className="faint">Loading the Sensitivity report…</p>}

    {report && d && <>
      <div className="rpt-grid" aria-busy={loading}>
        <section className="v-panel flush rpt-card" aria-label="Documents by level">
          <div className="rpt-ch">Documents by level <span>all stored, today</span></div>
          <div className="rpt-bars">
            {levels.map(([cls, name, v]) =>
              <div className="rpt-barrow" key={cls}>
                <span className={`v-lvl ${cls}`}>{name}</span>
                <div className="rpt-track" role="img" aria-label={`${name}: ${n(v)} of ${n(d.total)}`}><i className={cls} style={{ width: `${share(v, d.total)}%` }} /></div>
                <span className="rpt-num">{n(v)}</span>
              </div>)}
            <div className="faint rpt-small">{n(d.recordsWithLevel)} {d.recordsWithLevel === 1 ? `${record} carries` : `${words.many.toLowerCase()} carry`} a {record} level (all their documents count at that level). On legal hold: {n(d.onLegalHold)}.</div>
          </div>
        </section>
        <section className="v-panel flush rpt-card" aria-label="Requests">
          <div className="rpt-ch">Requests <span>{hint}</span></div>
          <table className="rpt-tbl">
            <thead><tr><th>Kind</th><th className="r">Approved</th><th className="r">Declined</th><th className="r">Expired</th><th className="r">Waiting</th></tr></thead>
            <tbody>{report.requests.map((r) => <tr key={r.kind}><td>{kindWords(r.kind, record)}</td><td className="r">{n(r.approved)}</td><td className="r">{n(r.declined)}</td><td className="r">{n(r.expired)}</td><td className="r">{n(r.waiting)}</td></tr>)}</tbody>
          </table>
        </section>
      </div>
      <div className="rpt-grid">
        <section className="v-panel flush rpt-card" aria-label="Level changes">
          <div className="rpt-ch">Level changes <span>{hint}</span></div>
          <table className="rpt-tbl">
            <tbody>
              <tr><td>Raised by a steward</td><td className="r">{n(report.levelChanges.raisedBySteward)}</td></tr>
              <tr><td>Set or raised by a sending system (document or {record} level)</td><td className="r">{n(report.levelChanges.raisedBySendingSystem)}</td></tr>
              <tr><td>Lowered after approval</td><td className="r">{n(report.levelChanges.lowered)}</td></tr>
            </tbody>
          </table>
        </section>
        <section className="v-panel flush rpt-card" aria-label="Attempts to open restricted documents">
          <div className="rpt-ch">Attempts to open restricted documents <span>{hint}</span></div>
          <table className="rpt-tbl">
            <thead><tr><th>By role</th><th className="r">Attempts</th><th className="r">People</th></tr></thead>
            <tbody>
              {report.attempts.length === 0 && <tr><td colSpan={3} className="faint">No attempts {hint === 'today' ? 'today' : 'in this period'}.</td></tr>}
              {report.attempts.map((a) => <tr key={a.roles.join(',') || '-'}><td title={a.roles.length ? undefined : 'Attempts recorded before Vero wrote the role in the audit (task 0033)'}>{attemptRoleWords(a.roles)}</td><td className="r">{n(a.attempts)}</td><td className="r">{n(a.people)}</td></tr>)}
            </tbody>
          </table>
          <div className="faint rpt-small rpt-cardnote">Who and when is in the audit trail (filter by level).</div>
        </section>
      </div>
    </>}
  </>
}

/** The Period filter: this month, last month, the last 90 days, or From/To up to 366 days, in the tenant's days. */
function PeriodChip({ report, open, onOpen, onChoose }: { report: SensitivityReport; open: boolean; onOpen: (v: boolean) => void; onChoose: (from: string, to: string) => void }) {
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
  const problem = !from || !to ? 'Choose both days.' : span < 1 ? 'The last day is before the first.' : span > MAX_PERIOD_DAYS ? `At most ${MAX_PERIOD_DAYS} days.` : null
  const last = lastMonth(report.today)
  const zone = `${zoneWords(report.timeZone)} (entity)`
  return (
    <div className="rpt-daywrap" ref={box}>
      <button type="button" className="rpt-chip" aria-haspopup="dialog" aria-expanded={open} title={`Days in ${zone}: ${report.timeZone}, ${report.timeZoneSource === 'TENANT' ? 'this tenant’s own setting' : 'the default from the install configuration'}.`} onClick={() => onOpen(!open)}><b>Period</b> {periodWords(report.from, report.to, report.today)} <ChevronDown size={12} strokeWidth={2.4} aria-hidden /></button>
      {open && <div className="rpt-pop" role="dialog" aria-label="Choose the period">
        <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
          <button type="button" className="v-btn sm" onClick={() => onChoose(monthStart(report.today), report.today)}>This month</button>
          <button type="button" className="v-btn sm" onClick={() => onChoose(last.from, last.to)}>Last month</button>
          <button type="button" className="v-btn sm" onClick={() => onChoose(shiftDay(report.today, -89), report.today)}>Last 90 days</button>
        </div>
        <label className="rpt-field">From<input type="date" value={from} max={report.today} onChange={(e) => { setFrom(e.target.value); if (!to || e.target.value > to) setTo(e.target.value) }} /></label>
        <label className="rpt-field">To<input type="date" value={to} max={report.today} onChange={(e) => setTo(e.target.value)} /></label>
        <span className={problem ? 'rpt-bad' : 'faint'} style={{ fontSize: 12 }}>{problem ?? `${span} day${span === 1 ? '' : 's'}, in ${zone}. Up to ${MAX_PERIOD_DAYS}. Documents by level is always as it is today.`}</span>
        <div className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
          <button type="button" className="v-btn sm quiet" onClick={() => onOpen(false)}>Cancel</button>
          <button type="button" className="v-btn sm primary" disabled={!!problem} onClick={() => onChoose(from, to)}>Show</button>
        </div>
      </div>}
    </div>
  )
}

const LEVEL_NAMES: [SensitivityLevel, string, string][] = [['STANDARD', 's', 'Standard'], ['CONFIDENTIAL', 'c', 'Confidential'], ['HIGHLY_CONFIDENTIAL', 'hc', 'Highly Confidential']]
const levelName = (level: SensitivityLevel | null) => LEVEL_NAMES.find(([l]) => l === level)?.[2] ?? 'All levels'

/**
 * Task 0053: the Storage and retention sub-tab. Counts only: sending systems, levels, retention rules and numbers.
 * The counts are worked out in the background (they read every document) and the screen shows when; it never reads
 * the documents itself. The CSV carries a reference and its SHA-256 fingerprint, and the export is in the audit.
 */
function StorageReportView({ tabs }: { tabs: ReactNode }) {
  const [report, setReport] = useState<StorageReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [failure, setFailure] = useState<Failure | null>(null)
  const [refusal, setRefusal] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  const [exporting, setExporting] = useState(false)
  const [note, setNote] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => {
    let active = true
    setLoading(true); setFailure(null); setRefusal(null)
    storageReport().then((r) => { if (active) setReport(r) }).catch((e) => {
      if (!active) return
      const words = describeFailure('The Storage and retention report did not load', e)
      if (words.kind === 'refusal' && words.code) setRefusal(words.text)
      else setFailure({ words, kind: 'load', title: "The Storage and retention report didn't load" })
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [reload])

  const exportCsv = async () => {
    setExporting(true); setNote(null)
    try {
      const file = await storageReportCsv()
      const url = URL.createObjectURL(file.blob)
      const link = document.createElement('a')
      link.href = url; link.download = file.filename ?? `vero-storage-${new Date().toISOString().slice(0, 10)}.csv`
      document.body.appendChild(link); link.click(); link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      setNote({ ok: true, text: registeredNote(file.reference, file.fingerprint) })
    } catch (e) {
      setNote({ ok: false, text: `The export didn’t work. ${exportFailureText('A report export failed', e)}` })
    } finally { setExporting(false) }
  }

  const bySystem = new Map<string, { name: string; rows: StorageReport['stored'] }>()
  for (const s of report?.stored ?? []) {
    const e = bySystem.get(s.sourceId) ?? { name: s.sendingSystem, rows: [] }
    e.rows.push(s); bySystem.set(s.sourceId, e)
  }
  const most = Math.max(1, ...(report?.growth ?? []).map((m) => m.documents))
  const h = report?.heldBack

  return <>
    <div className="rpt-bar">
      {tabs}
      <span className="grow" />
      {report && <span className="rpt-chip" title={`Worked out in ${n(report.tookMs)} ms, every ${report.recountMinutes} minutes. Months in ${report.timeZone}.`}><b>Counts</b> {countedWords(report.countedAt)}</span>}
      <button type="button" className="v-btn" disabled={!report || loading} onClick={() => setReload((x) => x + 1)}>Refresh</button>
      <button type="button" className="v-btn" disabled={!report || loading || exporting} onClick={() => void exportCsv()}><Download size={15} aria-hidden />{exporting ? 'Exporting…' : 'Export CSV'}</button>
    </div>
    {note && <p role="status" className={note.ok ? 'faint' : 'rpt-bad'} style={{ fontSize: 13, margin: 0 }}>{note.text}</p>}
    {refusal && <p role="alert" className="rpt-bad" style={{ fontSize: 13, margin: 0 }}>{refusal}</p>}
    {failure && <FailureNotice failure={failure} loadTitle="The Storage and retention report didn't load" onRetry={() => setReload((x) => x + 1)} />}
    {loading && !report && !failure && <p className="faint">Loading the Storage and retention report…</p>}

    {report && h && <>
      <section className="v-panel flush rpt-card" aria-label="Storage now" aria-busy={loading}>
        <div className="rpt-ch">Storage now <span>by sending system and level, {countedWords(report.countedAt)}</span></div>
        <table className="rpt-tbl">
          <thead><tr><th>Sending system</th><th>Level</th><th className="r">Documents</th><th className="r">Pages</th><th className="r">Stored</th></tr></thead>
          <tbody>
            {bySystem.size === 0 && <tr><td colSpan={5} className="faint">No document is stored.</td></tr>}
            {[...bySystem.entries()].map(([id, s]) => <Fragment key={id}>{s.rows.map((r, i) =>
              <tr key={r.level}><td>{i === 0 ? s.name : ''}</td><td>{levelName(r.level)}</td><td className="r">{n(r.documents)}</td><td className="r">{n(r.pages)}</td><td className="r" title={`${n(r.bytes)} bytes`}>{bytesWords(r.bytes)}</td></tr>)}</Fragment>)}
            {report.totals.map((t, i) => <tr key={t.level ?? 'all'}><td>{i === 0 ? 'All sending systems' : ''}</td><td>{levelName(t.level)}</td><td className="r">{n(t.documents)}</td><td className="r">{n(t.pages)}</td><td className="r" title={`${n(t.bytes)} bytes`}>{bytesWords(t.bytes)}</td></tr>)}
            <tr><td><b>Total</b></td><td>All levels</td><td className="r"><b>{n(report.total.documents)}</b></td><td className="r"><b>{n(report.total.pages)}</b></td><td className="r" title={`${n(report.total.bytes)} bytes`}><b>{bytesWords(report.total.bytes)}</b></td></tr>
          </tbody>
        </table>
      </section>
      <div className="rpt-grid">
        <section className="v-panel flush rpt-card" aria-label="Growth">
          <div className="rpt-ch">Growth <span>received per month, last 12 months</span></div>
          <table className="rpt-tbl">
            <thead><tr><th>Month</th><th>Documents</th><th className="r">Pages</th><th className="r">Stored</th></tr></thead>
            <tbody>{[...report.growth].reverse().map((m) => <tr key={m.month}>
              <td>{monthWords(m.month)}</td>
              <td><div className="rpt-barrow"><div className="rpt-track" role="img" aria-label={`${monthWords(m.month)}: ${n(m.documents)} documents (${n(m.standard)} Standard, ${n(m.confidential)} Confidential, ${n(m.highlyConfidential)} Highly Confidential)`}><i className="s" style={{ width: `${share(m.documents, most)}%` }} /></div><span className="rpt-num">{n(m.documents)}</span></div></td>
              <td className="r">{n(m.pages)}</td><td className="r" title={`${n(m.bytes)} bytes`}>{bytesWords(m.bytes)}</td></tr>)}</tbody>
          </table>
          <div className="faint rpt-small rpt-cardnote">At each document&apos;s level now. A document deleted since still counts in the month it arrived.</div>
        </section>
        <div className="stack" style={{ gap: 14 }}>
          <section className="v-panel flush rpt-card" aria-label="Due for deletion">
            <div className="rpt-ch">Due for deletion <span>end of retention, from now</span></div>
            <table className="rpt-tbl">
              <thead><tr><th>Retention rule</th><th className="r">Stored</th><th className="r">30 days</th><th className="r">60 days</th><th className="r">90 days</th></tr></thead>
              <tbody>
                {report.retention.length === 0 && <tr><td colSpan={5} className="faint">No document is stored.</td></tr>}
                {report.retention.map((r) => <tr key={r.retentionDays ?? 'forever'}><td>{ruleWords(r)}</td><td className="r">{n(r.stored)}</td>
                  {r.keptForever ? <td colSpan={3} className="r faint">Never due</td> : <><td className="r">{n(r.within30 ?? 0)}</td><td className="r">{n(r.within60 ?? 0)}</td><td className="r">{n(r.within90 ?? 0)}</td></>}</tr>)}
              </tbody>
            </table>
            <div className="faint rpt-small rpt-cardnote">Each document keeps the retention it was given when it arrived. The 60 and 90 days include the earlier ones.</div>
          </section>
          <section className="v-panel flush rpt-card" aria-label="Held back from deletion">
            <div className="rpt-ch">Held back from deletion <span>past their retention, kept</span></div>
            <table className="rpt-tbl">
              <tbody>
                <tr><td>Legal hold</td><td className="r">{n(h.legalHold)}</td></tr>
                <tr><td>Quarantine</td><td className="r">{n(h.quarantine)}</td></tr>
                <tr><td>Legal hold and quarantine</td><td className="r">{n(h.both)}</td></tr>
                <tr><td><b>Held back</b></td><td className="r"><b>{n(h.total)}</b></td></tr>
              </tbody>
            </table>
            <div className="faint rpt-small rpt-cardnote">They are deleted once nothing holds them back. Which documents, and why, stays on the Legal holds and Quarantine pages.</div>
          </section>
        </div>
      </div>
    </>}
  </>
}

/** Task 0053: a registered export's note: the reference and the SHA-256 fingerprint, so the file matches the audit. */
export function registeredNote(reference: string | null, fingerprint: string | null): string {
  return ['The file is saved. The export is recorded in the audit.', reference ? `Reference ${reference}.` : '', fingerprint ? `SHA-256 fingerprint ${fingerprint}.` : ''].filter(Boolean).join(' ')
}

/**
 * Task 0054: the Activity sub-tab, from the audit trail. For a period (the last 7 days by default, the entity's days):
 * opens, prints and downloads by role and level; opens refused by role; the busiest people by sign-in name, as on the
 * Audit page; Highly Confidential approvals. Counts only: never content, file names, titles, record identifiers or
 * reasons. The CSV carries a reference and its SHA-256 fingerprint, and the export is in the audit.
 */
function ActivityReportView({ tabs }: { tabs: ReactNode }) {
  const [filter, setFilter] = useState<{ from?: string; to?: string }>({})
  const [report, setReport] = useState<ActivityReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [failure, setFailure] = useState<Failure | null>(null)
  const [refusal, setRefusal] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  const [periodOpen, setPeriodOpen] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [note, setNote] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => {
    let active = true
    setLoading(true); setFailure(null); setRefusal(null)
    activityReport(filter).then((r) => { if (active) setReport(r) }).catch((e) => {
      if (!active) return
      const words = describeFailure('The Activity report did not load', e)
      if (words.kind === 'refusal' && words.code) setRefusal(words.text)
      else setFailure({ words, kind: 'load', title: "The Activity report didn't load" })
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [filter, reload])

  const exportCsv = async () => {
    if (!report) return
    setExporting(true); setNote(null)
    try {
      const file = await activityReportCsv({ from: report.from, to: report.to })
      const url = URL.createObjectURL(file.blob)
      const link = document.createElement('a')
      link.href = url; link.download = file.filename ?? `vero-activity-${report.from}-to-${report.to}.csv`
      document.body.appendChild(link); link.click(); link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      setNote({ ok: true, text: registeredNote(file.reference, file.fingerprint) })
    } catch (e) {
      setNote({ ok: false, text: `The export didn’t work. ${exportFailureText('A report export failed', e)}` })
    } finally { setExporting(false) }
  }

  const hint = report ? activityPeriodWords(report.from, report.to, report.today) : ''
  const a = report?.approvals

  return <>
    <div className="rpt-bar">
      {tabs}
      <span className="grow" />
      {report && <ActivityPeriodChip report={report} open={periodOpen} onOpen={setPeriodOpen} onChoose={(from, to) => { setPeriodOpen(false); setFilter({ from, to }) }} />}
      <button type="button" className="v-btn" disabled={!report || loading || exporting} onClick={() => void exportCsv()}><Download size={15} aria-hidden />{exporting ? 'Exporting…' : 'Export CSV'}</button>
    </div>
    {note && <p role="status" className={note.ok ? 'faint' : 'rpt-bad'} style={{ fontSize: 13, margin: 0 }}>{note.text}</p>}
    {refusal && <p role="alert" className="rpt-bad" style={{ fontSize: 13, margin: 0 }}>{refusal}</p>}
    {failure && <FailureNotice failure={failure} loadTitle="The Activity report didn't load" onRetry={() => setReload((x) => x + 1)} />}
    {loading && !report && !failure && <p className="faint">Loading the Activity report…</p>}

    {report && a && <>
      <section className="v-panel flush rpt-card" aria-label="Opened, printed and downloaded" aria-busy={loading}>
        <div className="rpt-ch">Opened, printed and downloaded <span>{hint}, by role and level</span></div>
        <table className="rpt-tbl">
          <thead><tr><th>What</th><th>Role</th><th className="r">Standard</th><th className="r">Confidential</th><th className="r">Highly Confidential</th><th className="r">All</th></tr></thead>
          <tbody>
            {report.accessTotals.every((t) => t.total === 0) && <tr><td colSpan={6} className="faint">Nothing was opened, printed or downloaded {hint === 'the last 7 days' ? 'in the last 7 days' : 'in this period'}.</td></tr>}
            {report.accessTotals.filter((t) => t.total > 0).map((t) => <Fragment key={t.kind}>
              {report.access.filter((r) => r.kind === t.kind).map((r, i) => <tr key={t.kind + r.roles.join(',')}>
                <td>{i === 0 ? accessWords(t.kind) : ''}</td><td title={r.roles.length ? undefined : 'Recorded before Vero wrote the role in the audit (task 0054)'}>{attemptRoleWords(r.roles)}</td>
                <td className="r">{n(r.standard)}</td><td className="r">{n(r.confidential)}</td><td className="r">{n(r.highlyConfidential)}</td><td className="r">{n(r.total)}</td></tr>)}
              <tr><td></td><td><b>Every role</b></td><td className="r"><b>{n(t.standard)}</b></td><td className="r"><b>{n(t.confidential)}</b></td><td className="r"><b>{n(t.highlyConfidential)}</b></td><td className="r"><b>{n(t.total)}</b></td></tr>
            </Fragment>)}
          </tbody>
        </table>
      </section>
      <div className="rpt-grid">
        <section className="v-panel flush rpt-card" aria-label="Opens refused">
          <div className="rpt-ch">Opens refused <span>{hint}, by role</span></div>
          <table className="rpt-tbl">
            <thead><tr><th>Role</th><th className="r">For the level</th><th className="r">For a quarantine</th></tr></thead>
            <tbody>
              {report.refusals.length === 0 && <tr><td colSpan={3} className="faint">No open was refused.</td></tr>}
              {report.refusals.map((r) => <tr key={r.roles.join(',') || '-'}><td>{attemptRoleWords(r.roles)}</td><td className="r">{n(r.level)}</td><td className="r">{n(r.quarantine)}</td></tr>)}
            </tbody>
          </table>
          <div className="faint rpt-small rpt-cardnote">A legal hold never stops a document being opened, so it has no column.</div>
        </section>
        <section className="v-panel flush rpt-card" aria-label="Highly Confidential approvals">
          <div className="rpt-ch">Highly Confidential approvals <span>{hint}</span></div>
          <table className="rpt-tbl">
            <tbody>
              <tr><td>Print and download requests made</td><td className="r">{n(a.requested)}</td></tr>
              <tr><td>Approved</td><td className="r">{n(a.approved)}</td></tr>
              <tr><td>Declined</td><td className="r">{n(a.declined)}</td></tr>
              <tr><td>Expired undecided</td><td className="r">{n(a.expired)}</td></tr>
              <tr><td>One-time grants used</td><td className="r">{n(a.grantsUsed)}</td></tr>
              <tr><td>One-time grants given back</td><td className="r">{n(a.grantsReturned)}</td></tr>
            </tbody>
          </table>
        </section>
      </div>
      <section className="v-panel flush rpt-card" aria-label="Busiest people">
        <div className="rpt-ch">Busiest people <span>the {ACTIVITY_BUSIEST} with the most opens, {hint}</span></div>
        <table className="rpt-tbl">
          <thead><tr><th>Person</th><th>Role</th><th className="r">Standard</th><th className="r">Confidential</th><th className="r">Highly Confidential</th><th className="r">Opens</th></tr></thead>
          <tbody>
            {report.busiest.length === 0 && <tr><td colSpan={6} className="faint">Nobody opened a document.</td></tr>}
            {report.busiest.map((p, i) => <tr key={p.name + i}><td>{p.name}</td><td>{attemptRoleWords(p.roles)}</td><td className="r">{n(p.standard)}</td><td className="r">{n(p.confidential)}</td><td className="r">{n(p.highlyConfidential)}</td><td className="r"><b>{n(p.total)}</b></td></tr>)}
          </tbody>
        </table>
        <div className="faint rpt-small rpt-cardnote">People by sign-in name, as on the Audit page. What they opened is in the audit trail.</div>
      </section>
    </>}
  </>
}

/** The Activity report's period: the last 7 days, the last 30, or From/To up to 92 days, in the entity's days. */
function ActivityPeriodChip({ report, open, onOpen, onChoose }: { report: ActivityReport; open: boolean; onOpen: (v: boolean) => void; onChoose: (from: string, to: string) => void }) {
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
  const problem = !from || !to ? 'Choose both days.' : span < 1 ? 'The last day is before the first.' : span > ACTIVITY_MAX_DAYS ? `At most ${ACTIVITY_MAX_DAYS} days.` : null
  const zone = `${zoneWords(report.timeZone)} (entity)`
  return (
    <div className="rpt-daywrap" ref={box}>
      <button type="button" className="rpt-chip" aria-haspopup="dialog" aria-expanded={open} title={`Days in ${zone}: ${report.timeZone}.`} onClick={() => onOpen(!open)}><b>Period</b> {activityPeriodWords(report.from, report.to, report.today)} <ChevronDown size={12} strokeWidth={2.4} aria-hidden /></button>
      {open && <div className="rpt-pop" role="dialog" aria-label="Choose the period">
        <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
          <button type="button" className="v-btn sm" onClick={() => onChoose(shiftDay(report.today, -6), report.today)}>Last 7 days</button>
          <button type="button" className="v-btn sm" onClick={() => onChoose(shiftDay(report.today, -29), report.today)}>Last 30 days</button>
          <button type="button" className="v-btn sm" onClick={() => onChoose(monthStart(report.today), report.today)}>This month</button>
        </div>
        <label className="rpt-field">From<input type="date" value={from} max={report.today} onChange={(e) => { setFrom(e.target.value); if (!to || e.target.value > to) setTo(e.target.value) }} /></label>
        <label className="rpt-field">To<input type="date" value={to} max={report.today} onChange={(e) => setTo(e.target.value)} /></label>
        <span className={problem ? 'rpt-bad' : 'faint'} style={{ fontSize: 12 }}>{problem ?? `${span} day${span === 1 ? '' : 's'}, in ${zone}. Up to ${ACTIVITY_MAX_DAYS}.`}</span>
        <div className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
          <button type="button" className="v-btn sm quiet" onClick={() => onOpen(false)}>Cancel</button>
          <button type="button" className="v-btn sm primary" disabled={!!problem} onClick={() => onChoose(from, to)}>Show</button>
        </div>
      </div>}
    </div>
  )
}

const ACTIVITY_MAX_DAYS = 92
const ACTIVITY_BUSIEST = 20
const accessWords = (kind: 'OPENED' | 'PRINTED' | 'DOWNLOADED') => (kind === 'OPENED' ? 'Opened' : kind === 'PRINTED' ? 'Printed' : 'Downloaded')

/** "the last 7 days", "the last 30 days", or the period's own words (as the Sensitivity report). */
export function activityPeriodWords(from: string, to: string, today: string): string {
  if (to === today && from === shiftDay(today, -6)) return 'the last 7 days'
  if (to === today && from === shiftDay(today, -29)) return 'the last 30 days'
  return periodWords(from, to, today)
}
