import { useEffect, useState } from 'react'
import { ApiRequestError, getChosenEntity, setChosenEntity } from '../api'
import { RESTART_EVENT, isLocalSecurityMode } from '../auth'
import { activateFirstEntity, entitySwitched, firstStart, sessionEntities, type SessionEntities } from './liveApi'
import { firstStartProblem, firstStartRefusal } from './firstStartWords'

// Task 0041 (owner answer 5): the minimum entity choice. The person lands on the entity used last (kept in this
// browser); the chooser shows only when there is none, or it is no longer theirs; the account menu switches; every
// switch is audited by the server (ENTITY_SWITCHED). A server-side "last entity" comes later.

/** The sign-in's entities. A stored choice the server refuses (SDV-AUT-010) is forgotten once and asked again. */
export function useSessionEntities(): SessionEntities | null {
  const [entities, setEntities] = useState<SessionEntities | null>(null)
  useEffect(() => {
    let live = true
    const load = (retry: boolean) => sessionEntities().then((e) => { if (live) setEntities(e) }).catch((e) => {
      if (retry && e instanceof ApiRequestError && e.code === 'SDV-AUT-010' && getChosenEntity()) {
        setChosenEntity(null)
        void load(false)
      } else if (live) setEntities(null)
    })
    void load(true)
    return () => { live = false }
  }, [])
  return entities
}

/** Switch: remember the new entity, let the server audit it (the request names the new one), then start again in it. */
export function switchEntity(to: string, from: string | null) {
  if (to === from) return
  setChosenEntity(to)
  entitySwitched(from).catch(() => undefined).finally(restart)
}

/** Task 0065: the sign-in is in memory, so the app starts again without a reload (SignInGate gives it a new key, and
 * nothing of the old entity stays mounted). On the route's area; an open record or document belongs to the old entity. */
function restart() {
  if (isLocalSecurityMode()) window.location.reload()
  else window.dispatchEvent(new Event(RESTART_EVENT))
}

/** Shown instead of the app while no entity is chosen and more than one is open, or none is activated yet. */
export function EntityChooser({ entities }: { entities: SessionEntities }) {
  if (entities.entities.length === 0) return <FirstStartOrWait entities={entities} />
  return <div className="v-body">
    <h1 className="v-title">Choose an entity</h1>
    <p className="muted">You work in one entity at a time. You can switch later in the account menu.</p>
    <ul aria-label="Your entities">
      {entities.entities.map((e) => (
        <li key={e.entityId}><button type="button" className="v-btn" onClick={() => switchEntity(e.entityId, null)}>{e.name}</button></li>
      ))}
    </ul>
  </div>
}

/**
 * Task 0098: no entity is active. An IT Admin of a slot gets the first-start form (the server says which slots, and
 * answers 404 once any entity is active); everyone else is told who activates it.
 */
function FirstStartOrWait({ entities }: { entities: SessionEntities }) {
  const [slots, setSlots] = useState<string[] | null>(null)
  useEffect(() => {
    let live = true
    firstStart().then((f) => { if (live) setSlots(f.slots) }).catch(() => { if (live) setSlots([]) })
    return () => { live = false }
  }, [])
  if (slots === null) return <div className="v-body">Looking for the first start…</div>
  if (slots.length > 0) return <FirstStartForm slots={slots} />
  return <div className="v-body" role="status">
    <strong>No entity is activated yet.</strong>{' '}
    <span className="muted">{entities.activatableSlots.length > 0
      ? `IT activates the first entity on this screen; after that, as Business Admin you can activate ${entities.activatableSlots.join(', ')}.`
      : 'IT activates the first entity on this screen. Ask your IT Admin.'}</span>
  </div>
}

function FirstStartForm({ slots }: { slots: string[] }) {
  const [slot, setSlot] = useState(slots.length === 1 ? slots[0] : '')
  const [name, setName] = useState('')
  const [licence, setLicence] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<{ text: string; reference?: string } | null>(null)
  const problem = firstStartProblem(slot, name, licence)
  const choose = (file: File | undefined) => {
    setLicence(null)
    if (file) void file.text().then(setLicence).catch(() => setLicence(null))
  }
  const send = () => {
    if (busy || problem || !licence) return
    setBusy(true); setFailure(null)
    activateFirstEntity(slot, name.trim(), licence).then((done) => {
      setChosenEntity(done.entityId)
      restart()
    }).catch((e) => {
      setFailure({ text: firstStartRefusal(e instanceof ApiRequestError ? e.code : undefined), reference: e instanceof ApiRequestError ? e.correlationId : undefined })
      setBusy(false)
    })
  }
  return <div className="v-body">
    <h1 className="v-title">Activate the first entity</h1>
    <p className="muted">Vero is installed. As IT Admin, give the first entity its name and choose this install&apos;s licence file
      (the same file setup stored). This screen is shown once: after the first entity is active, it is gone.</p>
    {failure && <div className="v-callout bad" role="alert">{failure.text}{failure.reference ? ` Reference: ${failure.reference}.` : ''}</div>}
    <div className="stack" style={{ maxWidth: 520 }}>
      <label className="v-field"><span>Entity</span>
        <select className="v-input" value={slot} onChange={(e) => setSlot(e.target.value)}>
          {slots.length > 1 && <option value="">Choose…</option>}
          {slots.map((s) => <option key={s} value={s}>{s}</option>)}
        </select></label>
      <label className="v-field"><span>Name</span>
        <input className="v-input" value={name} maxLength={120} placeholder="For example: Acme Mortgage" onChange={(e) => setName(e.target.value)} /></label>
      <label className="v-field"><span>Licence file</span>
        <input type="file" accept=".json,application/json" aria-label="Licence file" onChange={(e) => choose(e.target.files?.[0])} />
      </label>
      {problem && <small className="muted">{problem}</small>}
      <div><button type="button" className="v-btn primary" disabled={busy || !!problem} onClick={send}>{busy ? 'Activating…' : 'Activate'}</button></div>
    </div>
  </div>
}
