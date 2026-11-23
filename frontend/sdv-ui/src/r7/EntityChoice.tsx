import { useEffect, useState } from 'react'
import { ApiRequestError, getChosenEntity, setChosenEntity } from '../api'
import { entitySwitched, sessionEntities, type SessionEntities } from './liveApi'

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
  entitySwitched(from).catch(() => undefined).finally(() => window.location.reload())
}

/** Shown instead of the app while no entity is chosen and more than one is open, or none is activated yet. */
export function EntityChooser({ entities }: { entities: SessionEntities }) {
  if (entities.entities.length === 0) {
    return <div className="v-body" role="status">
      <strong>No entity is activated yet.</strong>{' '}
      <span className="muted">{entities.activatableSlots.length > 0
        ? `As Business Admin you can activate ${entities.activatableSlots.join(', ')}. The activation screen comes in a later release; your installer can do it meanwhile.`
        : 'Ask your Business Admin to activate one.'}</span>
    </div>
  }
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
