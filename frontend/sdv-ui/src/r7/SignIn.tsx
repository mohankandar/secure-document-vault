import { useEffect, useState } from 'react'
import { roleApi } from '../api'
import { signInSummary } from '../auth'
import { identityOverview, type IdentityOverview, type OpsAuditEvent } from './liveApi'
import { FailureNotice, loadFailed, type Failure } from './CardError'
import { actorName } from './auditWords'
import { Banner, fmtDate } from './ui'

// Task 0012 Part F (finding 16, owner 2026-11-18): sign-in is set by a script the installer runs, never on screens.
// This tab only shows the sign-in in use. The backend draft/test/activate/rollback endpoints are left as they are.

const INSTALLER = 'Sign-in is set during installation by your installer. To change it, contact your Vero support team.'
const CHANGES = ['ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED', 'ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK']

/** The newest successful switch to a sign-in setting, for "by whom" (the connection itself doesn't keep a name). */
async function lastChange(): Promise<OpsAuditEvent | null> {
  const found = await Promise.all(CHANGES.map((action) =>
    roleApi<{ events: OpsAuditEvent[] }>(`/api/v1/operations/audit-events?limit=1&outcome=SUCCESS&action=${action}`, 'tenantIt').then((r) => r.events[0] ?? null)))
  return found.filter((e): e is OpsAuditEvent => !!e).sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))[0] ?? null
}

export function SignIn() {
  const [data, setData] = useState<IdentityOverview | null>(null)
  const [change, setChange] = useState<OpsAuditEvent | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  useEffect(() => {
    let live = true
    identityOverview().then((x) => { if (live) { setData(x); setError(null) } }).catch((e) => { if (live) setError(loadFailed('Sign-in did not load', e)) })
    lastChange().then((e) => { if (live) setChange(e) }).catch(() => { if (live) setChange(null) })
    return () => { live = false }
  }, [reload])

  if (error) return <FailureNotice failure={error} loadTitle="Sign-in didn't load" onRetry={() => setReload((n) => n + 1)} />
  if (!data) return <p className="muted">Loading…</p>
  const a = data.active
  const local = signInSummary().kind === 'local'
  // Task 0019: no board for Sign-in; it uses About's card (small capital labels in columns) and the banner.
  return <>
    <section className="v-panel about-facts" aria-label="Sign-in in use">
      {a ? <dl aria-label="Sign-in in use">
        <div><dt className="v-sec">Provider</dt><dd>{a.displayName}{a.providerType ? ` (${a.providerType})` : ''}</dd></div>
        <div><dt className="v-sec">Address</dt><dd className="mono">{a.issuerUri}</dd></div>
        <div><dt className="v-sec">Client ID</dt><dd className="mono">{a.browserClientId}</dd></div>
        <div><dt className="v-sec">Role groups</dt><dd>Set in the installer’s sign-in file; Vero doesn’t show them on screen yet.</dd></div>
        <div><dt className="v-sec">Last changed</dt><dd>{a.updatedAt ? fmtDate(a.updatedAt) : 'Not known'}{change ? ` by ${actorName(change)}` : ''}</dd></div>
      </dl>
      : <p style={{ padding: '18px 16px', margin: 0 }}>{local ? 'This computer uses the local test sign-in. No company sign-in is set up here.' : 'No company sign-in is in use yet.'}</p>}
    </section>
    <Banner tone="info" lead="Changing sign-in:">{INSTALLER}</Banner>
  </>
}
