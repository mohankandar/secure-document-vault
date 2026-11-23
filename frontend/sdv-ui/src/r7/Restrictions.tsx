import { useEffect, useState } from 'react'
import { ArrowRight } from 'lucide-react'
import { useStore } from './store'
import { FailureNotice, loadFailed, type Failure } from './CardError'
import { Callout, fmtDate, fmtDateTime } from './ui'
import { LevelBadge } from './Sensitivity'
import { restrictions, restrictionChanges, restrictedRecords, type RestrictionChange, type RestrictedRecord, type RestrictionsOverview } from './liveApi'
import { changeScopeWords, levelWords, oldestWords, recordWords } from './restrictionWords'

/**
 * Task 0055: the Business admin's read-only Restrictions page: everything restricted in one place, in the Legal holds
 * and Approvals look. Each part links to the page that acts on it, where a Business admin may go (quarantine is the
 * Stewards', so it has no link). Records by their primary identifier only: never a document ID, title, file name,
 * content or reason.
 */
const n = (v: number) => v.toLocaleString('en-GB')

export function Restrictions() {
  const { can, navigate } = useStore()
  const [overview, setOverview] = useState<RestrictionsOverview | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [records, setRecords] = useState<{ items: RestrictedRecord[]; next: string | null; pages: string[] } | null>(null)
  const [changes, setChanges] = useState<{ items: RestrictionChange[]; next: string | null; pages: string[] } | null>(null)
  const allowed = can('VIEW_RESTRICTIONS')

  useEffect(() => {
    if (!allowed) return
    let active = true
    restrictions().then((o) => {
      if (!active) return
      setOverview(o); setError(null)
      setRecords({ ...o.records, pages: [] }); setChanges({ ...o.changes, pages: [] })
    }).catch((e) => { if (active) setError(loadFailed('The restrictions did not load', e)) })
    return () => { active = false }
  }, [allowed, reload])

  const pageRecords = async (after: string | null, pages: string[]) => {
    try { const p = after ? await restrictedRecords(after) : overview!.records; setRecords({ ...p, pages }) } catch (e) { setError(loadFailed('The restricted records did not load', e)) }
  }
  const pageChanges = async (after: string | null, pages: string[]) => {
    try { const p = after ? await restrictionChanges(after) : overview!.changes; setChanges({ ...p, pages }) } catch (e) { setError(loadFailed('The level changes did not load', e)) }
  }

  if (!allowed) return <Callout tone="warn" title="Only a Business admin sees the restrictions" />
  if (error && !overview) return <FailureNotice failure={error} loadTitle="The restrictions didn't load" onRetry={() => setReload((x) => x + 1)} />
  if (!overview || !records || !changes) return <Callout title="Loading">Reading the restrictions…</Callout>
  const o = overview
  const link = (label: string, tab: string) => <button type="button" className="v-btn sm" onClick={() => navigate('governance', tab)}>{label}<ArrowRight size={14} aria-hidden /></button>

  return <>
    <div className="rpt-grid">
      <section className="v-panel flush rpt-card" aria-label="Restricted documents">
        <header className="hold-head"><h2>Restricted documents · {n(o.confidential + o.highlyConfidential)}</h2><span className="faint" style={{ fontSize: 13 }}>By sending system, counted {fmtDateTime(o.levelsCountedAt)}</span></header>
        <table className="rpt-tbl">
          <thead><tr><th>Sending system</th><th className="r">Confidential</th><th className="r">Highly Confidential</th></tr></thead>
          <tbody>
            {o.levels.length === 0 && <tr><td colSpan={3} className="faint">No document is above Standard.</td></tr>}
            {o.levels.map((l) => <tr key={l.sendingSystem}><td>{l.sendingSystem}</td><td className="r">{n(l.confidential)}</td><td className="r">{n(l.highlyConfidential)}</td></tr>)}
            {o.levels.length > 0 && <tr><td><b>Every sending system</b></td><td className="r"><b>{n(o.confidential)}</b></td><td className="r"><b>{n(o.highlyConfidential)}</b></td></tr>}
          </tbody>
        </table>
      </section>
      <div className="stack" style={{ gap: 14 }}>
        <section className="v-panel flush" aria-label="Lowering requests waiting">
          <header className="hold-head"><h2>Lowering requests waiting · {n(o.lowerings.waiting)}</h2>{link('Approvals', 'approvals')}</header>
          <p className="muted" style={{ padding: '0 16px 14px' }}>{o.lowerings.waiting === 0 ? 'Nothing is waiting.' : oldestWords(o.lowerings.oldest)}</p>
        </section>
        <section className="v-panel flush" aria-label="Legal holds">
          <header className="hold-head"><h2>Legal holds · {n(o.legalHolds.holds)}</h2>{link('Legal holds', 'holds')}</header>
          <p className="muted" style={{ padding: '0 16px 14px' }}>{o.legalHolds.documents === 0 ? 'No document is held.' : `${n(o.legalHolds.documents)} document${o.legalHolds.documents === 1 ? '' : 's'} held: never deleted by the daily cleanup.`}</p>
        </section>
        <section className="v-panel flush" aria-label="Quarantine">
          <header className="hold-head"><h2>Open quarantine incidents · {n(o.quarantine.incidents)}</h2></header>
          <p className="muted" style={{ padding: '0 16px 14px' }}>{o.quarantine.incidents === 0 ? 'No document is quarantined.'
            : `${n(o.quarantine.documents)} document${o.quarantine.documents === 1 ? '' : 's'} quarantined${o.quarantine.preparing ? `; ${n(o.quarantine.preparing)} incident${o.quarantine.preparing === 1 ? '' : 's'} still being prepared` : ''}. The Stewards review them under Quarantine.`}</p>
        </section>
      </div>
    </div>

    <section className="v-panel flush" aria-label="Records with a record level">
      <header className="hold-head"><h2>Records with a record level · {n(o.restrictedRecords)}</h2><span className="faint" style={{ fontSize: 13 }}>Every document of the record counts at this level · newest first</span></header>
      {records.items.length === 0 ? <p className="muted" style={{ padding: 16 }}>No record has a record level above Standard.</p>
        : <table className="v-table" aria-label="Restricted records"><thead><tr><th>Record</th><th>Level</th><th>Set</th></tr></thead>
          <tbody>{records.items.map((r, i) => <tr key={i + (r.identifier ?? '')}>
            <td className="lead mono" data-label={r.identifierLabel ?? 'Record'}>{recordWords(r)}</td>
            <td data-label="Level"><LevelBadge level={r.level} /></td>
            <td data-label="Set" className="faint">{fmtDate(r.setAt)}</td></tr>)}</tbody></table>}
      <Pager next={records.next} pages={records.pages} onPage={(after, pages) => void pageRecords(after, pages)} />
    </section>

    <section className="v-panel flush" aria-label="Level changes, last 30 days">
      <header className="hold-head"><h2>Level changes, last 30 days · {n(o.levelChanges)}</h2><span className="faint" style={{ fontSize: 13 }}>Newest first · the reasons stay in the audit</span></header>
      {changes.items.length === 0 ? <p className="muted" style={{ padding: 16 }}>No level was changed in the last 30 days.</p>
        : <table className="v-table" aria-label="Level changes"><thead><tr><th>When</th><th>Who</th><th>What</th><th>Record</th><th>From</th><th>To</th></tr></thead>
          <tbody>{changes.items.map((c, i) => <tr key={i + c.changedAt}>
            <td data-label="When" className="faint">{fmtDateTime(c.changedAt)}</td>
            <td data-label="Who">{c.changedBy}</td>
            <td data-label="What">{changeScopeWords(c.scope)}</td>
            <td data-label="Record" className="mono">{recordWords(c)}</td>
            <td data-label="From">{levelWords(c.fromLevel)}</td>
            <td data-label="To">{levelWords(c.toLevel)}</td></tr>)}</tbody></table>}
      <Pager next={changes.next} pages={changes.pages} onPage={(after, pages) => void pageChanges(after, pages)} />
    </section>
  </>
}

/** Next and First page; pages holds the positions already passed (so First page goes back to the start). */
function Pager({ next, pages, onPage }: { next: string | null; pages: string[]; onPage: (after: string | null, pages: string[]) => void }) {
  if (!next && pages.length === 0) return null
  return <div className="row wrap" style={{ gap: 8, justifyContent: 'flex-end', padding: '10px 16px' }}>
    {pages.length > 0 && <button type="button" className="v-btn sm" onClick={() => onPage(null, [])}>First page</button>}
    {next && <button type="button" className="v-btn sm" onClick={() => onPage(next, [...pages, next])}>Next 50</button>}
  </div>
}
