import { useState, type ReactNode } from 'react'
import { ApiRequestError } from '../api'
import { ActionError, actionFailed, type Failure } from './CardError'
import { Callout, fmtDateTime } from './ui'
import {
  SWITCHED_OFF, allowedFrequencies, byRole, changeOf, channelWords, draftProblem, frequencyName, hasChange, roleListWords, validDailyAt, validEmail, validZone,
  type Destination, type EventChange, type EventRow, type Frequency, type Recipient,
} from './notificationWords'

// Task 0081: the pieces both Notifications screens share (IT and Business administration).

/** SDV-NTF-007 is not a failure: notifications are switched off here, or the entity isn't set up. */
export const isSwitchedOff = (e: unknown) => e instanceof ApiRequestError && e.code === SWITCHED_OFF

export function SwitchedOff({ who }: { who: 'it' | 'business' }) {
  return <Callout title="Notifications aren’t available here">
    Notifications are switched off in this installation, or this entity isn’t set up for them yet. {who === 'it' ? 'They are turned on in the install configuration (IT admin guide, Notifications).' : 'Ask your IT team.'}
  </Callout>
}

/**
 * The events and how each goes. chat: the Teams and Slack channels an event can go to; null when they aren't connected
 * (no channel choice is shown).
 */
export function EventsTable({ rows, chat, onSave, label }: { rows: EventRow[]; chat: Destination[] | null; onSave: (id: string, change: EventChange) => Promise<EventRow>; label: string }) {
  if (rows.length === 0) return <p className="muted" style={{ padding: 16 }}>There are no events here.</p>
  return <table className="v-table ntf-events" aria-label={label}>
    <thead><tr><th>Event</th><th>On</th><th>How often</th><th>By e-mail</th>{chat && <th>Teams and Slack</th>}<th><span className="sr-only">Save</span></th></tr></thead>
    <tbody>{rows.map((r) => <EventLine key={r.id} row={r} chat={chat} onSave={onSave} />)}</tbody>
  </table>
}

function EventLine({ row, chat, onSave }: { row: EventRow; chat: Destination[] | null; onSave: (id: string, change: EventChange) => Promise<EventRow> }) {
  const [saved, setSaved] = useState(row)
  const [draft, setDraft] = useState(row)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<Failure | null>(null)
  const change = changeOf(saved, draft)
  const problem = draftProblem(draft)
  const set = (patch: Partial<EventRow>) => { setDraft((d) => ({ ...d, ...patch })); setFailure(null) }
  const setFrequency = (f: Frequency) => set({ frequency: f, dailyAt: f === 'ONCE_A_DAY' ? (draft.dailyAt ?? '08:00') : draft.dailyAt })
  const save = async () => {
    if (busy || problem || !hasChange(change)) return
    setBusy(true); setFailure(null)
    try { const next = await onSave(row.id, change); setSaved(next); setDraft(next) } catch (e) { setFailure(actionFailed('Saving a notification setting failed', e)) } finally { setBusy(false) }
  }
  const off = !draft.enabled
  return <tr>
    <td className="lead" data-label="Event">
      <span style={{ fontWeight: 500 }}>{row.name}</span>
      {row.critical && <small className="faint ntf-note">Always on: something is down.</small>}
      {row.withIdentifier && <small className="faint ntf-note">E-mail names the record; Teams and Slack don’t.</small>}
      {failure && <ActionError title="The setting wasn’t saved" failure={failure} />}
      {!failure && problem && <small className="ntf-problem">{problem}</small>}
    </td>
    <td data-label="On">
      <label className="v-check"><input type="checkbox" checked={draft.enabled} disabled={row.critical || busy} onChange={(e) => set({ enabled: e.target.checked })} /><span>{draft.enabled ? 'On' : 'Off'}</span></label>
    </td>
    <td data-label="How often">
      <div className="row wrap" style={{ gap: 6 }}>
        <select className="v-select ntf-often" aria-label={`How often: ${row.name}`} value={draft.frequency} disabled={off || busy} onChange={(e) => setFrequency(e.target.value as Frequency)}>
          {allowedFrequencies(row.critical).map((f) => <option key={f} value={f}>{frequencyName(f)}</option>)}
        </select>
        {draft.frequency === 'ONCE_A_DAY' && <label className="row" style={{ gap: 6 }}><span className="faint">at</span>
          <input className={`v-input ntf-time${validDailyAt(draft.dailyAt) ? '' : ' invalid'}`} aria-label={`Time of day: ${row.name}`} placeholder="08:00" maxLength={5} value={draft.dailyAt ?? ''} disabled={off || busy} onChange={(e) => set({ dailyAt: e.target.value })} /></label>}
      </div>
    </td>
    <td data-label="By e-mail">
      <label className="v-check"><input type="checkbox" checked={draft.email} disabled={off || busy} onChange={(e) => set({ email: e.target.checked })} /><span>E-mail</span></label>
    </td>
    {chat && <td data-label="Teams and Slack">
      {chat.length === 0 ? <span className="faint">None set up</span> : <div className="stack" style={{ gap: 4 }}>
        {chat.map((c) => <label key={c.id} className="v-check"><input type="checkbox" checked={draft.channels.includes(c.id)} disabled={off || busy}
          onChange={(e) => set({ channels: e.target.checked ? [...draft.channels, c.id] : draft.channels.filter((x) => x !== c.id) })} /><span>{channelWords(c.channel)}: <span className="mono">{c.id}</span></span></label>)}
      </div>}
    </td>}
    <td data-label="Save">
      <button type="button" className="v-btn sm" disabled={busy || !!problem || !hasChange(change)} onClick={() => void save()}>{busy ? 'Saving…' : 'Save'}</button>
    </td>
  </tr>
}

/** One time zone: what it is now, a field and Save. extra: further buttons (Follow the customer's). */
export function ZoneForm({ label, current, onSave, extra }: { label: string; current: string; onSave: (zone: string) => Promise<void>; extra?: (busy: boolean, run: (zone: string) => void) => ReactNode }) {
  const [zone, setZone] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<Failure | null>(null)
  const run = (z: string) => {
    if (busy) return
    setBusy(true); setFailure(null)
    onSave(z).then(() => setZone('')).catch((e) => setFailure(actionFailed('Saving the time zone failed', e))).finally(() => setBusy(false))
  }
  const typed = zone.trim()
  return <div className="stack">
    <dl className="v-kv"><div><dt>Now</dt><dd>{current}</dd></div></dl>
    {failure && <ActionError title="The time zone wasn’t saved" failure={failure} />}
    <div className="row wrap ntf-form">
      <label className="v-field grow"><span>{label}</span><input className={`v-input mono${typed && !validZone(typed) ? ' invalid' : ''}`} value={zone} maxLength={64} placeholder="For example: America/New_York" onChange={(e) => setZone(e.target.value)} />
        <small className="faint">A name from the time zone list, such as Europe/London or America/New_York.</small></label>
      <button type="button" className="v-btn sm" disabled={busy || !validZone(typed)} onClick={() => run(typed)}>{busy ? 'Saving…' : 'Save'}</button>
      {extra?.(busy, run)}
    </div>
  </div>
}

/** A list of people who get e-mail, with Remove. */
export function RecipientTable({ people, onRemove, label }: { people: Recipient[]; onRemove: (r: Recipient) => Promise<void>; label: string }) {
  const [busy, setBusy] = useState<string | null>(null)
  const [failure, setFailure] = useState<Failure | null>(null)
  const remove = (r: Recipient) => {
    if (busy) return
    setBusy(r.recipientId); setFailure(null)
    onRemove(r).catch((e) => setFailure(actionFailed('Removing a person from the list failed', e))).finally(() => setBusy(null))
  }
  return <>
    {failure && <ActionError title="The person wasn’t removed" failure={failure} />}
    {people.length === 0 ? <p className="muted ntf-empty">No one is on this list yet.</p> :
      <table className="v-table" aria-label={label}><thead><tr><th>User ID</th><th>E-mail</th><th>Added</th><th><span className="sr-only">Remove</span></th></tr></thead>
        <tbody>{people.map((r) => <tr key={r.recipientId}>
          <td className="lead mono" data-label="User ID">{r.userId}</td>
          <td data-label="E-mail">{r.email}</td>
          <td data-label="Added" className="faint">{fmtDateTime(r.addedAt)} by <span className="mono">{r.addedBy}</span></td>
          <td data-label="Remove"><button type="button" className="v-btn sm" disabled={!!busy} onClick={() => remove(r)}>{busy === r.recipientId ? 'Removing…' : 'Remove'}</button></td>
        </tr>)}</tbody></table>}
  </>
}

/** The lists by role (Business administration). */
export function RecipientsByRole({ people, onRemove }: { people: Recipient[]; onRemove: (r: Recipient) => Promise<void> }) {
  return <div className="stack">{byRole(people).map((g) => <section key={g.role} aria-label={roleListWords(g.role)} className="stack" style={{ gap: 6 }}>
    <h3 className="card-title">{roleListWords(g.role)}</h3>
    <RecipientTable people={g.people} onRemove={onRemove} label={roleListWords(g.role)} />
  </section>)}</div>
}

/** Add a person: user ID and e-mail, and for Business administration the role whose list it is. */
export function AddRecipient({ roles, onAdd }: { roles?: readonly string[]; onAdd: (role: string | null, userId: string, email: string) => Promise<void> }) {
  const [role, setRole] = useState<string>(roles?.[0] ?? '')
  const [userId, setUserId] = useState('')
  const [email, setEmail] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<Failure | null>(null)
  const ready = userId.trim() !== '' && validEmail(email) && (!roles || roles.includes(role))
  const add = () => {
    if (busy || !ready) return
    setBusy(true); setFailure(null)
    onAdd(roles ? role : null, userId.trim(), email.trim()).then(() => { setUserId(''); setEmail('') }).catch((e) => setFailure(actionFailed('Adding a person to the list failed', e))).finally(() => setBusy(false))
  }
  return <div className="stack">
    {failure && <ActionError title="The person wasn’t added" failure={failure} />}
    <div className="row wrap ntf-form">
      {roles && <label className="v-field"><span>List</span><select className="v-select" value={role} onChange={(e) => setRole(e.target.value)}>
        {roles.map((r) => <option key={r} value={r}>{roleListWords(r)}</option>)}</select></label>}
      <label className="v-field grow"><span>User ID</span><input className="v-input mono" value={userId} maxLength={200} onChange={(e) => setUserId(e.target.value)} /></label>
      <label className="v-field grow"><span>E-mail</span><input className={`v-input${email.trim() && !validEmail(email) ? ' invalid' : ''}`} type="email" value={email} maxLength={320} onChange={(e) => setEmail(e.target.value)} /></label>
      <button type="button" className="v-btn sm" disabled={busy || !ready} onClick={add}>{busy ? 'Adding…' : 'Add'}</button>
    </div>
  </div>
}
