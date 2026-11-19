import { useEffect, useMemo, useState } from 'react'
import { ChevronRight } from 'lucide-react'
import { FailureNotice, loadFailed, type Failure } from './CardError'
import { Badge, Callout, Drawer, TechDetails, fmtDateTime, fmtRel } from './ui'
import { opsDetail, type OpsDetail } from './liveApi'
import { problemNote, problemSentences, type ProblemFacts, type ProblemKind } from './problemWords'

// Task 0011 Part A: one read-only list of problems for Platform operations and Tenant IT. Nothing can be fixed here:
// recovery is the sending system sending again, so each problem says who to ask.

type Problem = ProblemFacts & { id: string; title?: string | null; tech: [string, string][] }
const FILTERS: { id: 'all' | ProblemKind; label: string }[] = [
  { id: 'all', label: 'All' }, { id: 'store', label: 'Could not store' }, { id: 'stuck', label: 'Stuck' }, { id: 'missing', label: 'Missing documents' },
]

export function toProblems(d: OpsDetail): Problem[] {
  const rec = (submissionId: string) => d.problemRecords?.[submissionId]
  const store: Problem[] = d.processingFailures.map((f) => ({
    id: `store-${f.documentId}`, kind: 'store', file: f.originalFilename, at: f.ingestedAt, errorCode: f.errorCode, attempts: f.processingAttemptCount,
    source: rec(f.submissionId)?.sourceName, recordNumber: rec(f.submissionId)?.recordNumber, recordLabel: rec(f.submissionId)?.recordNumberLabel, title: rec(f.submissionId)?.recordTitle,
    tech: [['Error code', f.errorCode], ['Attempts', String(f.processingAttemptCount)], ['Document', f.documentId], ['Submission', f.submissionId], ['Sending system', f.sourceId], ['Received', fmtDateTime(f.ingestedAt)]],
  }))
  const stuck: Problem[] = d.stuckDocuments.map((s) => ({
    id: `stuck-${s.documentId}`, kind: 'stuck', file: s.originalFilename, at: s.stateSince, status: s.status,
    source: rec(s.submissionId)?.sourceName, recordNumber: rec(s.submissionId)?.recordNumber, recordLabel: rec(s.submissionId)?.recordNumberLabel, title: rec(s.submissionId)?.recordTitle,
    tech: [['State', s.status], ['Error code', s.processingErrorCode ?? '—'], ['Document', s.documentId], ['Submission', s.submissionId], ['Since', fmtDateTime(s.stateSince)]],
  }))
  const missing: Problem[] = d.attentionSubmissions.map((a) => ({
    id: `missing-${a.submissionId}`, kind: 'missing', at: a.lastActivityAt, expected: a.expectedDocumentCount, received: a.receivedDocumentCount,
    source: rec(a.submissionId)?.sourceName, recordNumber: rec(a.submissionId)?.recordNumber, recordLabel: rec(a.submissionId)?.recordNumberLabel, title: rec(a.submissionId)?.recordTitle,
    tech: [['State', a.status], ['Submission', a.submissionId], ['Delivery ID from the sending system', a.sourceSubmissionId || '—'], ['Attention after', `${a.attentionHours} hours`], ['Last activity', fmtDateTime(a.lastActivityAt)]],
  }))
  return [...store, ...stuck, ...missing].sort((a, b) => b.at.localeCompare(a.at))
}

export function Problems({ role }: { role: 'platform' | 'tenantIt' }) {
  const [data, setData] = useState<OpsDetail | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [filter, setFilter] = useState<'all' | ProblemKind>('all')
  const [open, setOpen] = useState<Problem | null>(null)
  const [copied, setCopied] = useState(false)
  useEffect(() => {
    let live = true
    opsDetail(role).then((d) => { if (live) { setData(d); setError(null) } }).catch((e) => { if (live) setError(loadFailed('Problems did not load', e)) })
    return () => { live = false }
  }, [role, reload])
  const all = useMemo(() => (data ? toProblems(data) : []), [data])
  const count = (k: 'all' | ProblemKind) => (k === 'all' ? all.length : all.filter((p) => p.kind === k).length)
  const shown = filter === 'all' ? all : all.filter((p) => p.kind === filter)
  const partial = data?.dataObservations?.some((x) => x.status === 'DEGRADED')

  const copy = async (p: Problem) => {
    const text = problemNote(p)
    try { await navigator.clipboard.writeText(text); setCopied(true); window.setTimeout(() => setCopied(false), 2500) }
    catch { setCopied(false) }
  }

  // Canvas: the chips ("All · 4", the chosen one dark), each row's kind as a badge before what happened, a chevron;
  // the detail panel with small capital section labels and one button for the note (the note itself is in the page
  // for screen readers, not shown).
  const KIND: Record<ProblemKind, { label: string; tone: 'bad' | 'warn' }> = { store: { label: 'Could not store', tone: 'bad' }, stuck: { label: 'Stuck', tone: 'warn' }, missing: { label: 'Missing documents', tone: 'warn' } }
  return <>
    {error && <FailureNotice failure={error} loadTitle="Problems didn't load" onRetry={() => setReload((x) => x + 1)} />}
    {partial && <Callout tone="warn" title="Some problems couldn't be read">Part of this list didn't load. Missing rows don't mean there are no problems.</Callout>}
    <div className="row between wrap" style={{ gap: 8 }}>
      <div className="pr-chips" role="group" aria-label="Show">
        {FILTERS.map((f) => <button key={f.id} type="button" className="pr-chip" aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}>{f.label} · {count(f.id)}</button>)}
      </div>
      <button type="button" className="v-btn sm" onClick={() => setReload((x) => x + 1)}>Check again</button>
    </div>
    <section className="v-panel flush" aria-label="Problems">
      {!data && !error ? <p className="muted" style={{ padding: 16 }}>Loading…</p> : shown.length === 0 ? <p className="muted" style={{ padding: 16 }}>{filter === 'all' ? 'No problems right now.' : 'None of this kind right now.'}</p> :
      <table className="v-table pr-table" aria-label="Problems list"><thead><tr><th>Record</th><th>What happened</th><th>From</th><th>Since</th><th className="act"><span className="sr-only">Open</span></th></tr></thead>
        <tbody>{shown.map((p) => { const s = problemSentences(p); return (
          <tr key={p.id} className="clickable" tabIndex={0} aria-selected={open?.id === p.id} onClick={() => setOpen(p)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setOpen(p) } }}>
            <td className="lead" data-label="Record"><span className="mono">{p.recordNumber ?? '—'}</span>{p.title && <span className="pr-title">{p.title}</span>}</td>
            <td data-label="What happened"><Badge tone={KIND[p.kind].tone}>{KIND[p.kind].label}</Badge> {s.what}</td>
            <td data-label="From">{p.source ?? '—'}</td>
            <td data-label="Since" className="faint">{fmtRel(p.at)}</td>
            <td className="act faint"><ChevronRight size={16} aria-hidden /></td>
          </tr>) })}</tbody></table>}
    </section>

    {open && (() => { const s = problemSentences(open); return (
      <Drawer eyebrow="Problem" title={s.what} onClose={() => { setOpen(null); setCopied(false) }}>
        {(open.recordNumber || open.title) && <p className="mono faint pr-sub">{[open.recordNumber, open.title].filter(Boolean).join(' · ')}</p>}
        <section className="pr-block"><h3 className="v-sec">What happened</h3><p>{s.happened}</p></section>
        <section className="pr-block"><h3 className="v-sec">What it means</h3><p>{s.means}</p></section>
        <section className="pr-block"><h3 className="v-sec">What you can do</h3><p>{s.todo}</p>
          <pre className="pr-note sr-only" aria-label="Note to copy">{problemNote(open)}</pre>
          <div className="row" style={{ gap: 8, alignItems: 'center' }}>
            <button type="button" className="v-btn sm" onClick={() => void copy(open)}>Copy a note for the {open.source ?? 'sending system'} team</button>
            {copied && <span className="faint" role="status">Copied</span>}
          </div>
        </section>
        <TechDetails flat hint="error code, document and submission IDs, attempts"><dl className="v-kv">{open.tech.map(([k, v]) => <div key={k}><dt>{k}</dt><dd className="mono">{v}</dd></div>)}</dl></TechDetails>
      </Drawer>) })()}
  </>
}
