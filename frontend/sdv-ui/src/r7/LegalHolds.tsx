import { useEffect, useState } from 'react'
import { AlertTriangle, Check } from 'lucide-react'
import { useStore } from './store'
import { FailureNotice, loadFailed, type Failure } from './CardError'
import { Callout, Dialog, fmtDate } from './ui'
import { LevelBadge, refusalText } from './Sensitivity'
import { activeHolds, holdDocuments, holdReleaseRequests, placeHold, requestHoldRelease, type HoldDocument, type HoldReleaseWaiting, type LegalHold } from './liveApi'

/**
 * Task 0030 (S9a part 2), board Sens-LegalHold. A Business admin puts every document of a contract on hold by its
 * number, with a reason; the screen says how many documents that covers, never which ones (no title, file name or
 * content: the server sends none). Holds in place are listed with Release; released holds stay in the audit.
 * Task 0071: a release is asked for with a reason and decided by another Business admin in Approvals.
 * The server holds one document at a time (task 0027), so a contract is held document by document; each refusal is
 * shown in the server's words with its code.
 */

const sameDay = (iso: string) => new Date(iso).toDateString() === new Date().toDateString()
const pages = (n: number | null) => (n == null ? '' : ` · ${n} page${n === 1 ? '' : 's'}`)

export function LegalHolds() {
  const { can, toast } = useStore()
  const [holds, setHolds] = useState<LegalHold[] | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [number, setNumber] = useState('')
  const [reason, setReason] = useState('')
  const [found, setFound] = useState<{ number: string; documents: HoldDocument[] } | null>(null)
  const [looking, setLooking] = useState(false)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [releasing, setReleasing] = useState<LegalHold | null>(null)
  const [why, setWhy] = useState('')
  const [waiting, setWaiting] = useState<HoldReleaseWaiting[]>([])

  useEffect(() => {
    let active = true
    activeHolds().then((h) => { if (active) { setHolds(h); setError(null) } }).catch((e) => { if (active) setError(loadFailed('Legal holds did not load', e)) })
    holdReleaseRequests().then((w) => { if (active) setWaiting(w) }).catch(() => { if (active) setWaiting([]) })
    return () => { active = false }
  }, [reload])

  // The documents of the contract, looked up as the number is typed (a short pause first).
  useEffect(() => {
    const value = number.trim()
    setFound(null); setProblem(null)
    if (!value) return
    let active = true
    const t = window.setTimeout(() => {
      setLooking(true)
      holdDocuments(value).then((d) => { if (active) setFound({ number: value, documents: d }) })
        .catch((e) => { if (active) setProblem(refusalText(e)) })
        .finally(() => { if (active) setLooking(false) })
    }, 350)
    return () => { active = false; window.clearTimeout(t) }
  }, [number, reload])

  if (!can('MANAGE_LEGAL_HOLD')) return <Callout tone="warn" title="Only a Business admin manages legal holds" />

  const waitingFor = (h: LegalHold) => waiting.find((w) => w.holdId === h.holdId)
  const toHold = found?.documents.filter((d) => !d.onHold) ?? []
  const already = (found?.documents.length ?? 0) - toHold.length
  const ready = !!found && toHold.length > 0 && reason.trim().length >= 3 && !busy

  const place = async () => {
    if (!ready || !found) return
    setBusy(true); setProblem(null)
    let placed = 0
    const refused: string[] = []
    for (const d of toHold) {
      try { await placeHold(d.documentId, reason.trim()); placed += 1 } catch (e) { refused.push(refusalText(e)) }
    }
    setBusy(false)
    if (placed) toast({ tone: 'ok', title: `Hold placed on ${placed} document${placed === 1 ? '' : 's'}`, detail: `Contract ${found.number}. The daily cleanup won't delete ${placed === 1 ? 'it' : 'them'}.` })
    if (refused.length) setProblem(`${refused.length} document${refused.length === 1 ? ' was' : 's were'} not held: ${[...new Set(refused)].join(' ')}`)
    else { setNumber(''); setReason('') }
    setReload((x) => x + 1)
  }
  const release = async (h: LegalHold) => {
    setBusy(true); setProblem(null)
    try {
      await requestHoldRelease(h.holdId, why.trim())
      toast({ tone: 'ok', title: 'Release requested', detail: `${h.primaryIdentifier ?? 'The document'}${pages(h.pageCount)} stays on hold until another Business admin approves it in Approvals.` })
      setReleasing(null)
    } catch (e) { setProblem(refusalText(e)); setReleasing(null) }
    finally { setBusy(false); setReload((x) => x + 1) }
  }

  return <>
    <section className="v-panel hold-form" aria-label="Put a document on hold">
      <span className="v-sec">Put a document on hold</span>
      <form className="hold-fields" onSubmit={(e) => { e.preventDefault(); void place() }}>
        <label className="v-field" style={{ width: 300 }}><span>Contract number</span>
          <input className="v-input mono" value={number} maxLength={300} inputMode="numeric" onChange={(e) => setNumber(e.target.value)} /></label>
        <label className="v-field" style={{ flex: 1, minWidth: 240 }}><span>Reason</span>
          <input className="v-input" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} placeholder="For example: Litigation, case 26-CV-0412" /></label>
        <button type="submit" className="v-btn primary" disabled={!ready}>{busy && !releasing ? 'Placing…' : 'Place hold'}</button>
      </form>
      {looking && <p className="faint">Looking for the contract…</p>}
      {found && !looking && (found.documents.length === 0
        ? <div className="hold-found warn" role="status"><AlertTriangle size={16} aria-hidden /><span>No documents have contract number <span className="mono">{found.number}</span></span></div>
        : <div className="hold-found" role="status"><Check size={16} strokeWidth={2.2} aria-hidden />
          <span>{toHold.length === 0 ? <>All {found.documents.length} documents on contract <span className="mono">{found.number}</span> are already on hold</>
            : <>{toHold.length} document{toHold.length === 1 ? '' : 's'} on contract <span className="mono">{found.number}</span> will be held{already ? ` (${already} already on hold)` : ''}</>}</span>
          <span className="faint" style={{ marginLeft: 'auto' }}>You won&apos;t see their titles or contents</span></div>)}
      {problem && <Callout tone="warn" title="Not done">{problem}</Callout>}
    </section>

    {error && !holds ? <FailureNotice failure={error} loadTitle="Legal holds didn't load" onRetry={() => setReload((x) => x + 1)} />
      : <section className="v-panel flush" aria-label="On hold now">
        <header className="hold-head"><h2>On hold now · {holds?.length ?? '…'}</h2><span className="faint" style={{ fontSize: 13 }}>Released holds stay in the audit</span></header>
        {holds && holds.length === 0 ? <p className="muted" style={{ padding: 16 }}>No document is on hold.</p> : holds &&
          <table className="v-table" aria-label="Legal holds in place"><thead><tr><th>Contract and document</th><th>Level</th><th>Received</th><th>Reason</th><th>Placed by</th><th>Since</th><th><span className="sr-only">Release</span></th></tr></thead>
            <tbody>{holds.map((h) => <tr key={h.holdId}>
              <td className="lead" data-label="Contract and document"><span className="mono">{h.primaryIdentifier ?? '—'}</span> <span className="faint" style={{ fontSize: 12 }}>{pages(h.pageCount)}</span></td>
              <td data-label="Level">{h.sensitivityLevel ? <LevelBadge level={h.sensitivityLevel} /> : '—'}</td>
              <td data-label="Received">{fmtDate(h.receivedAt)}</td>
              <td data-label="Reason">{h.reason}</td>
              <td data-label="Placed by">{h.placedByName?.trim() || 'A Business admin'}</td>
              <td data-label="Since" className="faint">{sameDay(h.placedAt) ? 'Today' : fmtDate(h.placedAt)}</td>
              <td className="act">{waitingFor(h) ? <span className="faint" title={`Asked by ${waitingFor(h)!.requestedBy}`}>Release requested</span>
                : <button type="button" className="v-btn sm" disabled={busy} onClick={() => { setWhy(''); setReleasing(h) }}>Request release</button>}</td>
            </tr>)}</tbody></table>}
      </section>}

    {releasing && <Dialog title="Ask to release this hold" onClose={() => { if (!busy) setReleasing(null) }} footer={<>
      <button type="button" className="v-btn" disabled={busy} onClick={() => setReleasing(null)}>Cancel</button>
      <button type="button" className="v-btn primary" disabled={busy || why.trim().length < 3} onClick={() => void release(releasing)}>{busy ? 'Sending…' : 'Send request'}</button>
    </>}>
      <p><span className="mono">{releasing.primaryIdentifier ?? '—'}</span>{pages(releasing.pageCount)}, on hold since {fmtDate(releasing.placedAt)}: “{releasing.reason}”.</p>
      <label className="v-field"><span>Why it can be released</span>
        <textarea className="v-input" rows={3} value={why} maxLength={500} onChange={(e) => setWhy(e.target.value)} placeholder="For example: Case closed" /></label>
      <p className="muted">Another Business admin decides it in Approvals; not the one who placed the hold. Once released, the daily cleanup can delete the document when its keeping rule says so, and a sealed original is destroyed.</p>
    </Dialog>}
  </>
}
