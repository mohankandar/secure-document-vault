import { useEffect, useState } from 'react'
import { Bell, X } from 'lucide-react'
import { useStore } from './store'
import { whenWords } from './Sensitivity'
import { LEVEL_NAMES, intakeNotices, myRequests, type IntakeNotice, type MyRequest } from './liveApi'

/**
 * Task 0030 (item 7): in-app notices for requesters. When a request is approved, declined or expires, the requester
 * sees a short notice the next time they open Vero, and a count on the viewer's Requests tab until they look at it.
 * Email, Teams and Slack come later through the shared notification module.
 *
 * What the person has already been told is kept in this browser, per person (localStorage), and compared with the
 * server's own times only (never this computer's clock). The first time Vero opens in a browser it starts from what
 * is there, so old decisions aren't announced as new.
 */

/** A decision the requester is told about: approved, declined, or expired with nobody deciding. */
export const isDecision = (r: MyRequest) => r.status === 'APPROVED' || r.status === 'DECLINED' || r.status === 'EXPIRED'
/** When it happened, by the server's clock: the decision, or the end of the waiting time. */
export const decisionTime = (r: MyRequest) => (r.status === 'EXPIRED' && !r.decidedAt ? r.expiresAt : r.decidedAt ?? r.expiresAt)

type Seen = { noticedUpTo: string; viewed: Record<string, string> }
const KEY = (subject: string) => 'vero.requests.seen.' + subject
const read = (subject: string): Seen | null => {
  try { const v = JSON.parse(window.localStorage.getItem(KEY(subject)) ?? 'null') as Seen | null; return v && typeof v.noticedUpTo === 'string' && v.viewed ? v : null } catch { return null }
}
const write = (subject: string, s: Seen) => { try { window.localStorage.setItem(KEY(subject), JSON.stringify(s)) } catch { /* storage unavailable: notices show again next time */ } }
const latest = (rs: MyRequest[], from: string) => rs.filter(isDecision).map(decisionTime).reduce((m, t) => (t > m ? t : m), from)
const SEEN_EVENT = 'vero-requests-seen'

/** First run in this browser: everything there now counts as seen. */
function seenOf(subject: string, requests: MyRequest[]): Seen {
  const s = read(subject)
  if (s) return s
  const start: Seen = { noticedUpTo: latest(requests, ''), viewed: Object.fromEntries(requests.filter(isDecision).map((r) => [r.requestId, r.status])) }
  write(subject, start)
  return start
}

/** Decisions the person hasn't looked at under Requests yet (each counts once per state it reaches). */
export function unseenDecisions(subject: string, requests: MyRequest[]): MyRequest[] {
  const s = seenOf(subject, requests)
  return requests.filter((r) => isDecision(r) && s.viewed[r.requestId] !== r.status)
}
/** The person looked at these under Requests: they no longer count. */
export function markViewed(subject: string, requests: MyRequest[]) {
  const s = seenOf(subject, requests)
  const fresh = requests.filter((r) => isDecision(r) && s.viewed[r.requestId] !== r.status)
  if (!fresh.length) return
  write(subject, { ...s, viewed: { ...s.viewed, ...Object.fromEntries(fresh.map((r) => [r.requestId, r.status])) } })
  window.dispatchEvent(new Event(SEEN_EVENT))
}
/** Re-renders when the person looks at their requests somewhere else on the page. */
export function useSeenRevision(): number {
  const [n, setN] = useState(0)
  useEffect(() => { const on = () => setN((x) => x + 1); window.addEventListener(SEEN_EVENT, on); return () => window.removeEventListener(SEEN_EVENT, on) }, [])
  return n
}

/** "Download on 300000000003 approved · use by tomorrow 18:54". */
export function noticeLine(r: MyRequest): string {
  const what = r.kind === 'PRINT' ? 'Print' : r.kind === 'DOWNLOAD' ? 'Download'
    : r.kind === 'LOWER_LEVEL' ? `Lower to ${r.requestedLevel ? LEVEL_NAMES[r.requestedLevel] : 'a lower level'}`
      : `Lower the whole contract to ${r.requestedLevel ? LEVEL_NAMES[r.requestedLevel] : 'a lower level'}`
  const where = r.primaryIdentifier ? ` on ${r.primaryIdentifier}` : ''
  if (r.status === 'APPROVED') return `${what}${where} approved${r.kind === 'PRINT' || r.kind === 'DOWNLOAD' ? ` · use by ${whenWords(r.expiresAt)}` : ''}`
  if (r.status === 'DECLINED') return `${what}${where} declined`
  return `${what}${where} expired: nobody decided in time`
}

/** The notice a requester sees when they open Vero, once per new decision. Stewards only (they have requests). */
export function RequestNotices() {
  const { can, liveSession } = useStore()
  const subject = liveSession?.subject ?? ''
  const requester = can('CHANGE_SENSITIVITY_LEVEL')
  const [news, setNews] = useState<MyRequest[]>([])
  useEffect(() => {
    if (!requester || !subject) return
    let active = true
    myRequests().then((rs) => {
      if (!active) return
      const s = seenOf(subject, rs)
      const fresh = rs.filter((r) => isDecision(r) && decisionTime(r) > s.noticedUpTo)
      if (fresh.length) { setNews(fresh); write(subject, { ...s, noticedUpTo: latest(fresh, s.noticedUpTo) }) }
    }).catch(() => { /* no notice: the Requests tab still shows every state */ })
    return () => { active = false }
  }, [requester, subject])
  if (!news.length) return null
  const shown = news.slice(0, 4)
  return (
    <section className="v-notice" role="status" aria-label="Your requests">
      <header><Bell size={16} aria-hidden /><strong className="grow">{news.length === 1 ? 'One of your requests was decided' : `${news.length} of your requests were decided`}</strong>
        <button type="button" className="v-btn quiet icon sm" aria-label="Dismiss" onClick={() => setNews([])}><X size={16} /></button></header>
      <ul>{shown.map((r) => <li key={r.requestId}><b>{noticeLine(r)}</b></li>)}</ul>
      {news.length > shown.length && <span className="faint">and {news.length - shown.length} more.</span>}
      <span className="faint">Open the document to see it under Requests.</span>
    </section>
  )
}

/**
 * Task 0032 (0): Document stewards are told when a sending system sent an uncorrected copy of a corrected document
 * (nothing was stored; only the corrected version is kept). From the server's last 30 days; each notice is shown once
 * per person in this browser. The first time Vero opens in a browser, nothing older is announced (as above).
 */
const INTAKE_KEY = (subject: string) => 'vero.intake.seen.' + subject
export function IntakeNotices() {
  const { can, liveSession } = useStore()
  const subject = liveSession?.subject ?? ''
  const steward = can('CHANGE_SENSITIVITY_LEVEL')
  const [news, setNews] = useState<IntakeNotice[]>([])
  useEffect(() => {
    if (!steward || !subject) return
    let active = true
    intakeNotices().then((ns) => {
      if (!active) return
      let seenUpTo: string | null = null
      try { seenUpTo = window.localStorage.getItem(INTAKE_KEY(subject)) } catch { /* storage unavailable */ }
      const latestAt = ns.reduce((m, x) => (x.receivedAt > m ? x.receivedAt : m), seenUpTo ?? '')
      const fresh = seenUpTo == null ? [] : ns.filter((x) => x.receivedAt > seenUpTo!)
      try { window.localStorage.setItem(INTAKE_KEY(subject), latestAt) } catch { /* shown again next time */ }
      if (fresh.length) setNews(fresh)
    }).catch(() => { /* no notice: nothing was stored either way */ })
    return () => { active = false }
  }, [steward, subject])
  if (!news.length) return null
  const shown = news.slice(0, 4)
  return (
    <section className="v-notice" role="status" aria-label="Sent again">
      <header><Bell size={16} aria-hidden /><strong className="grow">{news.length === 1 ? 'A corrected document was sent again' : `${news.length} corrected documents were sent again`}</strong>
        <button type="button" className="v-btn quiet icon sm" aria-label="Dismiss" onClick={() => setNews([])}><X size={16} /></button></header>
      <ul>{shown.map((x) => <li key={x.noticeId}><b>{x.primaryIdentifier ? `${x.primaryIdentifierLabel ?? 'Record'} ${x.primaryIdentifier}` : 'A record'}</b>{x.sendingSystem ? ` · ${x.sendingSystem}` : ''} · {whenWords(x.receivedAt)}</li>)}</ul>
      {news.length > shown.length && <span className="faint">and {news.length - shown.length} more.</span>}
      <span className="faint">{shown[0].message}.</span>
    </section>
  )
}
