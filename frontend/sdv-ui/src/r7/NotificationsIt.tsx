import { useEffect, useState } from 'react'
import { addItRecipient, connectChannels, itNotificationEvents, itNotifications, itRecipients, removeItRecipient, saveCustomerZone, saveItNotificationEvent } from './liveApi'
import { ActionError, FailureNotice, actionFailed, loadFailed, type Failure } from './CardError'
import { Callout, Panel, fmtDateTime } from './ui'
import { AddRecipient, EventsTable, RecipientTable, SwitchedOff, ZoneForm, isSwitchedOff } from './NotificationParts'
import { channelWords, chatDestinations, mailAuthWords, stateTone, appAddressNote, type Channels, type EventRow, type ItNotifications, type Recipient } from './notificationWords'

// Task 0081: IT › Notifications. The channels (mail, Teams, Slack: set in the install configuration, shown by the path of
// their secret, never a secret), the customer's time zone, the technical events and the IT Admins who get e-mail.

type Loaded = { view: ItNotifications; events: EventRow[]; people: Recipient[] }

export function NotificationsIt() {
  const [data, setData] = useState<Loaded | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [off, setOff] = useState(false)
  const [reload, setReload] = useState(0)
  useEffect(() => {
    let live = true
    Promise.all([itNotifications(), itNotificationEvents(), itRecipients()])
      .then(([view, events, people]) => { if (live) { setData({ view, events, people }); setError(null); setOff(false) } })
      .catch((e) => { if (!live) return; if (isSwitchedOff(e)) setOff(true); else setError(loadFailed('Notifications did not load', e)) })
    return () => { live = false }
  }, [reload])
  if (off) return <SwitchedOff who="it" />
  if (error) return <FailureNotice failure={error} loadTitle="Notifications didn’t load" onRetry={() => setReload((n) => n + 1)} />
  if (!data) return <p className="muted">Loading…</p>
  const setView = (view: ItNotifications) => setData((d) => (d ? { ...d, view } : d))
  return <section aria-label="Notifications" className="stack">
    <ChannelsPanel channels={data.view.channels} onSaved={setView} />
    <Panel title="Customer time zone" description="Days and times in messages and reports. Every entity without its own time zone follows this one.">
      <ZoneForm label="New time zone" current={data.view.customerZone ?? 'None set, so UTC'} onSave={async (z) => setView(await saveCustomerZone(z))} />
    </Panel>
    <Panel flush title="Technical notifications" description="Events for the whole customer: what goes, how often, and where.">
      <EventsTable label="Technical notifications" rows={data.events} chat={chatDestinations(data.view.channels.destinations)} onSave={saveItNotificationEvent} />
    </Panel>
    <Panel flush title="IT Admins who get e-mail" description="Technical notifications by e-mail go to the people on this list.">
      <RecipientTable label="IT Admins who get e-mail" people={data.people} onRemove={async (r) => { await removeItRecipient(r.recipientId); setData((d) => (d ? { ...d, people: d.people.filter((x) => x.recipientId !== r.recipientId) } : d)) }} />
      <div className="ntf-add">
        <AddRecipient onAdd={async (_role, userId, email) => { const r = await addItRecipient(userId, email); setData((d) => (d ? { ...d, people: [...d.people, r] } : d)) }} />
      </div>
    </Panel>
  </section>
}

function ChannelsPanel({ channels, onSaved }: { channels: Channels; onSaved: (v: ItNotifications) => void }) {
  // Task 0100 (VSEC-204): the app address is the install's (sdv.public-url); this screen only shows it and connects.
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<Failure | null>(null)
  const note = appAddressNote(channels)
  const connect = () => {
    if (busy || !channels.appAddress) return
    setBusy(true); setFailure(null)
    connectChannels().then(onSaved).catch((e) => setFailure(actionFailed('Connecting the channels failed', e))).finally(() => setBusy(false))
  }
  return <Panel title="Channels" description="Mail, Teams and Slack are set in the install configuration by IT (IT admin guide, Notifications). Secrets stay in the key service; this page shows only where they are kept.">
    <div className="stack">
      {note && <Callout tone="warn" title="Nothing is sent yet">{note}</Callout>}
      {failure && <ActionError title="The channels weren’t connected" failure={failure} />}
      <div className="row wrap ntf-form">
        <div className="v-field grow"><span>App address</span>
          <span className="mono">{channels.appAddress ?? 'Not set'}</span>
          <small className="faint">The address people open Vero on, from the install configuration (sdv.public-url). Links in messages are made under it.</small></div>
        {channels.appAddress && !channels.connected && <button type="button" className="v-btn sm" disabled={busy} onClick={connect}>{busy ? 'Connecting…' : 'Connect'}</button>}
      </div>
      <dl className="v-kv">
        <div><dt>Mail server</dt><dd>{channels.mailConfigured ? 'Set up' : 'Not set up'}</dd></div>
        {channels.mailConfigured && <div><dt>Mail sign-in</dt><dd>{mailAuthWords(channels.mailAuth)}</dd></div>}
        {channels.mailConfigured && <div><dt>Where the mail secret is kept</dt><dd className="mono">{channels.mailSecretPath ?? '—'}</dd></div>}
      </dl>
      {channels.destinations.length === 0 ? <p className="muted">No destinations are set up.</p> :
        <table className="v-table" aria-label="Destinations"><thead><tr><th>Destination</th><th>Channel</th><th>Where the secret is kept</th><th>State</th><th>Last attempt</th></tr></thead>
          <tbody>{channels.destinations.map((d) => <tr key={d.id}>
            <td className="lead mono" data-label="Destination">{d.id}</td>
            <td data-label="Channel">{channelWords(d.channel)}</td>
            <td data-label="Where the secret is kept" className="mono">{d.secretPath ?? '—'}</td>
            <td data-label="State"><span className={`v-status ${stateTone(d.state)}`}>{d.state}</span>
              {(d.reason || d.code) && <small className="faint ntf-note">{d.reason}{d.code ? ` (${d.code})` : ''}</small>}</td>
            <td data-label="Last attempt" className="faint">{fmtDateTime(d.lastAttempt)}</td>
          </tr>)}</tbody></table>}
    </div>
  </Panel>
}
