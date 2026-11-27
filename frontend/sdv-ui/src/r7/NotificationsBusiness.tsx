import { useEffect, useState } from 'react'
import { addBusinessRecipient, businessNotifications, businessRecipients, removeBusinessRecipient, saveBusinessNotificationEvent, saveEntityZone } from './liveApi'
import { FailureNotice, loadFailed, type Failure } from './CardError'
import { Panel } from './ui'
import { AddRecipient, EventsTable, RecipientsByRole, SwitchedOff, ZoneForm, isSwitchedOff } from './NotificationParts'
import { RECIPIENT_ROLES, chatDestinations, entityZoneWords, type BusinessNotifications, type Recipient } from './notificationWords'

// Task 0081: Business administration › Notifications, for the current entity. Which events go, how often, by e-mail and
// to which Teams or Slack channels; the entity's time zone; who gets e-mail, by role.

type Loaded = { view: BusinessNotifications; people: Recipient[] }

export function NotificationsBusiness() {
  const [data, setData] = useState<Loaded | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [off, setOff] = useState(false)
  const [reload, setReload] = useState(0)
  useEffect(() => {
    let live = true
    Promise.all([businessNotifications(), businessRecipients()])
      .then(([view, people]) => { if (live) { setData({ view, people }); setError(null); setOff(false) } })
      .catch((e) => { if (!live) return; if (isSwitchedOff(e)) setOff(true); else setError(loadFailed('Notifications did not load', e)) })
    return () => { live = false }
  }, [reload])
  if (off) return <SwitchedOff who="business" />
  if (error) return <FailureNotice failure={error} loadTitle="Notifications didn’t load" onRetry={() => setReload((n) => n + 1)} />
  if (!data) return <p className="muted">Loading…</p>
  const { view } = data
  const saveZone = async (z: string) => { const next = await saveEntityZone(z); setData((d) => (d ? { ...d, view: next } : d)) }
  return <section aria-label="Notifications" className="stack">
    <p className="muted">Messages say the event, counts, times and a link. An e-mail may name the record’s identifier, never for a Highly Confidential record; Teams and Slack never name it.</p>
    <Panel flush title="This entity’s events">
      <EventsTable label="This entity’s events" rows={view.events} chat={view.channelsConnected ? chatDestinations(view.channels) : null} onSave={saveBusinessNotificationEvent} />
      {!view.channelsConnected && <p className="faint ntf-foot">Teams and Slack aren’t connected yet. IT connects them first; until then messages go by e-mail only.</p>}
    </Panel>
    <Panel title="This entity’s time zone" description="Days and times in this entity’s messages and reports.">
      <ZoneForm label="Own time zone" current={entityZoneWords(view.ownZone, view.customerZone)} onSave={saveZone}
        extra={(busy, run) => view.ownZone ? <button type="button" className="v-btn sm" disabled={busy} onClick={() => run('')}>Follow the customer’s</button> : null} />
    </Panel>
    <Panel flush title="Who gets e-mail" description="Messages go to the people on the list of the role an event is for. Requesters are told the outcome of their request when they are on one of the lists.">
      <div className="ntf-lists">
        <RecipientsByRole people={data.people} onRemove={async (r) => { await removeBusinessRecipient(r.recipientId); setData((d) => (d ? { ...d, people: d.people.filter((x) => x.recipientId !== r.recipientId) } : d)) }} />
      </div>
      <div className="ntf-add">
        <AddRecipient roles={RECIPIENT_ROLES} onAdd={async (role, userId, email) => { const r = await addBusinessRecipient(role ?? '', userId, email); setData((d) => (d ? { ...d, people: [...d.people, r] } : d)) }} />
        <small className="faint">The user ID is the one Vero shows for the person, for example in Approvals.</small>
      </div>
    </Panel>
  </section>
}
