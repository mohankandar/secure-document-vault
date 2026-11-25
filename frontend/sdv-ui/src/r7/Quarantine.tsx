import { useEffect, useMemo, useState } from 'react'
import { AlertTriangle, ArrowLeft, Check, ChevronDown, ChevronRight, Download, Plus } from 'lucide-react'
import { useStore } from './store'
import { FailureNotice, exportFailureText, loadFailed, type Failure } from './CardError'
import { Badge, Banner, Callout, Dialog, Drawer, PageHead, fmtDate, fmtDateTime } from './ui'
import { FIELD_VALUE_MAX, wholeHint } from './searchRouting'
import { LevelBadge, refusalText, whenWords } from './Sensitivity'
import { Viewer } from './Viewer'
import type { RecordItem, Source } from './model'
import {
  countQuarantineFilter, quarantineByFilter, quarantineDocument, quarantineExposure, quarantineExposureCsv, quarantineIncident,
  quarantineIncidents, releaseQuarantinedDocument, requestIncidentRelease, schema, toSource,
  type ExposureReport, type QuarantineCount, type QuarantineFilter, type QuarantineIncident, type QuarantineIncidentSummary, type QuarantinedDocument,
} from './liveApi'
import { NAME_MAX, REASON_MAX, countWords, exportNote, filterProblem, inQuarantineCount, levelsLine, nameReady, oldestFirst, pageNote, plural, progressWords, reasonReady, releaseWords, scopeWords } from './quarantineWords'

/**
 * Task 0049: the Stewards' quarantine screens (the server side is task 0048). No canvas board yet: built from the
 * Legal holds and Approvals screens (same panels, tables, dialogs and collapsible groups).
 * A Document or Restricted steward reviews the incidents they can open: the server leaves out documents at levels
 * they can't open and only counts them. Only a Restricted steward starts an incident by filter.
 * Every refusal is shown in the catalog's words with its code (task 0044).
 */

const pagesWords = (n: number | null) => (n == null ? '—' : plural(n, 'page'))

export function Quarantine() {
  const { can } = useStore()
  const [incidents, setIncidents] = useState<QuarantineIncidentSummary[] | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [openId, setOpenId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [releasedOpen, setReleasedOpen] = useState(false)
  const allowed = can('QUARANTINE_DOCUMENTS')

  useEffect(() => {
    if (!allowed) return
    let active = true
    quarantineIncidents().then((l) => { if (active) { setIncidents(l); setError(null) } }).catch((e) => { if (active) setError(loadFailed('Quarantine incidents did not load', e)) })
    return () => { active = false }
  }, [allowed, reload])

  const open = incidents?.filter((i) => i.status === 'OPEN') ?? []
  const released = incidents?.filter((i) => i.status !== 'OPEN') ?? []
  const byFilter = can('QUARANTINE_BY_FILTER')

  return (
    <div className="v-page">
      <PageHead crumbs={['Quarantine']} title="Quarantine"
        lede="Documents under review are temporarily unavailable to everyone else. Nothing is deleted while a document is quarantined."
        actions={allowed && byFilter && !openId && !creating ? <button type="button" className="v-btn primary" onClick={() => setCreating(true)}><Plus size={16} aria-hidden />New incident</button> : undefined} />
      <div className="v-body">
        {!allowed ? <Callout tone="warn" title="Only a Document steward or a Restricted steward reviews quarantined documents" />
          : openId ? <IncidentDetail incidentId={openId} onBack={() => { setOpenId(null); setReload((x) => x + 1) }} />
          : <>
            {creating && <NewIncident onCancel={() => setCreating(false)} onCreated={(id) => { setCreating(false); setOpenId(id) }} />}
            {error && !incidents ? <FailureNotice failure={error} loadTitle="Quarantine incidents didn't load" onRetry={() => setReload((x) => x + 1)} />
              : <>
                <section className="v-panel flush" aria-label="Open incidents">
                  <header className="hold-head"><h2>Open incidents · {incidents ? open.length : '…'}</h2><span className="faint" style={{ fontSize: 13 }}>{byFilter ? 'Start one with New incident, or from a document' : 'Start one from a document, in its record or the viewer'}</span></header>
                  {incidents && open.length === 0 ? <p className="muted" style={{ padding: 16 }}>No open incident you can review.</p> : incidents && <IncidentTable rows={open} label="Open incidents" onOpen={setOpenId} />}
                </section>
                {incidents && released.length > 0 && <section className="v-panel flush" aria-label="Released incidents">
                  <header className="hold-head">
                    <button type="button" className="q-toggle" aria-expanded={releasedOpen} onClick={() => setReleasedOpen((o) => !o)}>
                      {releasedOpen ? <ChevronDown size={14} strokeWidth={2.4} aria-hidden /> : <ChevronRight size={14} strokeWidth={2.4} aria-hidden />}
                      <h2>Released incidents · {released.length}</h2></button>
                    <span className="faint" style={{ fontSize: 13 }}>Kept as history, with who released each document</span>
                  </header>
                  {releasedOpen && <IncidentTable rows={released} label="Released incidents" onOpen={setOpenId} />}
                </section>}
              </>}
          </>}
      </div>
    </div>
  )
}

function IncidentTable({ rows, label, onOpen }: { rows: QuarantineIncidentSummary[]; label: string; onOpen: (id: string) => void }) {
  return (
    <table className="v-table" aria-label={label}><thead><tr><th>Incident</th><th>How</th><th>In quarantine</th><th>Started by</th><th>Started</th><th><span className="sr-only">Open</span></th></tr></thead>
      <tbody>{rows.map((i) => <tr key={i.incidentId}>
        <td className="lead" data-label="Incident"><strong style={{ fontWeight: 500 }}>{i.name}</strong>
          {i.releaseRequested && <> <Badge tone="warn">Release requested</Badge></>}
          {i.preparing && <> <Badge tone="outline">Being prepared</Badge></>}
          {i.releasing && <> <Badge tone="outline">Being released</Badge></>}
          {i.status !== 'OPEN' && <> <Badge>Released{i.closedAt ? ` ${fmtDate(i.closedAt)}` : ''}</Badge></>}</td>
        <td data-label="How">{scopeWords(i.scope)}</td>
        <td data-label="In quarantine">{countWords(inQuarantineCount(i))} document{inQuarantineCount(i) === 1 ? '' : 's'}
          {i.preparing && i.expectedCount != null && <span className="faint"> · {progressWords(i.preparedCount, i.expectedCount)}</span>}
          {i.releasing && <span className="faint"> · {releaseWords(i.releasedCount, i.documentCount)}</span>}</td>
        <td data-label="Started by">{i.createdByName?.trim() || 'A Steward'}</td>
        <td data-label="Started" className="faint">{fmtDate(i.createdAt)}</td>
        <td className="act"><button type="button" className="v-btn sm" onClick={() => onOpen(i.incidentId)} aria-label={`Open incident ${i.name}`}>Open</button></td>
      </tr>)}</tbody></table>
  )
}

/** A minimal record for the viewer: the document's number and what the review list knows; never a title or file name. */
function reviewRecord(d: QuarantinedDocument): RecordItem {
  const number = d.primaryIdentifier ?? '—'
  return {
    ref: 'quarantine:' + d.documentId, sourceId: '', identifiers: [{ label: d.primaryIdentifierLabel ?? 'Number', value: number }], metadata: [],
    title: number, updatedAt: d.quarantinedAt, incomplete: null,
    docs: [{ id: d.documentId, filename: 'Document', kind: 'Document', pages: d.pageCount ?? 0, ingestedAt: d.receivedAt ?? d.quarantinedAt, expiresAt: '', submissionId: '', level: d.level ?? undefined, quarantined: d.inForce }],
  }
}

function IncidentDetail({ incidentId, onBack }: { incidentId: string; onBack: () => void }) {
  const { can, toast } = useStore()
  const [incident, setIncident] = useState<QuarantineIncident | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [releasing, setReleasing] = useState<QuarantinedDocument | null>(null)
  const [asking, setAsking] = useState(false)
  const [reason, setReason] = useState('')
  const [exposureOpen, setExposureOpen] = useState(false)
  const [viewing, setViewing] = useState<QuarantinedDocument | null>(null)
  // Task 0052: the list comes a page at a time. after: where this page starts (null: the first); first: its number.
  const [page, setPage] = useState<{ after: string | null; first: number }>({ after: null, first: 1 })

  useEffect(() => {
    let active = true
    quarantineIncident(incidentId, page.after).then((i) => { if (active) { setIncident(i); setError(null) } }).catch((e) => { if (active) setError(loadFailed('The quarantine incident did not load', e)) })
    return () => { active = false }
  }, [incidentId, reload, page])
  // Task 0051: while the incident is prepared in the background (task 0052: or released), read it again every few
  // seconds to show the progress.
  const working = !!incident?.preparing || !!incident?.releasing
  useEffect(() => {
    if (!working) return
    const t = window.setInterval(() => setReload((x) => x + 1), 3000)
    return () => window.clearInterval(t)
  }, [working])

  const rows = useMemo(() => oldestFirst(incident?.documents ?? []), [incident])
  const back = <button type="button" className="v-btn quiet sm" onClick={onBack}><ArrowLeft size={16} aria-hidden />All incidents</button>
  if (error && !incident) return <>{back}<FailureNotice failure={error} loadTitle="The incident didn't load" onRetry={() => setReload((x) => x + 1)} /></>
  if (!incident) return <>{back}<Callout title="Loading">Reading the incident…</Callout></>

  const isOpen = incident.status === 'OPEN'
  // Task 0051: a whole release waits until the incident is prepared (the server refuses it before, SDV-QUA-010).
  const mayAsk = isOpen && !incident.preparing && !incident.releasing && !incident.pendingRequestId && incident.documentsInForce > 0 && incident.hiddenCount === 0
  const inQuarantine = inQuarantineCount(incident)
  const reviewable = incident.documentCount - incident.hiddenCount
  const where = pageNote(page.first, rows.length, reviewable)
  const next = incident.next
  const paging = (page.after || next) ? <div className="row wrap" style={{ gap: 8, justifyContent: 'flex-end', padding: '10px 16px' }}>
    {page.after && <button type="button" className="v-btn sm" onClick={() => setPage({ after: null, first: 1 })}>First page</button>}
    {next && <button type="button" className="v-btn sm" onClick={() => setPage({ after: next, first: page.first + rows.length })}>Next {incident.listLimit}</button>}
  </div> : null

  const release = async (d: QuarantinedDocument) => {
    if (!reasonReady(reason) || busy) return
    setBusy(true); setProblem(null)
    try {
      await releaseQuarantinedDocument(d.documentId, reason.trim())
      toast({ tone: 'ok', title: 'Released', detail: `${d.primaryIdentifier ?? 'The document'} is available again. The release is in the audit.` })
      setReleasing(null); setReason('')
    } catch (e) { setProblem(refusalText(e)); setReleasing(null) }
    finally { setBusy(false); setReload((x) => x + 1) }
  }
  const askRelease = async () => {
    if (!reasonReady(reason) || busy) return
    setBusy(true); setProblem(null)
    try {
      await requestIncidentRelease(incident.incidentId, reason.trim())
      toast({ tone: 'ok', title: 'Release requested', detail: 'A Business admin decides it under Approvals. Until then the documents stay quarantined.' })
      setAsking(false); setReason('')
    } catch (e) { setProblem(refusalText(e)); setAsking(false) }
    finally { setBusy(false); setReload((x) => x + 1) }
  }

  return <>
    <div className="row wrap" style={{ gap: 10 }}>{back}</div>
    <section className="v-panel q-incident" aria-label={`Incident ${incident.name}`}>
      <div className="v-panel-body stack" style={{ gap: 10 }}>
        <div className="row wrap" style={{ gap: 10, alignItems: 'center' }}>
          <h2 className="grow" style={{ fontSize: 18, fontWeight: 600 }}>{incident.name}</h2>
          {isOpen ? <Badge tone="warn">Open</Badge> : <Badge>Released</Badge>}
          {incident.preparing && <Badge tone="outline">Being prepared</Badge>}
          <button type="button" className="v-btn sm" onClick={() => setExposureOpen(true)}>Exposure report</button>
          {mayAsk && <button type="button" className="v-btn sm primary" disabled={busy} onClick={() => { setReason(''); setAsking(true) }}>Request release of whole incident</button>}
        </div>
        <dl className="v-kv">
          <div><dt>Reason</dt><dd>{incident.reason}</dd></div>
          <div><dt>How</dt><dd>{scopeWords(incident.scope)}</dd></div>
          <div><dt>Started</dt><dd>{incident.createdByName?.trim() || 'A Steward'}, {whenWords(incident.createdAt)}</dd></div>
          <div><dt>In quarantine</dt><dd>{incident.preparing ? `${countWords(inQuarantine)} document${inQuarantine === 1 ? '' : 's'}`
            : `${countWords(incident.documentsInForce)} document${incident.documentsInForce === 1 ? '' : 's'} of ${countWords(incident.documentCount)}`}</dd></div>
          {incident.preparing && incident.expectedCount != null && <div><dt>Prepared</dt><dd>{progressWords(incident.preparedCount, incident.expectedCount)}</dd></div>}
          {incident.releasing && <div><dt>Released</dt><dd>{releaseWords(incident.releasedCount, incident.documentCount)}</dd></div>}
          {incident.closedAt && <div><dt>Released</dt><dd>{whenWords(incident.closedAt)}</dd></div>}
        </dl>
      </div>
    </section>
    {incident.preparing && incident.expectedCount != null && <Banner tone="info" lead="Being prepared:">
      {progressWords(incident.preparedCount, incident.expectedCount)}. Every matching document is quarantined already; the list below fills as Vero prepares it. You can review and release the documents listed. The whole incident can be released once it is ready.</Banner>}
    {incident.releasing && <Banner tone="info" lead="Being released:">
      {releaseWords(incident.releasedCount, incident.documentCount)}. A Business admin approved the release of the whole incident; Vero releases the documents in batches. Each document is available again once it is released. The incident ends when every one is released.</Banner>}
    {incident.pendingRequestId && <Banner tone="info" lead="Release requested:">{incident.pendingRequestedByName?.trim() || 'A Steward'} asked to release the whole incident. A Business admin decides it under Approvals{incident.pendingExpiresAt ? `; it expires ${whenWords(incident.pendingExpiresAt)} if nobody does` : ''}.</Banner>}
    {incident.hiddenCount > 0 && <Banner tone="info" lead={`${plural(incident.hiddenCount, 'document')} not shown:`}>{incident.hiddenCount === 1 ? 'it is' : 'they are'} at a level you can&apos;t open. A Restricted steward reviews {incident.hiddenCount === 1 ? 'it' : 'them'}, and only they can ask to release the whole incident.</Banner>}
    {problem && <Callout tone="warn" title="Not done">{problem}</Callout>}

    <section className="v-panel flush" aria-label="Documents to review">
      <header className="hold-head"><h2>Documents to review · {countWords(Math.max(rows.length, reviewable))}</h2><span className="faint" style={{ fontSize: 13 }}>{where ? `${where} ` : ''}Oldest first · you can read and correct them; print and download wait until release</span></header>
      {rows.length === 0 ? <p className="muted" style={{ padding: 16 }}>No document here that you can open.</p>
        : <table className="v-table" aria-label="Quarantined documents"><thead><tr><th>Number</th><th>Level</th><th>Received</th><th>Pages</th><th>Quarantined</th><th>Status</th><th><span className="sr-only">Actions</span></th></tr></thead>
          <tbody>{rows.map((d) => <tr key={d.quarantineId}>
            <td className="lead mono" data-label={d.primaryIdentifierLabel ?? 'Number'}>{d.primaryIdentifier ?? '—'}</td>
            <td data-label="Level">{d.level ? <LevelBadge level={d.level} /> : '—'}</td>
            <td data-label="Received">{fmtDate(d.receivedAt)}</td>
            <td data-label="Pages">{pagesWords(d.pageCount)}</td>
            <td data-label="Quarantined" className="faint">{d.quarantinedByName?.trim() || 'A Steward'}, {fmtDate(d.quarantinedAt)}</td>
            <td data-label="Status">{d.inForce ? <Badge tone="warn">Quarantined</Badge> : <span className="faint">Released{d.releasedByName ? ` by ${d.releasedByName}` : ''}{d.releasedAt ? `, ${fmtDate(d.releasedAt)}` : ''}</span>}</td>
            <td className="act"><span className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
              {can('VIEW_PDF') && <button type="button" className="v-btn sm" onClick={() => setViewing(d)} aria-label={`Open ${d.primaryIdentifier ?? 'document'}`}>Open</button>}
              {d.inForce && <button type="button" className="v-btn sm" disabled={busy} onClick={() => { setReason(''); setReleasing(d) }} aria-label={`Release ${d.primaryIdentifier ?? 'document'}`}>Release</button>}
            </span></td>
          </tr>)}</tbody></table>}
      {paging}
    </section>

    {incident.singleReleases && incident.singleReleases.length > 0 && <section className="v-panel flush" aria-label="Released one by one">
      <header className="hold-head"><h2>Released one by one · {incident.singleReleases.length}</h2><span className="faint" style={{ fontSize: 13 }}>Shown to you because you started this incident</span></header>
      <table className="v-table" aria-label="Documents released one by one"><thead><tr><th>Number</th><th>Released by</th><th>When</th></tr></thead>
        <tbody>{incident.singleReleases.map((s, i) => <tr key={i}><td className="lead mono" data-label="Number">{s.primaryIdentifier ?? 'Not shown'}</td><td data-label="Released by">{s.releasedByName?.trim() || 'A Steward'}</td><td data-label="When" className="faint">{whenWords(s.releasedAt)}</td></tr>)}</tbody></table>
    </section>}

    {releasing && <Dialog title="Release this document?" onClose={() => { if (!busy) setReleasing(null) }} footer={<>
      <button type="button" className="v-btn" disabled={busy} onClick={() => setReleasing(null)}>Cancel</button>
      <button type="button" className="v-btn primary" disabled={busy || !reasonReady(reason)} onClick={() => void release(releasing)}>{busy ? 'Releasing…' : 'Release document'}</button>
    </>}>
      <p><span className="mono">{releasing.primaryIdentifier ?? '—'}</span>, {pagesWords(releasing.pageCount)}, received {fmtDate(releasing.receivedAt)}.</p>
      <label className="v-field"><span>Why it can be released</span>
        <textarea className="v-input" rows={3} value={reason} maxLength={REASON_MAX} onChange={(e) => setReason(e.target.value)} placeholder="For example: Reviewed, nothing exposed" /></label>
      <p className="muted">Once released, everyone who could open it before can open, print and download it again. The release is recorded in the audit.</p>
    </Dialog>}
    {asking && <Dialog title="Request release of the whole incident" onClose={() => { if (!busy) setAsking(false) }} footer={<>
      <button type="button" className="v-btn" disabled={busy} onClick={() => setAsking(false)}>Cancel</button>
      <button type="button" className="v-btn primary" disabled={busy || !reasonReady(reason)} onClick={() => void askRelease()}>{busy ? 'Sending…' : 'Send request'}</button>
    </>}>
      <p>{countWords(incident.documentsInForce)} document{incident.documentsInForce === 1 ? '' : 's'} in “{incident.name}” would be released at once, after a Business admin approves.</p>
      <label className="v-field"><span>Why it can be released</span>
        <textarea className="v-input" rows={3} value={reason} maxLength={REASON_MAX} onChange={(e) => setReason(e.target.value)} placeholder="For example: Review finished, nothing exposed" /></label>
      <p className="muted">The Business admin sees the incident&apos;s name, counts, levels and dates, not your reason. Someone else must decide it.</p>
    </Dialog>}
    {exposureOpen && <ExposureDrawer incidentId={incident.incidentId} onClose={() => setExposureOpen(false)} />}
    {viewing && <Viewer record={reviewRecord(viewing)} results={[reviewRecord(viewing)]} docId={viewing.documentId} backLabel="Incident" recordTitle={viewing.primaryIdentifier ?? undefined}
      onClose={() => { setViewing(null); setReload((x) => x + 1) }} />}
  </>
}

function ExposureDrawer({ incidentId, onClose }: { incidentId: string; onClose: () => void }) {
  const [report, setReport] = useState<ExposureReport | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [exporting, setExporting] = useState(false)
  const [note, setNote] = useState<{ ok: boolean; text: string } | null>(null)
  // Task 0052: a page at a time, as the review list.
  const [page, setPage] = useState<{ after: string | null; first: number }>({ after: null, first: 1 })
  useEffect(() => {
    let active = true
    quarantineExposure(incidentId, page.after).then((r) => { if (active) { setReport(r); setError(null) } }).catch((e) => { if (active) setError(loadFailed('The exposure report did not load', e)) })
    return () => { active = false }
  }, [incidentId, reload, page])

  const exportCsv = async () => {
    setExporting(true); setNote(null)
    try {
      const file = await quarantineExposureCsv(incidentId)
      const url = URL.createObjectURL(file.blob)
      const link = document.createElement('a')
      link.href = url; link.download = file.filename ?? `vero-quarantine-exposure-${new Date().toISOString().slice(0, 10)}.csv`
      document.body.appendChild(link); link.click(); link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      setNote({ ok: true, text: exportNote(file.reference, file.fingerprint) })
    } catch (e) {
      setNote({ ok: false, text: `The export didn’t work. ${exportFailureText('An exposure export failed', e)}` })
    } finally { setExporting(false) }
  }

  return (
    <Drawer eyebrow="Quarantine" title={report ? `Exposure: ${report.name}` : 'Exposure report'} onClose={onClose} wide
      footer={<button type="button" className="v-btn" disabled={!report || exporting} onClick={() => void exportCsv()}><Download size={15} aria-hidden />{exporting ? 'Exporting…' : 'Export CSV'}</button>}>
      <p className="faint">Who opened, printed or downloaded each document before it was quarantined, from the audit. Never a file name, title, content or reason.</p>
      {note && (note.ok ? <div className="hold-found" role="status"><Check size={16} strokeWidth={2.2} aria-hidden /><span className="q-note">{note.text}</span></div>
        : <Callout tone="warn" title="Not exported">{note.text}</Callout>)}
      {error && !report ? <FailureNotice failure={error} loadTitle="The exposure report didn't load" onRetry={() => setReload((x) => x + 1)} />
        : !report ? <p className="muted">Reading the audit…</p>
        : <>
          <div className="q-tiles" role="group" aria-label="Documents by level">
            <div><span>Standard</span><strong>{report.standard}</strong></div>
            <div><span>Confidential</span><strong>{report.confidential}</strong></div>
            <div><span>Highly Confidential</span><strong>{report.highlyConfidential}</strong></div>
          </div>
          {report.hiddenCount > 0 && <p className="faint">{plural(report.hiddenCount, 'document')} at a level you can&apos;t open {report.hiddenCount === 1 ? 'is' : 'are'} counted but not listed.</p>}
          {pageNote(page.first, report.documents.length, report.documentCount) && <p className="faint">{pageNote(page.first, report.documents.length, report.documentCount)} Export CSV has every one.</p>}
          {report.documents.length === 0 ? <p className="muted">No document to list.</p>
            : <ul className="q-exposure">{report.documents.map((d) => <li key={d.documentId}>
              <div className="row wrap" style={{ gap: 8, alignItems: 'center' }}>
                <strong className="mono">{d.primaryIdentifier ?? '—'}</strong>{d.level && <LevelBadge level={d.level} />}
                <span className="faint">received {fmtDate(d.receivedAt)} · {pagesWords(d.pageCount)} · quarantined {fmtDate(d.quarantinedAt)}{d.inForce ? '' : ' · released'}</span>
              </div>
              {d.exposure.length === 0 ? <p className="faint">Nobody opened, printed or downloaded it before.</p>
                : <ol className="q-entries">{d.exposure.map((x, i) => <li key={i}><b>{x.what}</b> · {x.person} · <span className="faint">{fmtDateTime(x.at)}</span></li>)}</ol>}
            </li>)}</ul>}
          {(page.after || report.next) && <div className="row wrap" style={{ gap: 8, justifyContent: 'flex-end' }}>
            {page.after && <button type="button" className="v-btn sm" onClick={() => setPage({ after: null, first: 1 })}>First page</button>}
            {report.next && <button type="button" className="v-btn sm" onClick={() => setPage({ after: report.next, first: page.first + report.documents.length })}>Next {report.listLimit}</button>}
          </div>}
        </>}
    </Drawer>
  )
}

/** Restricted steward only: a filter, the server's live count, "select all matching", a name and a reason. */
function NewIncident({ onCancel, onCreated }: { onCancel: () => void; onCreated: (incidentId: string) => void }) {
  const { toast } = useStore()
  const [sources, setSources] = useState<Source[]>([])
  const [filter, setFilter] = useState<QuarantineFilter>({ receivedFrom: '', receivedTo: '', sourceId: '', fields: {} })
  const [count, setCount] = useState<QuarantineCount | null>(null)
  const [counting, setCounting] = useState(false)
  const [countProblem, setCountProblem] = useState<string | null>(null)
  const [all, setAll] = useState(false)
  const [name, setName] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [recount, setRecount] = useState(0)

  useEffect(() => { let active = true; schema().then((s) => { if (active) setSources(s.sources.map(toSource)) }).catch(() => { if (active) setSources([]) }); return () => { active = false } }, [])
  const source = sources.find((s) => s.id === filter.sourceId)
  const notYet = filterProblem(filter)

  // The live count, a short pause after each change; a new count unticks "select all".
  useEffect(() => {
    setCount(null); setAll(false); setCountProblem(null)
    if (notYet) return
    let active = true
    const t = window.setTimeout(() => {
      setCounting(true)
      countQuarantineFilter(filter).then((c) => { if (active) setCount(c) }).catch((e) => { if (active) setCountProblem(refusalText(e)) }).finally(() => { if (active) setCounting(false) })
    }, 400)
    return () => { active = false; window.clearTimeout(t) }
  }, [JSON.stringify(filter), recount]) // eslint-disable-line react-hooks/exhaustive-deps

  // Task 0051: no limit; whatever matches is quarantined.
  const ready = !!count && count.count > 0 && all && nameReady(name) && reasonReady(reason) && !busy
  const set = (patch: Partial<QuarantineFilter>) => setFilter((f) => ({ ...f, ...patch }))

  const submit = async () => {
    if (!ready || !count) return
    setBusy(true); setProblem(null)
    try {
      const incident = await quarantineByFilter(filter, count.count, name.trim(), reason.trim())
      const n = inQuarantineCount(incident)
      toast({ tone: 'ok', title: `${countWords(n)} document${n === 1 ? '' : 's'} quarantined`, detail: `Incident “${incident.name}”. Everyone else sees ${n === 1 ? 'it' : 'them'} as temporarily unavailable.${incident.preparing ? ' The review list is being prepared.' : ''}` })
      onCreated(incident.incidentId)
    } catch (e) { setProblem(refusalText(e)); setRecount((x) => x + 1) }
    finally { setBusy(false) }
  }

  return (
    <section className="v-panel hold-form" aria-label="New incident">
      <span className="v-sec">New incident by filter</span>
      <div className="hold-fields">
        <label className="v-field" style={{ width: 170 }}><span>Received from</span>
          <input className="v-input" type="date" value={filter.receivedFrom} max={filter.receivedTo || undefined} onChange={(e) => set({ receivedFrom: e.target.value })} /></label>
        <label className="v-field" style={{ width: 170 }}><span>Received to</span>
          <input className="v-input" type="date" value={filter.receivedTo} min={filter.receivedFrom || undefined} onChange={(e) => set({ receivedTo: e.target.value })} /></label>
        <label className="v-field" style={{ minWidth: 220 }}><span>Sending system</span>
          <select className="v-select" value={filter.sourceId} onChange={(e) => set({ sourceId: e.target.value, fields: {} })}>
            <option value="">Any sending system</option>{sources.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select></label>
      </div>
      {source && source.fields.length > 0 && <div className="hold-fields">
        {source.fields.map((f) => <label key={f.key} className="v-field" style={{ width: 220 }}><span>{f.label}<span className="v-hint">{wholeHint(f)}</span></span>
          <input className="v-input" type={f.type === 'DATE' ? 'date' : 'text'} value={filter.fields?.[f.key] ?? ''} maxLength={FIELD_VALUE_MAX} autoComplete="off" spellCheck={false}
            onChange={(e) => set({ fields: { ...filter.fields, [f.key]: e.target.value } })} /></label>)}
      </div>}
      <p className="faint" style={{ fontSize: 13 }}>Received dates are whole days in UTC. Field values must match in full; capitals don’t matter.</p>
      {notYet ? <p className="faint" role="status">{notYet}</p>
        : counting ? <p className="faint" role="status">Counting the matching documents…</p>
        : countProblem ? <Callout tone="warn" title="Not counted">{countProblem}</Callout>
        : count && (count.count === 0
          ? <div className="hold-found warn" role="status"><AlertTriangle size={16} aria-hidden /><span>No document matches this filter</span></div>
          : <div className="hold-found" role="status"><Check size={16} strokeWidth={2.2} aria-hidden />
            <label className="v-check"><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} />Select all {countWords(count.count)} matching document{count.count === 1 ? '' : 's'}</label>
            <span className="faint" style={{ marginLeft: 'auto' }}>You won&apos;t see their titles or contents here</span></div>)}
      <div className="hold-fields">
        <label className="v-field" style={{ width: 300 }}><span>Incident name</span>
          <input className="v-input" value={name} maxLength={NAME_MAX} onChange={(e) => setName(e.target.value)} placeholder="For example: Scanner fault, 12 November" /></label>
        <label className="v-field" style={{ flex: 1, minWidth: 240 }}><span>Reason</span>
          <input className="v-input" value={reason} maxLength={REASON_MAX} onChange={(e) => setReason(e.target.value)} placeholder="Why these documents must be reviewed" /></label>
      </div>
      {problem && <Callout tone="warn" title="Not quarantined">{problem}</Callout>}
      <div className="row wrap" style={{ gap: 10, justifyContent: 'flex-end' }}>
        <button type="button" className="v-btn" disabled={busy} onClick={onCancel}>Cancel</button>
        <button type="button" className="v-btn primary" disabled={!ready} onClick={() => void submit()}>{busy ? 'Quarantining…' : count && all ? `Quarantine ${countWords(count.count)} document${count.count === 1 ? '' : 's'}` : 'Quarantine'}</button>
      </div>
    </section>
  )
}

/**
 * Quarantine one document (record panel and viewer): into a new incident (name and reason) or an open one. Shown to
 * Stewards for documents they can open; the server checks the level again (SDV-QUA-002).
 */
export function QuarantineDialog({ documents, initialId, onClose, onDone }: { documents: { id: string; label: string }[]; initialId?: string; onClose: () => void; onDone: () => void }) {
  const { toast } = useStore()
  const [docId, setDocId] = useState(initialId ?? documents[0]?.id ?? '')
  const [incidents, setIncidents] = useState<QuarantineIncidentSummary[] | null>(null)
  const [into, setInto] = useState<'new' | 'open'>('new')
  const [incidentId, setIncidentId] = useState('')
  const [name, setName] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  useEffect(() => {
    let active = true
    quarantineIncidents().then((l) => { if (!active) return; const open = l.filter((i) => i.status === 'OPEN'); setIncidents(open); if (open[0]) setIncidentId(open[0].incidentId) })
      .catch(() => { if (active) setIncidents([]) })
    return () => { active = false }
  }, [])
  const ready = !!docId && !busy && (into === 'open' ? !!incidentId : nameReady(name) && reasonReady(reason))
  const submit = async () => {
    if (!ready) return
    setBusy(true); setProblem(null)
    try {
      const incident = await quarantineDocument(docId, into === 'open' ? { incidentId } : { name: name.trim(), reason: reason.trim() })
      toast({ tone: 'ok', title: 'Quarantined', detail: `In incident “${incident.name}”. Everyone else sees it as temporarily unavailable; you can still review it.` })
      onDone()
    } catch (e) { setProblem(refusalText(e)) }
    finally { setBusy(false) }
  }
  const open = incidents ?? []
  return (
    <Dialog title="Quarantine a document" onClose={() => { if (!busy) onClose() }} footer={<>
      <button type="button" className="v-btn" disabled={busy} onClick={onClose}>Cancel</button>
      <button type="button" className="v-btn primary" disabled={!ready} onClick={() => void submit()}>{busy ? 'Quarantining…' : 'Quarantine'}</button>
    </>}>
      {documents.length > 1 && <label className="v-field"><span>Document</span>
        <select className="v-select" value={docId} onChange={(e) => setDocId(e.target.value)}>{documents.map((d) => <option key={d.id} value={d.id}>{d.label}</option>)}</select></label>}
      <fieldset className="q-into"><legend className="v-label">Into</legend>
        <label className="v-check"><input type="radio" name="q-into" checked={into === 'new'} onChange={() => setInto('new')} />A new incident</label>
        <label className="v-check"><input type="radio" name="q-into" checked={into === 'open'} disabled={open.length === 0} onChange={() => setInto('open')} />An open incident{incidents && open.length === 0 ? ' (none open)' : ''}</label>
      </fieldset>
      {into === 'new' ? <>
        <label className="v-field"><span>Incident name</span><input className="v-input" value={name} maxLength={NAME_MAX} onChange={(e) => setName(e.target.value)} placeholder="For example: Wrong customer's statement" /></label>
        <label className="v-field"><span>Reason</span><textarea className="v-input" rows={3} value={reason} maxLength={REASON_MAX} onChange={(e) => setReason(e.target.value)} placeholder="Why it must be reviewed" /></label>
      </> : <label className="v-field"><span>Incident</span>
        <select className="v-select" value={incidentId} onChange={(e) => setIncidentId(e.target.value)}>{open.map((i) => <option key={i.incidentId} value={i.incidentId}>{i.name} · {plural(i.documentsInForce, 'document')}</option>)}</select></label>}
      <p className="muted">Until it is released, everyone else sees it as temporarily unavailable, without its level or why. Stewards who can open its level can still read and correct it. Nothing is deleted.</p>
      {problem && <Callout tone="warn" title="Not quarantined">{problem}</Callout>}
    </Dialog>
  )
}
