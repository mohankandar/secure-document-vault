import { useEffect, useState } from 'react'
import { licenceView, usageExport } from './liveApi'
import { exportFailureText, FailureNotice, loadFailed, type Failure } from './CardError'
import { licenceDay, licenceSentence, useAmount, useName, useNote, type LicenceView } from './licenceWords'
import { ApiRequestError } from '../api'
import { Callout, Panel } from './ui'

// Task 0075: the licence's state and this entity's use of it: Business administration, Licence and IT, About. IT also saves
// the usage file. Never a price.

export function Licence({ role }: { role: 'businessAdmin' | 'tenantIt' }) {
  const [data, setData] = useState<LicenceView | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  useEffect(() => {
    let live = true
    licenceView(role).then((x) => { if (live) { setData(x); setError(null) } }).catch((e) => { if (live) setError(loadFailed('The licence did not load', e)) })
    return () => { live = false }
  }, [role, reload])
  if (error) return <FailureNotice failure={error} loadTitle="The licence didn't load" onRetry={() => setReload((n) => n + 1)} />
  if (!data) return <p className="muted">Loading…</p>
  const s = licenceSentence(data)
  return <section aria-label="Licence" className="stack">
    <Callout tone={s.tone} title={s.title}>{s.text}</Callout>
    {data.clockBehind && <Callout tone="warn" title="The server’s clock is behind">{data.clockMessage} The licence’s dates count on. IT corrects the clock.</Callout>}
    <Panel title="Licence">
      <dl className="v-kv">
        <div><dt>Licence</dt><dd>{data.licenceId}, version {data.version}{data.testEnvironment ? ' (test environment)' : ''}</dd></div>
        <div><dt>Customer</dt><dd>{data.customer}</dd></div>
        <div><dt>Starts</dt><dd>{licenceDay(data.startsOn)}</dd></div>
        <div><dt>Ends</dt><dd>{data.expiresOn ? licenceDay(data.expiresOn) : 'No end date'}</dd></div>
        {data.updatesUntil && <div><dt>Updates until</dt><dd>{licenceDay(data.updatesUntil)}</dd></div>}
        {data.renewalStartsOn && <div><dt>Renewal starts</dt><dd>{licenceDay(data.renewalStartsOn)}</dd></div>}
        <div><dt>Entities</dt><dd>{data.entities}</dd></div>
      </dl>
    </Panel>
    <Panel flush title="This entity’s use this month">
      {data.usage.length === 0 ? <p className="muted" style={{ padding: 16 }}>The licence doesn’t list this entity.</p> :
      <table className="v-table" aria-label="Use this month"><thead><tr><th>What</th><th>Used</th><th>Note</th></tr></thead>
        <tbody>{data.usage.map((u) => <tr key={u.type}><td className="lead" data-label="What">{useName(u.type)}</td><td data-label="Used">{useAmount(u)}</td><td data-label="Note">{useNote(u) || '—'}</td></tr>)}</tbody></table>}
      <p className="faint" style={{ padding: '4px 16px 12px', margin: 0, fontSize: 13 }}>Counted, never stopped: work goes on over an allowance. Storage is counted once a day; seats are the month’s highest count.</p>
    </Panel>
    {role === 'tenantIt' && <UsageFile />}
  </section>
}

function UsageFile() {
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState<{ ok: boolean; text: string } | null>(null)
  const [askAgain, setAskAgain] = useState(false)
  const save = async (startAgain: boolean) => {
    setBusy(true); setNote(null)
    try {
      const url = URL.createObjectURL(await usageExport(startAgain))
      const link = document.createElement('a')
      link.href = url; link.download = `vero-usage-${new Date().toISOString().slice(0, 7)}.json`
      document.body.appendChild(link); link.click(); link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      setAskAgain(false); setNote({ ok: true, text: 'The file is saved. The export is recorded in the audit.' })
    } catch (e) {
      if (e instanceof ApiRequestError && e.code === 'SDV-LIC-003') setAskAgain(true)
      setNote({ ok: false, text: `The export didn’t work. ${exportFailureText('The usage export failed', e)}` })
    } finally { setBusy(false) }
  }
  return <Panel title="Usage file" actions={<button type="button" className="v-btn sm" disabled={busy} onClick={() => void save(false)}>{busy ? 'Saving…' : 'Export usage'}</button>}>
    <p className="muted">Numbers per entity and month for your account manager: no names and no documents. Each file goes on from the last one.</p>
    {note && <Callout tone={note.ok ? 'ok' : 'bad'} title={note.ok ? 'Saved' : 'Not saved'}>{note.text}</Callout>}
    {askAgain && <p><button type="button" className="v-btn sm" disabled={busy} onClick={() => void save(true)}>I told support: start a new series</button></p>}
  </Panel>
}
