import { useCallback, useEffect, useState } from 'react'
import { diagnosticBundle, serviceChecks, type ServiceCheck } from './liveApi'
import { exportFailureText, FailureNotice, loadFailed, type Failure } from './CardError'
import { CHECK_STATE, checkName } from './checkWords'
import { Callout, fmtDateTime } from './ui'

// Task 0074: what Vero depends on, each with its state, its code and what to do; and the diagnostic bundle for support.
// Platform operations, Health and IT, Problems show the same list.

export function Checks({ role, at }: { role: 'platform' | 'tenantIt'; /** Changes when the page is checked again. */ at?: string | null }) {
  const [checks, setChecks] = useState<ServiceCheck[] | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [saving, setSaving] = useState(false)
  const [note, setNote] = useState<{ ok: boolean; text: string } | null>(null)
  const load = useCallback(() => serviceChecks(role).then((r) => { setChecks(r.checks); setError(null) }).catch((e) => setError(loadFailed('The checks did not load', e))), [role])
  useEffect(() => { void load() }, [load, at])
  const save = async () => {
    setSaving(true); setNote(null)
    try {
      const url = URL.createObjectURL(await diagnosticBundle(role))
      const link = document.createElement('a')
      link.href = url; link.download = `vero-diagnostics-${new Date().toISOString().slice(0, 10)}.zip`
      document.body.appendChild(link); link.click(); link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      setNote({ ok: true, text: 'The file is saved. The download is recorded in the audit.' })
    } catch (e) {
      setNote({ ok: false, text: `The download didn’t work. ${exportFailureText('The diagnostic bundle failed', e)}` })
    } finally { setSaving(false) }
  }
  return <section className="v-panel flush" aria-label="Checks">
    <div className="row between wrap" style={{ padding: '12px 16px 4px', gap: 8 }}>
      <div>
        <h3 className="v-sec" style={{ margin: 0 }}>Checks</h3>
        <p className="faint" style={{ margin: '4px 0 0', fontSize: 13 }}>What Vero depends on. Vero checks these by itself, most of them every minute.</p>
      </div>
      <button type="button" className="v-btn sm" disabled={saving} onClick={() => void save()}>{saving ? 'Saving…' : 'Download diagnostic bundle'}</button>
    </div>
    {note && <div style={{ padding: '0 16px 8px' }}><Callout tone={note.ok ? 'ok' : 'bad'} title={note.ok ? 'Saved' : 'Not saved'}>{note.text}</Callout></div>}
    {error && <FailureNotice failure={error} loadTitle="The checks didn't load" onRetry={() => void load()} />}
    {!checks && !error && <p className="muted" style={{ padding: 16 }}>Loading…</p>}
    {checks && checks.length === 0 && <p className="muted" style={{ padding: 16 }}>Not checked yet. Check again in a minute.</p>}
    {checks && checks.length > 0 && <table className="v-table" aria-label="Checks list"><thead><tr><th>Check</th><th>State</th><th>Why</th><th>What to do</th><th>Code</th></tr></thead>
      <tbody>{checks.map((c) => { const st = CHECK_STATE[c.state] ?? CHECK_STATE.DOWN; return (
        <tr key={c.name}>
          <td className="lead" data-label="Check"><strong style={{ fontWeight: 500 }}>{checkName(c.name)}</strong></td>
          <td data-label="State" title={`Checked ${fmtDateTime(c.checkedAt)}`}><span className={`v-dot ${st.dot}`} aria-hidden />{st.word}</td>
          <td data-label="Why">{c.cause ?? '—'}</td>
          <td data-label="What to do">{c.fix ?? 'Nothing'}</td>
          <td data-label="Code" className="mono faint">{c.state === 'WORKING' ? '—' : c.code}</td>
        </tr>) })}</tbody></table>}
    <p className="faint" style={{ padding: '4px 16px 12px', margin: 0, fontSize: 13 }}>The diagnostic bundle is a zip for support: versions, settings without secrets, these checks and the last error codes. It holds no document, name or value.</p>
  </section>
}
