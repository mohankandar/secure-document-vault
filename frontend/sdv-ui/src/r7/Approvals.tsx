import { useEffect, useMemo, useState } from 'react'
import { ChevronDown, ChevronRight, CircleAlert, FileText, Files } from 'lucide-react'
import type { SensitivityLevel } from '../api'
import { useStore } from './store'
import { FailureNotice, loadFailed, type Failure } from './CardError'
import { Callout, Dialog, Drawer, Switch, fmtDate } from './ui'
import { refusalText, whenWords } from './Sensitivity'
import { itemWords } from './problemWords'
import { BULK_MAX, LEVEL_NAMES, approvalLevelNote, decideIncidentRelease, decideMany, decideRequest, decidedRecently, incidentReleaseRequests, pendingRequests, type BulkItem, type DecidedRequest, type IncidentReleaseRequest, type PendingList, type PendingRequest } from './liveApi'
import { levelsLine } from './quarantineWords'

/**
 * Task 0030 (S9a part 2), board Sens-Approvals: the Business admin's approvals. One line per request, grouped by the
 * record's primary identifier, oldest first. Only the primary identifier, received date, page count, level, requester,
 * reason and time are shown: never the document ID, a title, a name from the record or its content.
 * Every Business admin of the tenant sees the same list and any of them may decide; the server refuses a decision on
 * something already decided (SDV-APR-008) and on one's own request (SDV-APR-006), and the screen shows its words.
 * A whole-record request is never ticked or sent in bulk: it is reviewed and decided on its own.
 */

type Kind = 'all' | 'DOWNLOAD' | 'PRINT' | 'LOWER' | 'REDACT_HELD'
const KIND_CHIPS: { id: Kind; label: string }[] = [{ id: 'all', label: 'All' }, { id: 'DOWNLOAD', label: 'Downloads' }, { id: 'PRINT', label: 'Prints' }, { id: 'LOWER', label: 'Lower level' }, { id: 'REDACT_HELD', label: 'Redact on hold' }]
const ofKind = (r: PendingRequest, k: Kind) => k === 'all' || (k === 'LOWER' ? r.kind === 'LOWER_LEVEL' || r.kind === 'LOWER_RECORD_LEVEL' : r.kind === k)
/** More than this many waiting: the groups start collapsed. */
const COLLAPSE_OVER = 10
/** Groups shown before "Show all contracts". */
const GROUPS_SHOWN = 20
const SHORT: Record<SensitivityLevel, string> = { STANDARD: 'Standard', CONFIDENTIAL: 'Confidential', HIGHLY_CONFIDENTIAL: 'HC' }
const LVL: Record<SensitivityLevel, string> = { STANDARD: 's', CONFIDENTIAL: 'c', HIGHLY_CONFIDENTIAL: 'hc' }
const whole = (r: PendingRequest) => r.scope === 'RECORD'
/** Decided one at a time, never ticked: a whole contract, and a redaction of a document on legal hold (task 0066). */
const alone = (r: PendingRequest) => whole(r) || r.kind === 'REDACT_HELD'
const REDACT_WORDS = 'Redact on legal hold'
const key = (g: { primaryIdentifier: string | null }) => g.primaryIdentifier ?? '—'

/** "10 min", "2 h", "2 days": how long a request has waited. */
export function waitedFor(iso: string, now = Date.now()): string {
  const m = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 60_000))
  if (m < 60) return `${Math.max(1, m)} min`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} h`
  const d = Math.floor(h / 24)
  return `${d} day${d === 1 ? '' : 's'}`
}

export type WaitingSummary = { count: number; oldest: string | null }
export function waitingSummary(list: PendingList): WaitingSummary {
  const all = list.groups.flatMap((g) => g.requests)
  const oldest = all.reduce<string | null>((o, r) => (!o || r.requestedAt < o ? r.requestedAt : o), null)
  return { count: list.requestCount, oldest: oldest ? waitedFor(oldest) : null }
}
/** Task 0049: the waiting count with the whole-incident release requests added. */
export function waitingWithReleases(list: PendingList, releases: IncidentReleaseRequest[]): WaitingSummary {
  const all = [...list.groups.flatMap((g) => g.requests.map((r) => r.requestedAt)), ...releases.map((r) => r.requestedAt)]
  const oldest = all.reduce<string | null>((o, at) => (!o || at < o ? at : o), null)
  return { count: list.requestCount + releases.length, oldest: oldest ? waitedFor(oldest) : null }
}

function Lvl({ level }: { level: SensitivityLevel }) {
  return <span className={`apr-lvl ${LVL[level]}`} title={LEVEL_NAMES[level]}>{SHORT[level]}</span>
}

/** "Download", "Lower to Standard": the request in words, without the level badge. */
const kindWords = (r: PendingRequest) => r.kind === 'REDACT_HELD' ? REDACT_WORDS : r.kind === 'PRINT' ? 'Print' : r.kind === 'DOWNLOAD' ? 'Download' : `Lower to ${r.requestedLevel ? LEVEL_NAMES[r.requestedLevel] : 'a lower level'}`
const kindCount = (n: number, r: PendingRequest) => r.kind === 'REDACT_HELD' ? `${n} redaction${n === 1 ? '' : 's'} on hold` : r.kind === 'PRINT' ? `${n} print${n === 1 ? '' : 's'}` : r.kind === 'DOWNLOAD' ? `${n} download${n === 1 ? '' : 's'}` : `${n} lower to ${r.requestedLevel ? LEVEL_NAMES[r.requestedLevel] : 'a lower level'}`
/** "2 downloads, 1 print": the kinds of these requests, most first. */
export function kindsOf(requests: PendingRequest[]): string {
  const counts = new Map<string, { n: number; r: PendingRequest }>()
  for (const r of requests) { const k = r.kind + '|' + (r.requestedLevel ?? ''); const c = counts.get(k); counts.set(k, { n: (c?.n ?? 0) + 1, r }) }
  return [...counts.values()].sort((a, b) => b.n - a.n).map((c) => kindCount(c.n, c.r)).join(', ')
}
const and = (parts: string[]) => parts.length <= 1 ? parts.join('') : `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`
const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`


export function Approvals({ onWaiting }: { onWaiting: (w: WaitingSummary) => void }) {
  const { can, toast } = useStore()
  const [list, setList] = useState<PendingList | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [kind, setKind] = useState<Kind>('all')
  const [requester, setRequester] = useState('')
  const [grouped, setGrouped] = useState(true)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [open, setOpen] = useState<Record<string, boolean>>({})
  const [busy, setBusy] = useState(false)
  const [problems, setProblems] = useState<{ title: string; lines: string[] } | null>(null)
  const [review, setReview] = useState<PendingRequest | null>(null)
  const [showAll, setShowAll] = useState(false)
  const [recentOpen, setRecentOpen] = useState(false)
  // Task 0031 (0.2): "Decided recently" comes from the server: every Business admin's decisions, not this tab's.
  const [recent, setRecent] = useState<DecidedRequest[] | null>(null)
  const [recentError, setRecentError] = useState<Failure | null>(null)
  useEffect(() => {
    if (!recentOpen) return
    let active = true
    setRecent(null); setRecentError(null)
    decidedRecently().then((r) => { if (active) setRecent(r) }).catch((e) => { if (active) setRecentError(loadFailed('Recent decisions did not load', e)) })
    return () => { active = false }
  }, [recentOpen, reload])

  useEffect(() => {
    let active = true
    pendingRequests().then((l) => {
      if (!active) return
      setList(l); setError(null)
      // Ticks of requests that are no longer waiting are dropped.
      const ids = new Set(l.groups.flatMap((g) => g.requests.map((r) => r.requestId)))
      setSelected((s) => new Set([...s].filter((id) => ids.has(id))))
    }).catch((e) => { if (active) setError(loadFailed('Approvals did not load', e)) })
    return () => { active = false }
  }, [reload]) // eslint-disable-line react-hooks/exhaustive-deps
  // Task 0049: whole-incident release requests (quarantine), decided one at a time; never in bulk, never a reason.
  const [releases, setReleases] = useState<IncidentReleaseRequest[] | null>(null)
  const [releasesError, setReleasesError] = useState<Failure | null>(null)
  const [reviewRelease, setReviewRelease] = useState<IncidentReleaseRequest | null>(null)
  useEffect(() => {
    let active = true
    incidentReleaseRequests().then((r) => { if (active) { setReleases(r); setReleasesError(null) } }).catch((e) => { if (active) { setReleases(null); setReleasesError(loadFailed('Incident release requests did not load', e)) } })
    return () => { active = false }
  }, [reload])
  useEffect(() => { if (list) onWaiting(waitingWithReleases(list, releases ?? [])) }, [list, releases]) // eslint-disable-line react-hooks/exhaustive-deps

  const all = useMemo(() => list?.groups.flatMap((g) => g.requests) ?? [], [list])
  const byId = useMemo(() => new Map(all.map((r) => [r.requestId, r])), [all])
  const groupOf = useMemo(() => new Map(list?.groups.flatMap((g) => g.requests.map((r) => [r.requestId, key(g)] as const)) ?? []), [list])
  const requesters = useMemo(() => [...new Set(all.map((r) => r.requester))].sort((a, b) => a.localeCompare(b)), [all])
  const forRequester = all.filter((r) => !requester || r.requester === requester)
  const groups = (list?.groups ?? []).map((g) => ({ ...g, requests: g.requests.filter((r) => ofKind(r, kind) && (!requester || r.requester === requester)) })).filter((g) => g.requests.length > 0)
  const shownGroups = showAll ? groups : groups.slice(0, GROUPS_SHOWN)
  const total = list?.requestCount ?? 0
  const isOpen = (g: { primaryIdentifier: string | null; requests: PendingRequest[] }) => open[key(g)] ?? (g.requests.some(whole) || total <= COLLAPSE_OVER)

  if (!can('APPROVE_SENSITIVE_ACCESS')) return <Callout tone="warn" title="Only a Business admin decides requests" />
  if (error && !list) return <FailureNotice failure={error} loadTitle="Approvals didn't load" onRetry={() => setReload((x) => x + 1)} />
  if (!list) return <Callout title="Loading">Reading the requests that are waiting…</Callout>

  const toggle = (ids: string[], on: boolean) => setSelected((s) => { const n = new Set(s); for (const id of ids) { if (on) n.add(id); else n.delete(id) } return n })
  const tickable = (rs: PendingRequest[]) => rs.filter((r) => !alone(r)).map((r) => r.requestId)

  /** Many decisions at once (never a whole-record request), at most BULK_MAX per call; each is decided on its own. */
  const decideAll = async (decision: 'APPROVE' | 'DECLINE', ids: string[]) => {
    const sendable = ids.filter((id) => { const r = byId.get(id); return r && !alone(r) })
    if (!sendable.length || busy) return
    setBusy(true); setProblems(null)
    const results: BulkItem[] = []
    try {
      for (let i = 0; i < sendable.length; i += BULK_MAX) results.push(...(await decideMany(decision, sendable.slice(i, i + BULK_MAX))).results)
    } catch (e) {
      setProblems({ title: decision === 'APPROVE' ? 'Not approved' : 'Not declined', lines: [refusalText(e)] })
    } finally {
      const done = results.filter((r) => r.outcome === 'APPROVED' || r.outcome === 'DECLINED')
      const refused = results.filter((r) => r.outcome === 'REFUSED' || r.outcome === 'FAILED')
      if (done.length) toast({ tone: 'ok', title: `${plural(done.length, 'request')} ${decision === 'APPROVE' ? 'approved' : 'declined'}`, detail: 'Each requester sees it under Requests the next time they open Vero.' })
      if (refused.length) setProblems({ title: `${plural(refused.length, 'request')} ${refused.length === 1 ? 'was' : 'were'} not ${decision === 'APPROVE' ? 'approved' : 'declined'}`, lines: refused.map((r) => {
        const req = byId.get(r.requestId)
        // Task 0044: the catalog's words for the item's code, never the server's message.
        return `${req ? `${kindWords(req)} on ${req.primaryIdentifier ?? 'a record'} (${req.requester})` : 'A request'}: ${itemWords(r.outcome === 'FAILED' ? 'FAILED' : 'REFUSED', r.code)}`
      }) })
      setSelected((s) => new Set([...s].filter((id) => !done.some((d) => d.requestId === id))))
      setBusy(false); setReload((x) => x + 1)
    }
  }
  /** One decision: a row's own buttons, and whole-record requests (Review). */
  const decideOne = async (r: PendingRequest, decision: 'approve' | 'decline') => {
    if (busy) return
    setBusy(true); setProblems(null)
    try {
      await decideRequest(r.requestId, decision)
      toast({ tone: 'ok', title: decision === 'approve' ? 'Approved' : 'Declined', detail: `${kindWords(r)} on ${r.primaryIdentifier ?? 'a record'} for ${r.requester}.` })
      setReview(null)
    } catch (e) {
      setProblems({ title: decision === 'approve' ? 'Not approved' : 'Not declined', lines: [refusalText(e)] })
    } finally { setBusy(false); setReload((x) => x + 1) }
  }

  const decideRelease = async (r: IncidentReleaseRequest, decision: 'approve' | 'decline') => {
    if (busy) return
    setBusy(true); setProblems(null)
    try {
      await decideIncidentRelease(r.requestId, decision)
      toast({ tone: 'ok', title: decision === 'approve' ? 'Release approved' : 'Release declined', detail: decision === 'approve'
        ? `${plural(r.documentCount, 'document')} in “${r.incidentName}” ${r.documentCount === 1 ? 'is' : 'are'} available again.` : `The documents in “${r.incidentName}” stay quarantined. ${r.requestedByName} sees it on the incident.` })
      setReviewRelease(null)
    } catch (e) {
      setProblems({ title: decision === 'approve' ? 'Not approved' : 'Not declined', lines: [refusalText(e)] })
    } finally { setBusy(false); setReload((x) => x + 1) }
  }

  // The bar names exactly what is ticked: "3 on 300000000001 (2 downloads, 1 print) and 2 on 300000000005 (…)".
  const ticked = [...selected].map((id) => byId.get(id)).filter((r): r is PendingRequest => !!r)
  const tickedByGroup = new Map<string, PendingRequest[]>()
  for (const r of ticked) { const g = groupOf.get(r.requestId) ?? '—'; tickedByGroup.set(g, [...(tickedByGroup.get(g) ?? []), r]) }
  const summary = and([...tickedByGroup.entries()].map(([g, rs]) => `${rs.length} on ${g} (${kindsOf(rs)})`))

  return <>
    <div className="apr-toolbar" role="toolbar" aria-label="Approvals filters">
      {KIND_CHIPS.map((c) => <button key={c.id} type="button" className="apr-chip" aria-pressed={kind === c.id} onClick={() => setKind(c.id)}>{c.label} <span className="c">{forRequester.filter((r) => ofKind(r, c.id)).length}</span></button>)}
      <span className="apr-gap" />
      <label className="apr-chip apr-who"><span className="sr-only">Requested by</span>
        <select aria-label="Requested by" value={requester} onChange={(e) => setRequester(e.target.value)}>
          <option value="">Requested by: anyone</option>{requesters.map((p) => <option key={p} value={p}>Requested by: {p}</option>)}
        </select><ChevronDown size={12} strokeWidth={2.4} aria-hidden /></label>
      <span className="grow" />
      <span className="faint apr-meta">Group by contract</span><Switch checked={grouped} onChange={setGrouped} label={`Group by contract: ${grouped ? 'on' : 'off'}`} />
      <span className="faint apr-meta" style={{ marginLeft: 12 }}>Oldest first</span>
    </div>

    {problems && <Callout tone="bad" title={problems.title}>{problems.lines.map((l) => <span key={l} className="apr-problem">{l}</span>)}</Callout>}

    {ticked.length > 0 && <div className="apr-selbar" role="region" aria-label="Selected requests">
      <span><b>{ticked.length} selected</b> · {summary} · all one document</span>
      <span className="grow" />
      <button type="button" className="v-btn quiet sm" onClick={() => setSelected(new Set())}>Clear</button>
      <button type="button" className="v-btn sm" disabled={busy} onClick={() => void decideAll('DECLINE', ticked.map((r) => r.requestId))}>Decline {ticked.length}</button>
      <button type="button" className="v-btn sm primary" disabled={busy} onClick={() => void decideAll('APPROVE', ticked.map((r) => r.requestId))}>Approve {ticked.length}</button>
    </div>}

    {releasesError && <FailureNotice failure={releasesError} loadTitle="Incident release requests didn't load" onRetry={() => setReload((x) => x + 1)} />}
    {releases && releases.length > 0 && <section className="v-panel apr-card" aria-label="Release incident requests">
      <div className="apr-gh"><b>Release incident</b><span className="faint">{plural(releases.length, 'request')} · quarantine</span>
        <span className="apr-lock"><CircleAlert size={12} strokeWidth={2.4} aria-hidden />Whole incident: decide each on its own</span></div>
      {releases.map((r) => <div key={r.requestId} className="apr-row q-release">
        <span className="apr-sc whole" title="Whole incident" aria-label="Whole incident" role="img"><Files size={16} aria-hidden /></span>
        <span className="apr-kind"><b>{r.incidentName}</b></span>
        <span className="apr-whole">{plural(r.documentCount, 'document')}</span>
        <span className="faint" title="Documents by level">{levelsLine(r.standard, r.confidential, r.highlyConfidential)}</span>
        <span className="faint">{r.firstReceivedAt ? `received ${fmtDate(r.firstReceivedAt)}${r.lastReceivedAt && fmtDate(r.lastReceivedAt) !== fmtDate(r.firstReceivedAt) ? ` – ${fmtDate(r.lastReceivedAt)}` : ''}` : ''}</span>
        <span className="apr-who-cell">{r.requestedByName}</span>
        <span className="faint" title={whenWords(r.requestedAt)}>{waitedFor(r.requestedAt)}</span>
        <span className="apr-acts">
          <button type="button" className="v-btn xs" disabled={busy} onClick={() => void decideRelease(r, 'decline')} aria-label={`Decline release of ${r.incidentName}`}>Decline</button>
          <button type="button" className="v-btn xs primary" disabled={busy} onClick={() => setReviewRelease(r)} aria-label={`Review release of ${r.incidentName}`}>Review</button>
        </span>
      </div>)}
    </section>}

    {groups.length === 0 ? <section className="v-panel"><p className="muted" style={{ padding: 16 }}>{total === 0 ? (releases?.length ? 'No other request is waiting.' : 'Nothing is waiting. New requests show up here.') : 'No waiting request matches these filters.'}</p></section>
      : <section className="v-panel apr-card" aria-label="Waiting requests">
        {grouped ? shownGroups.map((g) => {
          const ids = tickable(g.requests), on = ids.filter((id) => selected.has(id)).length, expanded = isOpen(g), k = key(g)
          const people = [...new Set(g.requests.map((r) => r.requester))].join(', ')
          const oldest = g.requests.reduce((o, r) => (r.requestedAt < o ? r.requestedAt : o), g.requests[0].requestedAt)
          return <div key={k} role="group" aria-label={`Requests on ${k}`}>
            <div className={`apr-gh${expanded ? '' : ' col'}`}>
              <input type="checkbox" aria-label={`Select all on ${k}`} disabled={ids.length === 0} checked={ids.length > 0 && on === ids.length}
                ref={(el) => { if (el) el.indeterminate = on > 0 && on < ids.length }} onChange={(e) => toggle(ids, e.target.checked)} />
              <button type="button" className="apr-tg" aria-label={`${expanded ? 'Collapse' : 'Expand'} ${k}`} aria-expanded={expanded} onClick={() => setOpen((o) => ({ ...o, [k]: !expanded }))}>
                {expanded ? <ChevronDown size={14} strokeWidth={2.4} aria-hidden /> : <ChevronRight size={14} strokeWidth={2.4} aria-hidden />}</button>
              <button type="button" className="apr-id" onClick={() => setOpen((o) => ({ ...o, [k]: !expanded }))}>{k}</button>
              <span className="faint">{plural(g.requests.length, 'request')}{expanded ? '' : ` · ${kindsOf(g.requests)}`} · {people}</span>
              {g.requests.some(whole) && <span className="apr-lock"><CircleAlert size={12} strokeWidth={2.4} aria-hidden />Whole contract: decide on its own</span>}
              <span className="grow" />
              {!expanded && <span className="faint apr-small">oldest {waitedFor(oldest)}</span>}
              {ids.length > 0 && <>
                <button type="button" className="v-btn xs" disabled={busy} onClick={() => void decideAll('DECLINE', ids)}>Decline all</button>
                <button type="button" className="v-btn xs primary" disabled={busy} onClick={() => void decideAll('APPROVE', ids)}>{expanded ? `Approve all ${ids.length}` : 'Approve all'}</button>
              </>}
            </div>
            {expanded && g.requests.map((r) => <Row key={r.requestId} r={r} checked={selected.has(r.requestId)} busy={busy} onTick={(v) => toggle([r.requestId], v)} onDecide={(d) => void decideOne(r, d)} onReview={() => setReview(r)} />)}
          </div>
        }) : groups.flatMap((g) => g.requests).sort((a, b) => a.requestedAt.localeCompare(b.requestedAt)).map((r) =>
          <Row key={r.requestId} r={r} identifier checked={selected.has(r.requestId)} busy={busy} onTick={(v) => toggle([r.requestId], v)} onDecide={(d) => void decideOne(r, d)} onReview={() => setReview(r)} />)}
      </section>}

    <div className="apr-foot">
      <span>Showing {grouped ? shownGroups.length : groups.length} of {plural(list.groups.length, 'contract')} · {plural(total, 'request')} · requests nobody decides expire after {plural(list.pendingExpiryDays, 'day')} and the requester is told
        {grouped && groups.length > shownGroups.length && <> · <button type="button" className="v-link" onClick={() => setShowAll(true)}>Show all contracts</button></>}</span>
      <button type="button" className="v-link" onClick={() => setRecentOpen(true)}>Decided recently ›</button>
    </div>

    {review && <Dialog title={whole(review) ? 'Review a whole-contract request' : 'Review a redaction on legal hold'} onClose={() => { if (!busy) setReview(null) }} footer={<>
      <button type="button" className="v-btn" disabled={busy} onClick={() => setReview(null)}>Cancel</button>
      <button type="button" className="v-btn" disabled={busy} onClick={() => void decideOne(review, 'decline')}>Decline</button>
      <button type="button" className="v-btn primary" disabled={busy} onClick={() => void decideOne(review, 'approve')}>Approve</button>
    </>}>
      <dl className="v-kv">
        <div><dt>{review.primaryIdentifierLabel ?? 'Record'}</dt><dd className="mono">{review.primaryIdentifier ?? '—'}</dd></div>
        <div><dt>Asks to</dt><dd>{whole(review) ? <>{kindWords(review)} for the whole contract{review.level ? `, from ${LEVEL_NAMES[review.level]}` : ''}</> : 'Permanently redact one document on legal hold'}</dd></div>
        <div><dt>{whole(review) ? 'Documents' : 'Document'}</dt><dd>{whole(review) ? review.documentCount ?? '—' : `Received ${fmtDate(review.receivedAt)} · ${review.pageCount ?? '—'} pages${review.level ? ` · ${LEVEL_NAMES[review.level]}` : ''}`}</dd></div>
        <div><dt>Requested by</dt><dd>{review.requester}, {whenWords(review.requestedAt)}</dd></div>
        <div><dt>Reason</dt><dd>{review.reason}</dd></div>
      </dl>
      <p className="muted">{whole(review) ? '' : 'Approving replaces what everyone sees. The original is kept sealed, for Restricted Stewards only, until no legal hold is in place any more, and is then destroyed. It is decided on its own.'}</p>
      <p className="muted" hidden={!whole(review)}>Approving changes the level of every document of this contract at once. It is decided on its own, never with other requests.</p>
    </Dialog>}

    {reviewRelease && <Dialog title="Release a quarantine incident" onClose={() => { if (!busy) setReviewRelease(null) }} footer={<>
      <button type="button" className="v-btn" disabled={busy} onClick={() => setReviewRelease(null)}>Cancel</button>
      <button type="button" className="v-btn" disabled={busy} onClick={() => void decideRelease(reviewRelease, 'decline')}>Decline</button>
      <button type="button" className="v-btn primary" disabled={busy} onClick={() => void decideRelease(reviewRelease, 'approve')}>Approve release</button>
    </>}>
      <dl className="v-kv">
        <div><dt>Incident</dt><dd>{reviewRelease.incidentName}</dd></div>
        <div><dt>Documents</dt><dd>{reviewRelease.documentCount}</dd></div>
        <div><dt>Levels</dt><dd>{levelsLine(reviewRelease.standard, reviewRelease.confidential, reviewRelease.highlyConfidential)}</dd></div>
        <div><dt>Received</dt><dd>{reviewRelease.firstReceivedAt ? `${fmtDate(reviewRelease.firstReceivedAt)} to ${fmtDate(reviewRelease.lastReceivedAt)}` : '—'}</dd></div>
        <div><dt>Quarantined since</dt><dd>{fmtDate(reviewRelease.incidentCreatedAt)}</dd></div>
        <div><dt>Requested by</dt><dd>{reviewRelease.requestedByName}, {whenWords(reviewRelease.requestedAt)}</dd></div>
        <div><dt>Expires</dt><dd>{whenWords(reviewRelease.expiresAt)}, if nobody decides</dd></div>
      </dl>
      <p className="muted">Approving makes every document of the incident available again at once. It is decided on its own, never with other requests. You can&apos;t decide your own request.</p>
    </Dialog>}

    {recentOpen && <Drawer eyebrow="Approvals" title="Decided recently" onClose={() => setRecentOpen(false)}>
      <p className="faint">Decisions by every Business admin in the last 30 days, newest first (at most 50).</p>
      {recentError ? <FailureNotice failure={recentError} loadTitle="Recent decisions didn't load" onRetry={() => setReload((x) => x + 1)} />
        : !recent ? <p className="muted">Reading recent decisions…</p>
        : recent.length === 0 ? <p className="muted">Nothing decided in the last 30 days.</p>
        : <ul className="apr-recent">{recent.map((d) => <li key={d.requestId}>
        <b>{d.decision === 'APPROVE' ? 'Approved' : 'Declined'}</b> · {decidedWords(d)} on <span className="mono">{d.primaryIdentifier ?? '—'}</span><span className="faint"> · by {d.decidedBy} · {whenWords(d.decidedAt)}</span></li>)}</ul>}
    </Drawer>}
  </>
}

/** "Download", "Lower to Standard (whole contract)": a decided request in words. */
function decidedWords(d: DecidedRequest): string {
  const what = d.kind === 'REDACT_HELD' ? REDACT_WORDS : d.kind === 'PRINT' ? 'Print' : d.kind === 'DOWNLOAD' ? 'Download' : `Lower to ${d.requestedLevel ? LEVEL_NAMES[d.requestedLevel] : 'a lower level'}`
  return d.scope === 'RECORD' ? `${what} (whole contract)` : what
}

/** One request on one line, as on the board. identifier: shown when the list isn't grouped by contract. */
function Row({ r, checked, busy, identifier, onTick, onDecide, onReview }: { r: PendingRequest; checked: boolean; busy: boolean; identifier?: boolean; onTick: (v: boolean) => void; onDecide: (d: 'approve' | 'decline') => void; onReview: () => void }) {
  const isWhole = whole(r)
  const lowering = r.kind === 'LOWER_LEVEL' || r.kind === 'LOWER_RECORD_LEVEL'
  const note = approvalLevelNote(r)
  const received = `${fmtDate(r.receivedAt).replace(/ \d{4}$/, '')} · ${r.pageCount ?? '—'} pp`
  return (
    <div className={`apr-row${checked ? ' sel' : ''}`}>
      <input type="checkbox" checked={checked} disabled={alone(r)} onChange={(e) => onTick(e.target.checked)}
        aria-label={isWhole ? 'Cannot be selected: whole contract' : alone(r) ? 'Cannot be selected: document on legal hold' : `Select ${kindWords(r)} on ${r.primaryIdentifier ?? 'a record'}, ${r.requester}`} />
      <span className={`apr-sc${isWhole ? ' whole' : ''}`} title={isWhole ? 'Whole contract' : 'One document'} aria-label={isWhole ? 'Whole contract' : 'One document'} role="img">
        {isWhole ? <Files size={16} aria-hidden /> : <FileText size={16} aria-hidden />}</span>
      <span className="apr-kind" title={note ?? undefined}>
        {identifier && <span className="mono apr-rid">{r.primaryIdentifier ?? '—'}</span>}
        {lowering ? <><b>Lower to</b> {r.requestedLevel && <Lvl level={r.requestedLevel} />}{r.level && <span className="faint apr-from">from {SHORT[r.level]}</span>}</>
          : <><b>{kindWords(r)}</b> {r.level && <Lvl level={r.level} />}</>}
      </span>
      {isWhole ? <span className="apr-whole">Whole contract · {r.documentCount ?? '—'}</span>
        : <span className="faint" title={`Received ${fmtDate(r.receivedAt).replace(/ \d{4}$/, '')} · ${r.pageCount ?? '—'} pages`}>{received}</span>}
      <span className="apr-why" title={r.reason}>{r.reason}</span>
      <span className="apr-who-cell">{r.requester}</span>
      <span className="faint" title={whenWords(r.requestedAt)}>{waitedFor(r.requestedAt)}</span>
      <span className="apr-acts">
        <button type="button" className="v-btn xs" disabled={busy} onClick={() => onDecide('decline')}>Decline</button>
        {alone(r) ? <button type="button" className="v-btn xs primary" disabled={busy} onClick={onReview}>Review</button>
          : <button type="button" className="v-btn xs primary" disabled={busy} onClick={() => onDecide('approve')}>Approve</button>}
      </span>
    </div>
  )
}
