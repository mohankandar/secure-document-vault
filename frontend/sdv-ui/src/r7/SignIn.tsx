import { useEffect, useState } from 'react'
import { signInSummary } from '../auth'
import { identityOverview, testSignIn, type IdentityOverview } from './liveApi'
import { FailureNotice, loadFailed, type Failure } from './CardError'
import { Banner } from './ui'
import { checkedWords, codeWords, outcomeWords, type SignInTestAnswer } from './signInTestWords'

// Task 0012 Part F (finding 16, owner 2026-11-18): sign-in is set by a script the installer runs, never on screens.
// This tab only shows the sign-in in use. Task 0041 (owner answer 6): the installer's setup command loads it into
// platform-core's login connection; Vero offers no draft, test or switch, and the tab stays read-only.
// Task 0057 (LV-16 item 2): one exception that changes nothing: "Test sign-in" asks platform-core's identity module to
// check the connection in use (discovery document, keys, issuer and, with a company sign-in, the audience).

const INSTALLER = 'Sign-in is set during installation by your installer. To change it, contact your Vero support team.'

export function SignIn() {
  const [data, setData] = useState<IdentityOverview | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [testing, setTesting] = useState(false)
  const [result, setResult] = useState<SignInTestAnswer | null>(null)
  const [testError, setTestError] = useState<Failure | null>(null)
  useEffect(() => {
    let live = true
    identityOverview().then((x) => { if (live) { setData(x); setError(null) } }).catch((e) => { if (live) setError(loadFailed('Sign-in did not load', e)) })
    return () => { live = false }
  }, [reload])

  async function runTest() {
    setTesting(true); setTestError(null)
    try { setResult(await testSignIn()) } catch (e) { setResult(null); setTestError(loadFailed('The test sign-in did not run', e)) } finally { setTesting(false) }
  }

  if (error) return <FailureNotice failure={error} loadTitle="Sign-in didn't load" onRetry={() => setReload((n) => n + 1)} />
  if (!data) return <p className="muted">Loading…</p>
  const a = data.active
  const local = signInSummary().kind === 'local'
  // Task 0019: no board for Sign-in; it uses About's card (small capital labels in columns) and the banner.
  return <>
    <section className="v-panel about-facts" aria-label="Sign-in in use">
      {a ? <dl aria-label="Sign-in in use">
        <div><dt className="v-sec">Provider</dt><dd>{a.providerId}{a.providerType ? ` (${a.providerType})` : ''}</dd></div>
        <div><dt className="v-sec">Address</dt><dd className="mono">{a.issuerUri}</dd></div>
        <div><dt className="v-sec">Client ID</dt><dd className="mono">{a.browserClientId}</dd></div>
        <div><dt className="v-sec">Role groups</dt><dd>Set in the installer’s role mapping file; Vero doesn’t show them on screen yet.</dd></div>
        <div><dt className="v-sec">Version</dt><dd>{a.configurationVersion}, set by the installer’s setup</dd></div>
      </dl>
      : <p style={{ padding: '18px 16px', margin: 0 }}>{local ? 'This computer uses the local test sign-in. No company sign-in is set up here.' : 'No company sign-in is in use yet.'}</p>}
    </section>
    {a && <section className="v-panel" aria-label="Test sign-in" style={{ padding: '14px 16px', marginTop: 12 }}>
      <p style={{ margin: '0 0 10px' }}>Check that Vero can reach this login system and read its keys. The test changes nothing.</p>
      <button type="button" className="v-btn sm" disabled={testing} onClick={() => void runTest()}>{testing ? 'Testing…' : 'Test sign-in'}</button>
      {testError && <div style={{ marginTop: 10 }}><FailureNotice failure={testError} loadTitle="The test sign-in did not run" onRetry={() => void runTest()} /></div>}
      {result && <TestResult result={result} />}
    </section>}
    <Banner tone="info" lead="Changing sign-in:">{INSTALLER}</Banner>
  </>
}

function TestResult({ result }: { result: SignInTestAnswer }) {
  const { lead, tone } = outcomeWords(result)
  const code = codeWords(result)
  return <div role="status" aria-label="Test sign-in result" style={{ marginTop: 10 }}>
    <Banner tone={tone} lead={lead}>{result.message}</Banner>
    {code && <p style={{ margin: '6px 0 0' }}>Code: <span className="mono">{code}</span></p>}
    {result.outcome !== 'NO_CONNECTION' && <p className="muted" style={{ margin: '6px 0 0' }}>{checkedWords(result.tokenChecked)}</p>}
    <p className="muted" style={{ margin: '6px 0 0' }}>Tested {new Date(result.testedAt).toLocaleString()}{result.connectionId ? `, connection version ${result.configurationVersion}` : ''}.</p>
  </div>
}
