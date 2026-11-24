import { useEffect, useState, type ReactNode } from 'react'
import {
  OIDC_SESSION_EXPIRED_EVENT, RESTART_EVENT, SignInProblem, completeSignIn, hasSignIn, isLocalSecurityMode, shouldStay, startSignIn, stayHere,
} from '../auth'
import { PRODUCT_NAME } from './brand'
import { signInProblemWords } from './signInWords'

/**
 * Task 0065: nothing of the app starts before the person is signed in. Local test sign-in (development builds) passes
 * straight through. When a sign-in ends, the app stays as it is under a notice, so nothing typed disappears unread.
 */
type Phase = { at: 'working' } | { at: 'in' } | { at: 'out'; code: string | null }

export function SignInGate({ children }: { children: (epoch: number) => ReactNode }) {
  const local = isLocalSecurityMode()
  const [phase, setPhase] = useState<Phase>(local ? { at: 'in' } : { at: 'working' })
  const [ended, setEnded] = useState<{ fresh: boolean } | null>(null)
  const [epoch, setEpoch] = useState(0)

  useEffect(() => {
    const on = () => setEpoch((n) => n + 1)
    window.addEventListener(RESTART_EVENT, on)
    return () => window.removeEventListener(RESTART_EVENT, on)
  }, [])

  useEffect(() => {
    if (local) return
    let live = true
    const onEnded = (e: Event) => {
      const d = (e as CustomEvent<{ fresh: boolean; signedOut?: boolean }>).detail
      if (d?.signedOut) { setEnded(null); setPhase({ at: 'out', code: null }) } else setEnded({ fresh: !!d?.fresh })
    }
    window.addEventListener(OIDC_SESSION_EXPIRED_EVENT, onEnded)
    const start = async () => {
      if (hasSignIn() || await completeSignIn()) { if (live) setPhase({ at: 'in' }); return }
      if (shouldStay()) { if (live) setPhase({ at: 'out', code: null }); return }
      await startSignIn()
    }
    start().catch((e) => {
      stayHere() // a problem never starts a loop
      if (live) setPhase({ at: 'out', code: e instanceof SignInProblem ? e.code : 'NETWORK' })
    })
    return () => { live = false; window.removeEventListener(OIDC_SESSION_EXPIRED_EVENT, onEnded) }
  }, [local])

  const again = () => {
    setPhase((p) => (p.at === 'in' ? p : { at: 'working' }))
    startSignIn().catch((e) => { setEnded(null); setPhase({ at: 'out', code: e instanceof SignInProblem ? e.code : 'NETWORK' }) })
  }

  if (phase.at === 'working') return <div className="r7"><div className="v-body" role="status">Connecting to {PRODUCT_NAME}…</div></div>
  if (phase.at === 'out') {
    const words = phase.code ? signInProblemWords(phase.code) : null
    return <div className="r7"><div className="v-body access-page">
      <div className="v-panel access-card"><div className="v-panel-body stack">
        <h1 className="card-title">{words ? words.title : `You are signed out of ${PRODUCT_NAME}`}</h1>
        {words ? <p className="muted" role="alert">{words.text}{phase.code && phase.code !== 'NETWORK' ? ` (${phase.code})` : ''}</p>
          : <p className="muted">Your company sign-in may still be open on this computer. Close the browser if others use it.</p>}
        <div className="row wrap" style={{ gap: 10 }}><button type="button" className="v-btn primary" onClick={again}>Sign in to {PRODUCT_NAME}</button></div>
      </div></div>
    </div></div>
  }
  return <>
    {children(epoch)}
    {ended && <div className="r7">
      <div className="v-scrim v-dialog-scrim" aria-hidden />
      <div className="v-dialog" role="alertdialog" aria-modal="true" aria-label="Your sign-in has ended">
        <header className="v-drawer-head"><h2 className="grow">{ended.fresh ? 'Your sign-in was not accepted' : 'Your sign-in has ended'}</h2></header>
        <div className="v-drawer-body"><p>{ended.fresh
          ? 'Your company sign-in worked, but Vero did not accept it. If it happens again, tell your IT team. (SDV-AUT-003)'
          : 'Sign in again to go on. You come back to this page; what you typed and did not save is lost then.'}</p></div>
        <footer className="v-drawer-foot"><button type="button" className="v-btn primary" onClick={again}>Sign in again</button></footer>
      </div>
    </div>}
  </>
}
