import { useId, useState } from 'react'
import { AlertTriangle, ArrowRight, Check, CheckCircle2, ChevronDown, Circle, Copy, Loader2, Lock, Pencil, Plus, RotateCcw, XCircle } from 'lucide-react'
import { useStore } from './store'
import { fmtDateTime } from './ui'
import type { IdentityConnection, IdentityOverview } from './liveApi'
import './authentication.css'
type Phase = 'untested' | 'running' | 'passed' | 'failed' | 'stale'
type FieldKey = 'displayName' | 'providerId' | 'providerType' | 'issuerUri' | 'browserClientId' | 'apiAudience'
const FIELDS: { key: FieldKey; label: string; mono?: boolean }[] = [
{ key:'displayName',label:'Display name'},{key:'providerId',label:'Provider ID',mono:true},{key:'providerType',label:'Provider type'},
{key:'issuerUri',label:'Issuer URI',mono:true},{key:'browserClientId',label:'Browser client ID',mono:true},{key:'apiAudience',label:'API audience',mono:true}]
const changedFields=(a:IdentityConnection|null,b:IdentityConnection)=>FIELDS.filter(f=>!a||a[f.key]!==b[f.key])
const ageSeconds=(iso:string|null|undefined)=>{if(!iso)return Infinity;const t=Date.parse(iso);return Number.isFinite(t)&&t<=Date.now()?(Date.now()-t)/1000:Infinity}
const fmtRel=(v:string)=>{const delta=(Date.now()-Date.parse(v))/1000;return !Number.isFinite(delta)?'unknown':delta<90?'just now':delta<3600?`${Math.round(delta/60)} min ago`:delta<86400?`${Math.round(delta/3600)} h ago`:fmtDateTime(v)}
const windowLabel=(s:number)=>s%60===0?`${s/60} minutes`:`${s} seconds`
const PHASE_TEXT: Record<Phase,string>={untested:'Not tested',running:'Testing…',passed:'Test passed',failed:'Test failed',stale:'Test outside window'}
function phaseOf(staged:IdentityConnection,running:boolean,maxAge:number):Phase{if(running)return'running';if(staged.lastTestStatus==='FAILED')return'failed';if(staged.lastTestStatus==='PASSED')return ageSeconds(staged.lastTestedAt)>maxAge?'stale':'passed';return'untested'}
function Lifecycle({ auth, phase }: { auth: IdentityOverview; phase: Phase | null }) {
  const go = (id: string) => document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  const { active, staged, rollbackCandidates } = auth
  return (
    <nav className="au-life" aria-label="Identity configuration lifecycle">
      <button type="button" className="au-life-node live" onClick={() => go('au-live')}>
        <span className="au-pill live"><span className="dot" aria-hidden />Live</span>
        <span className="au-life-main"><strong>v{active!.configurationVersion}</strong> {active!.displayName}</span>
        <span className="au-life-sub">Serving sign-in</span>
      </button>
      <ArrowRight size={16} className="au-life-arrow" aria-hidden />
      <button type="button" className={`au-life-node draft${staged ? '' : ' none'}`} onClick={() => go('au-staged')}>
        <span className="au-pill draft">Draft · not live</span>
        <span className="au-life-main">{staged ? <><strong>v{staged.configurationVersion}</strong> {staged.displayName}</> : 'No staged draft'}</span>
        <span className="au-life-sub">{staged && phase ? <PhaseInline phase={phase} /> : 'Start one to change settings'}</span>
      </button>
      <span className="au-life-gap" aria-hidden />
      <button type="button" className="au-life-node rec" onClick={() => go('au-recovery')}>
        <span className="au-pill rec"><RotateCcw size={12} aria-hidden />Recovery</span>
        <span className="au-life-main">{rollbackCandidates.length ? `${rollbackCandidates.length} known-good target${rollbackCandidates.length > 1 ? 's' : ''}` : 'No rollback target'}</span>
        <span className="au-life-sub">{rollbackCandidates.length ? `Latest v${rollbackCandidates[0].configurationVersion}` : 'Recorded after an activation'}</span>
      </button>
    </nav>
  )
}

function PhaseIcon({ phase, size = 16 }: { phase: Phase; size?: number }) {
  if (phase === 'passed') return <CheckCircle2 size={size} className="au-ic ok" aria-hidden />
  if (phase === 'failed') return <XCircle size={size} className="au-ic bad" aria-hidden />
  if (phase === 'stale') return <AlertTriangle size={size} className="au-ic warn" aria-hidden />
  if (phase === 'running') return <Loader2 size={size} className="au-ic spin" aria-hidden />
  return <Circle size={size} className="au-ic idle" aria-hidden />
}
const PhaseInline = ({ phase }: { phase: Phase }) => <span className="row" style={{ gap: 6 }}><PhaseIcon phase={phase} size={14} />{PHASE_TEXT[phase]}</span>

/* ---------- Live binding ---------- */

function CopyValue({ value, label }: { value: string; label: string }) {
  const { toast } = useStore()
  return (
    <span className="au-copy">
      <span className="mono au-val">{value}</span>
      <button type="button" className="v-btn quiet icon sm" aria-label={`Copy ${label}`} title={`Copy ${label}`}
        onClick={() => { void navigator.clipboard?.writeText(value).catch(() => undefined); toast({ title: `${label} copied` }) }}><Copy size={14} aria-hidden /></button>
    </span>
  )
}

function LiveBinding({ conn }: { conn: IdentityConnection }) {
  return (
    <section id="au-live" className="au-card live" aria-labelledby="au-live-h">
      <header className="au-card-head">
        <div className="grow">
          <div className="row wrap" style={{ gap: 10 }}>
            <h2 id="au-live-h">Live binding</h2>
            <span className="au-pill live"><span className="dot" aria-hidden />Live · serving sign-in</span>
          </div>
          <p>The configuration the server trusts for sign-in right now. Saving or testing a draft never changes it.</p>
        </div>
      </header>
      <div className="au-live-lead">
        <span className="au-ver" aria-label={`Configuration version ${conn.configurationVersion}`}>v{conn.configurationVersion}</span>
        <div className="grow">
          <div className="au-live-name">{conn.displayName}</div>
          <div className="faint au-live-meta">{conn.providerType} · <span className="mono">{conn.providerId}</span> · updated {conn.updatedAt ? fmtDateTime(conn.updatedAt) : '—'}</div>
        </div>
      </div>
      <dl className="au-grid">
        <div className="wide"><dt>Issuer URI</dt><dd><CopyValue value={conn.issuerUri} label="issuer URI" /></dd></div>
        <div><dt>Browser client ID</dt><dd><CopyValue value={conn.browserClientId} label="browser client ID" /></dd></div>
        <div><dt>API audience</dt><dd><CopyValue value={conn.apiAudience} label="API audience" /></dd></div>
        <div className="wide"><dt>Connection ID</dt><dd><CopyValue value={conn.connectionId} label="connection ID" /></dd></div>
      </dl>
    </section>
  )
}

function StagedSection({ active, staged, phase, maxAge, activationSupported, onEdit, onStart, onTest, onActivate }: {
  active: IdentityConnection; staged: IdentityConnection | null; phase: Phase | null; maxAge: number; activationSupported: boolean
  onEdit: () => void; onStart: () => void; onTest: () => void; onActivate: () => void
}) {
  return (
    <section id="au-staged" className="au-card draft" aria-labelledby="au-staged-h">
      <header className="au-card-head">
        <div className="grow">
          <div className="row wrap" style={{ gap: 10 }}>
            <h2 id="au-staged-h">Staged draft</h2>
            <span className="au-pill draft">Draft · not live</span>
          </div>
          <p>Prepare a change here. It only affects sign-in after a passing test and a confirmed activation.</p>
        </div>
        {staged && <button type="button" className="v-btn sm" onClick={onEdit}><Pencil size={14} aria-hidden />Edit draft</button>}
      </header>
      {!staged || !phase ? (
        <div className="au-nodraft">
          <div className="grow">
            <h3>No staged configuration</h3>
            <p className="muted">Sign-in continues to use live v{active.configurationVersion}. Start a draft from the live settings, change what you need, then test it.</p>
          </div>
          <button type="button" className="v-btn primary" onClick={onStart}><Plus size={16} aria-hidden />Start draft from live</button>
        </div>
      ) : (
        <div className="au-staged-body">
          <Comparison active={active} staged={staged} />
          <Promotion staged={staged} phase={phase} maxAge={maxAge} activationSupported={activationSupported} onTest={onTest} onEdit={onEdit} onActivate={onActivate} />
        </div>
      )}
    </section>
  )
}

function Comparison({ active, staged }: { active: IdentityConnection; staged: IdentityConnection }) {
  const changed = changedFields(active, staged)
  const [showAll, setShowAll] = useState(changed.length === 0)
  const rows = showAll ? FIELDS : changed
  const hidden = FIELDS.length - changed.length
  return (
    <div className="au-diff-wrap">
      <div className="au-sub-head">
        <h3>Changes from live</h3>
        <span className="faint">{changed.length === 0 ? 'Identical to live' : `${changed.length} of ${FIELDS.length} fields differ`}</span>
      </div>
      <table className="au-diff">
        <caption className="sr-only">Live configuration v{active.configurationVersion} compared with draft v{staged.configurationVersion}</caption>
        <thead><tr><th scope="col">Field</th><th scope="col"><span className="au-th live">Live v{active.configurationVersion}</span></th><th scope="col"><span className="au-th draft">Draft v{staged.configurationVersion}</span></th></tr></thead>
        <tbody>
          {rows.map((f) => {
            const diff = active[f.key] !== staged[f.key]
            return (
              <tr key={f.key} className={diff ? 'changed' : ''}>
                <th scope="row">{f.label}{diff && <span className="au-tag">Changed</span>}</th>
                <td data-label={`Live v${active.configurationVersion}`}><span className={f.mono ? 'mono' : ''}>{active[f.key]}</span></td>
                <td data-label={`Draft v${staged.configurationVersion}`}><span className={f.mono ? 'mono' : ''}>{staged[f.key]}</span></td>
              </tr>
            )
          })}
        </tbody>
      </table>
      {hidden > 0 && changed.length > 0 && (
        <button type="button" className="v-btn quiet sm au-more" aria-expanded={showAll} onClick={() => setShowAll((v) => !v)}>
          <ChevronDown size={14} aria-hidden style={{ transform: showAll ? 'rotate(180deg)' : undefined }} />{showAll ? 'Hide unchanged fields' : `Show ${hidden} unchanged field${hidden > 1 ? 's' : ''}`}
        </button>
      )}
      <p className="faint au-foot">Connection ID <span className="mono">{staged.connectionId}</span></p>
    </div>
  )
}

function Promotion({ staged, phase, maxAge, activationSupported, onTest, onEdit, onActivate }: {
  staged: IdentityConnection; phase: Phase; maxAge: number; activationSupported: boolean; onTest: () => void; onEdit: () => void; onActivate: () => void
}) {
  const reasonId = useId()
  const v = staged.configurationVersion
  const canRequest = activationSupported && phase === 'passed'
  const blocked = !activationSupported ? 'Activation is not supported in this environment.'
    : phase === 'untested' ? 'Activation needs a passing connection test of this exact version.'
    : phase === 'running' ? 'Waiting for the test to finish.'
    : phase === 'failed' ? 'The last test failed. Fix the draft and test again.' : null

  return (
    <div className="au-promo">
      <div className="au-sub-head"><h3>Promote v{v} to live</h3></div>
      <ol className="au-steps">
        <li className="done">
          <span className="au-step-ic" aria-hidden><Check size={14} /></span>
          <div className="au-step-body">
            <div className="au-step-title">Draft saved</div>
            <p>v{v} saved {staged.updatedAt ? fmtRel(staged.updatedAt) : ''}. Live sign-in is unaffected.</p>
          </div>
        </li>
        <li className={phase === 'passed' ? 'done' : phase === 'failed' ? 'fail' : phase === 'stale' ? 'warn' : 'current'} aria-live="polite">
          <span className="au-step-ic" aria-hidden><PhaseIcon phase={phase} size={14} /></span>
          <div className="au-step-body">
            <div className="au-step-title">Connection test <span className="au-step-state">· {PHASE_TEXT[phase]}</span></div>
            {phase === 'untested' && <p>Checks that the issuer publishes a valid OIDC discovery document.</p>}
            {phase === 'running' && <p>Requesting the discovery document…</p>}
            {(phase === 'passed' || phase === 'stale' || phase === 'failed') && (
              <p>{staged.lastTestedAt ? `Tested ${fmtRel(staged.lastTestedAt)} · ${fmtDateTime(staged.lastTestedAt)}` : 'Test time not reported'}</p>
            )}
            {phase === 'failed' && staged.lastTestMessage && <div className="au-msg bad"><span className="mono">{staged.lastTestMessage}</span></div>}
            {phase === 'failed' && <p>Check the issuer URI for typos and that the provider is reachable, then test again.</p>}
            {phase === 'stale' && <p>Older than the {windowLabel(maxAge)} the server accepts. Re-run the test before activating, or the server will refuse.</p>}
            {phase === 'passed' && <p>A pass reflects that moment only. The server checks freshness again when you activate.</p>}
            <div className="row wrap au-step-actions">
              <button type="button" className={`v-btn sm${phase === 'untested' ? ' primary' : ''}`} onClick={onTest} disabled={phase === 'running'}>
                {phase === 'running' ? <><Loader2 size={14} className="spin" aria-hidden />Testing…</> : phase === 'untested' ? 'Run connection test' : 'Re-run test'}
              </button>
              {phase === 'failed' && <button type="button" className="v-btn sm" onClick={onEdit}><Pencil size={14} aria-hidden />Edit draft</button>}
            </div>
          </div>
        </li>
        <li className={canRequest ? 'current' : 'locked'}>
          <span className="au-step-ic" aria-hidden>{canRequest ? <ArrowRight size={14} /> : <Lock size={13} />}</span>
          <div className="au-step-body">
            <div className="au-step-title">Activate v{v}</div>
            <p id={reasonId}>{blocked ?? 'Eligible to request. You will review the change and confirm before anything is sent.'}</p>
            <div className="row wrap au-step-actions">
              <button type="button" className="v-btn sm primary" disabled={!canRequest} aria-describedby={reasonId} onClick={onActivate}>Review activation…</button>
            </div>
          </div>
        </li>
      </ol>
      <p className="faint au-foot">The server is the authority: it re-checks the exact version, test freshness and your permission when you confirm.</p>
    </div>
  )
}

function diffChips(active: IdentityConnection, c: IdentityConnection) {
  const out: string[] = []
  if (c.issuerUri !== active.issuerUri) out.push('Different issuer')
  if (c.browserClientId !== active.browserClientId) out.push('Different client')
  if (c.apiAudience !== active.apiAudience) out.push('Different audience')
  return out
}

function Recovery({ active, candidates, pick, onPick, onReview }: { active: IdentityConnection; candidates: IdentityConnection[]; pick: string | null; onPick: (id: string) => void; onReview: (c: IdentityConnection) => void }) {
  const selected = candidates.find((c) => c.connectionId === pick) ?? null
  return (
    <section id="au-recovery" className="au-card rec" aria-labelledby="au-rec-h">
      <header className="au-card-head">
        <div className="grow">
          <div className="row wrap" style={{ gap: 10 }}>
            <h2 id="au-rec-h">Known-good rollback</h2>
            <span className="au-pill rec"><RotateCcw size={12} aria-hidden />Recovery</span>
          </div>
          <p>Use this when live sign-in is failing. Only configurations the server has recorded as previously live and known-good are listed. Drafts can&apos;t be rolled back to.</p>
        </div>
      </header>
      {candidates.length === 0 ? (
        <div className="au-rec-empty">
          <h3>No rollback target recorded</h3>
          <p className="muted">A configuration becomes a known-good target after another version replaces it. Until then, recover a failing binding with your organisation&apos;s break-glass procedure.</p>
        </div>
      ) : (
        <>
          <fieldset className="au-cands">
            <legend className="sr-only">Choose the exact rollback target</legend>
            {candidates.map((c) => {
              const chips = diffChips(active, c)
              return (
                <label key={c.connectionId} className={`au-cand${pick === c.connectionId ? ' on' : ''}`}>
                  <input type="radio" name="au-rollback" value={c.connectionId} checked={pick === c.connectionId} onChange={() => onPick(c.connectionId)} />
                  <div className="grow" style={{ minWidth: 0 }}>
                    <div className="row wrap" style={{ gap: 8 }}>
                      <span className="au-ver sm">v{c.configurationVersion}</span>
                      <strong className="au-cand-name">{c.displayName}</strong>
                      {chips.map((x) => <span key={x} className="au-tag neutral">{x}</span>)}
                    </div>
                    <dl className="au-cand-kv">
                      <div><dt>Issuer</dt><dd className="mono">{c.issuerUri}</dd></div>
                      <div><dt>Browser client</dt><dd className="mono">{c.browserClientId}</dd></div>
                      <div><dt>Connection ID</dt><dd className="mono">{c.connectionId}</dd></div>
                      <div><dt>Last updated</dt><dd>{c.updatedAt ? fmtDateTime(c.updatedAt) : '—'}</dd></div>
                    </dl>
                  </div>
                </label>
              )
            })}
          </fieldset>
          <div className="au-rec-foot">
            <span className="faint">{selected ? `Selected v${selected.configurationVersion} · replaces live v${active.configurationVersion}` : 'Select one exact target to continue.'}</span>
            <button type="button" className="v-btn" disabled={!selected} onClick={() => selected && onReview(selected)}><RotateCcw size={15} aria-hidden />{selected ? `Review rollback to v${selected.configurationVersion}…` : 'Review rollback…'}</button>
          </div>
        </>
      )}
    </section>
  )
}


export function ConnectedAuthenticationView({auth,busy,onEdit,onTest,onActivate,onRollback}:{auth:IdentityOverview;busy:boolean;onEdit:()=>void;onTest:()=>void;onActivate:()=>void;onRollback:(target:IdentityConnection)=>void}){
 const [rollbackPick,setRollbackPick]=useState<string|null>(null)
 const active=auth.active
 const phase=auth.staged?phaseOf(auth.staged,busy,auth.activationMaxTestAgeSeconds):null
 if(!active)return <div className="au"><section className="au-card"><p className="muted">No active identity connection was returned by the backend.</p></section></div>
 return <div className="au">
 <Lifecycle auth={auth} phase={phase}/>
 <LiveBinding conn={active}/>
 <StagedSection active={active} staged={auth.staged} phase={phase} maxAge={auth.activationMaxTestAgeSeconds} activationSupported={auth.activationSupported} onEdit={onEdit} onStart={onEdit} onTest={onTest} onActivate={onActivate}/>
 <Recovery active={active} candidates={auth.rollbackCandidates} pick={rollbackPick} onPick={setRollbackPick} onReview={onRollback}/>
 </div>
}
