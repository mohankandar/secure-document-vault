import { useEffect, useState } from 'react'
import { approvalLinkDecide, approvalLinkOpen } from './liveApi'
import { LINK_TOKEN_KEY, linkWords, type LinkView } from './approvalLinkWords'
import { Callout } from './ui'
import { exportFailureText } from './CardError'
import type { PendingRequest } from './liveApi'

// Task 0082: the request an Approve or Decline link from an e-mail is for, at the top of Approvals. It shows what the list
// shows for it and decides only with one more click; Vero then decides with all its checks, and the link is used up.

type Answer = LinkView & { request: PendingRequest | null }

export function ApprovalLink({ onDecided }: { onDecided: () => void }) {
  const [token, setToken] = useState<string | null>(() => window.sessionStorage.getItem(LINK_TOKEN_KEY))
  const [answer, setAnswer] = useState<Answer | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    if (!token) return
    let live = true
    approvalLinkOpen(token).then((a) => { if (live) setAnswer(a) }).catch((e) => { if (live) setFailure(exportFailureText('The link could not be opened', e)) })
    return () => { live = false }
  }, [token])
  const putAside = () => { window.sessionStorage.removeItem(LINK_TOKEN_KEY); setToken(null) }
  if (!token) return null
  if (failure) return <Callout tone="bad" title="This link can’t be used here.">{failure} <button type="button" className="v-btn sm" onClick={putAside}>Close</button></Callout>
  if (!answer) return <p className="muted">Opening the link from your e-mail…</p>
  const words = linkWords(answer)
  const decide = async () => {
    setBusy(true)
    try {
      const done = await approvalLinkDecide(token)
      setAnswer(done)
      window.sessionStorage.removeItem(LINK_TOKEN_KEY)
      onDecided()
    } catch (e) {
      setFailure(exportFailureText('The decision was not saved', e))
    } finally { setBusy(false) }
  }
  const r = answer.request
  return <section aria-label="Request from your e-mail" className="stack">
    <Callout tone={words.tone} title={words.title}>
      {answer.state === 'READY' && r && <dl className="v-kv">
        <div><dt>Asks to</dt><dd>{r.kind === 'PRINT' ? 'Print' : r.kind === 'DOWNLOAD' ? 'Download' : 'Lower the level of'} one document{r.primaryIdentifier ? ` of ${r.primaryIdentifier}` : ''}</dd></div>
        <div><dt>Asked by</dt><dd>{r.requester}, {new Date(r.requestedAt).toLocaleString()}</dd></div>
      </dl>}
      {answer.message && answer.state !== 'READY' && <p>{answer.message}</p>}
      {answer.state === 'READY'
        ? <p><button type="button" className="v-btn" disabled={busy} onClick={() => void decide()}>{answer.action === 'DECLINE' ? 'Decline' : 'Approve'}</button>{' '}
            <button type="button" className="v-btn sm" disabled={busy} onClick={putAside}>Not now</button></p>
        : <p><button type="button" className="v-btn sm" onClick={putAside}>Close</button></p>}
    </Callout>
  </section>
}
