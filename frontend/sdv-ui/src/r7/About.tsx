import { useEffect, useState } from 'react'
import { Copy } from 'lucide-react'
import { roleApi, type SessionInfo } from '../api'
import { signInSummary } from '../auth'
import { identityOverview, systemInfo, type IdentityOverview } from './liveApi'
import { FailureNotice, loadFailed, type Failure } from './CardError'
import { PRODUCT_NAME } from './brand'
import { Panel, fmtDateTime } from './ui'
import { fmtWhen } from './opsWords'

// Task 0012 Part D (change 10): one page for the facts support asks for, replacing Tenant IT › Environment and
// Platform operations › Configuration. "Copy details for support" holds no documents and no personal data.

type Info = Awaited<ReturnType<typeof systemInfo>> & { productCode?: string; correlationId?: string | null }

const hostOf = (url: string) => { try { return new URL(url).host } catch { return url } }

/** The sign-in in words. Tenant IT can read the connection in use; Platform operations sees only the address. */
function signInWords(identity: IdentityOverview | null): string {
  const s = signInSummary()
  if (s.kind === 'local') return 'Local test sign-in (this computer only)'
  if (identity?.active) return `${identity.active.displayName} (${hostOf(identity.active.issuerUri)})`
  return s.address ? `Through ${hostOf(s.address)}` : 'Your company’s sign-in'
}

export function About({ role }: { role: 'platform' | 'tenantIt' }) {
  const [info, setInfo] = useState<Info | null>(null)
  const [tenant, setTenant] = useState<string | null>(null)
  const [identity, setIdentity] = useState<IdentityOverview | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [copied, setCopied] = useState<'yes' | 'no' | null>(null)
  useEffect(() => {
    let live = true
    systemInfo(role).then((x) => { if (live) { setInfo(x as Info); setError(null) } }).catch((e) => { if (live) setError(loadFailed('About this installation did not load', e)) })
    roleApi<SessionInfo>('/api/v1/session', role).then((s) => { if (live) setTenant(s.tenantDisplayName) }).catch(() => { if (live) setTenant(null) })
    if (role === 'tenantIt') identityOverview().then((x) => { if (live) setIdentity(x) }).catch(() => { if (live) setIdentity(null) })
    return () => { live = false }
  }, [role, reload])

  if (error) return <FailureNotice failure={error} loadTitle="About this installation didn't load" onRetry={() => setReload((n) => n + 1)} />
  if (!info) return <p className="muted">Loading…</p>
  const facts: [string, string][] = [
    ['Product', `${PRODUCT_NAME} ${info.version}`],
    ['Status', info.status === 'UP' ? 'Working' : 'Not working fully. Platform operations › Health says what is wrong'],
    ['Organization', tenant ?? 'Not known'],
    ['Sign-in', signInWords(identity)],
    ['Checked', fmtWhen(info.timestamp)],
  ]
  const technical: [string, string][] = [
    ['Active profiles', info.activeProfiles.join(', ') || 'None'],
    ['Build', `${info.productCode ?? 'SDV'} ${info.version} (${info.productName})`],
    ['Correlation ID of this check', info.correlationId ?? '—'],
  ]
  const text = [
    `About this ${PRODUCT_NAME} installation`,
    ...facts.map(([k, v]) => `${k}: ${k === 'Checked' ? fmtDateTime(info.timestamp) : v}`),
    '',
    'Technical details',
    ...technical.map(([k, v]) => `${k}: ${v}`),
  ].join('\n')
  const copy = async () => {
    try { await navigator.clipboard.writeText(text); setCopied('yes') } catch { setCopied('no') }
    window.setTimeout(() => setCopied(null), 3000)
  }

  return <>
    <Panel title="About this installation">
      <dl className="v-kv cols" aria-label="Installation facts">{facts.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl>
    </Panel>
    <Panel title="Copy details for support" description="Copies these facts and the technical details below as plain text. No documents or personal data.">
      <div className="row" style={{ gap: 12 }}>
        <button type="button" className="v-btn" onClick={() => void copy()}><Copy size={14} aria-hidden />Copy details for support</button>
        {copied === 'yes' && <span role="status" className="muted">Copied</span>}
        {copied === 'no' && <span role="alert">This browser didn’t allow copying. Select the text under Technical details instead.</span>}
      </div>
    </Panel>
    <details className="st-tech"><summary>Technical details</summary>
      <dl className="v-kv">{technical.map(([k, v]) => <div key={k}><dt>{k}</dt><dd className="mono">{v}</dd></div>)}</dl>
      <pre className="pr-note" aria-label="Details for support">{text}</pre>
    </details>
  </>
}
