import React, { useEffect, useState } from 'react'
import ReactDOM from 'react-dom/client'
import { Building2, FileSearch, LogOut, Moon, ServerCog, ShieldCheck, Sun } from 'lucide-react'
import { LiveWorkspace } from './LiveWorkspace'
import { beginOidcLogin, endOidcSession, getAccessToken, handleOidcCallback, isLocalSecurityMode, OIDC_SESSION_EXPIRED_EVENT } from '../auth'
import { roleApi, type LocalPersona, type SessionInfo } from '../api'
import './r7.css'
import '../business-workspace.css'
import '../styles.css'
import '../r7-live.css'

type Area = 'workspace' | 'governance' | 'tenant' | 'operations'
const areas: { id: Area; title: string; Icon: typeof FileSearch; capability: string }[] = [
  { id: 'workspace', title: 'Records workspace', Icon: FileSearch, capability: 'SEARCH_RECORDS' },
  { id: 'governance', title: 'Business administration', Icon: ShieldCheck, capability: 'TENANT_AUTH_MANAGE' },
  { id: 'tenant', title: 'Tenant IT', Icon: Building2, capability: 'TENANT_AUTH_MANAGE' },
  { id: 'operations', title: 'Platform operations', Icon: ServerCog, capability: 'VIEW_OPERATIONS' },
]
const local = isLocalSecurityMode()

function NativeR7() {
  const [persona, setPersona] = useState<LocalPersona>('business')
  const [session, setSession] = useState<SessionInfo | null>(null)
  const [pending, setPending] = useState(true)
  const [error, setError] = useState('')
  const [area, setArea] = useState<Area>('workspace')
  const [theme, setTheme] = useState<'light' | 'dark'>('light')

  useEffect(() => {
    let active = true
    const start = async () => {
      setPending(true); setError(''); setSession(null)
      try {
        if (!local) {
          await handleOidcCallback()
          if (!getAccessToken()) return
        }
        const current = await roleApi<SessionInfo>('/api/v1/session', persona)
        if (active) setSession(current)
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : 'Could not verify session')
      } finally { if (active) setPending(false) }
    }
    void start()
    return () => { active = false }
  }, [persona])

  useEffect(() => {
    const expired = () => { setSession(null); setError('Session expired. Sign in again.') }
    window.addEventListener(OIDC_SESSION_EXPIRED_EVENT, expired)
    return () => window.removeEventListener(OIDC_SESSION_EXPIRED_EVENT, expired)
  }, [])

  const allowed = (item: Area) => {
    if (!session) return false
    if (item === 'workspace') return session.capabilities.includes('SEARCH_RECORDS')
    if (item === 'governance') return session.roles.some(r => ['BUSINESS_ADMIN', 'PLATFORM_ADMIN'].includes(r))
    if (item === 'tenant') return session.roles.some(r => ['TENANT_IT_ADMIN', 'PLATFORM_ADMIN'].includes(r))
    return session.roles.some(r => r.includes('PLATFORM'))
  }
  const contentPersona: 'business' | 'steward' = session?.roles.includes('DOCUMENT_STEWARD') ? 'steward' : 'business'
  return <div className={`r7 native-r7 ${theme === 'dark' ? 'dark' : ''}`}>
    <div className="v-app">
      <header className="v-top">
        <span className="v-brand">vero<i aria-hidden /></span>
        <div className="v-tenant"><strong>{session?.tenantDisplayName || 'Secure workspace'}</strong><span>Enterprise document platform</span></div>
        <div className="grow" />
        <button className="v-btn quiet icon" type="button" onClick={() => setTheme(t => t === 'light' ? 'dark' : 'light')} aria-label="Toggle theme">{theme === 'light' ? <Moon size={18}/> : <Sun size={18}/>}</button>
        {!local && session && <button className="v-btn quiet" type="button" onClick={() => { setSession(null); void endOidcSession() }}><LogOut size={16}/>Sign out</button>}
        <span className="v-avatar" title={session?.subject || 'Not signed in'}>{(session?.subject || 'V').slice(0,2).toUpperCase()}</span>
      </header>
      <nav className="v-rail" aria-label="Primary">
        <div className="v-rail-group">Work</div>
        {areas.map(({id,title,Icon}) => allowed(id) && <button key={id} type="button" className="v-rail-item" aria-current={area === id ? 'page' : undefined} onClick={() => setArea(id)}>
          <Icon size={19} aria-hidden/><span className="lbl">{title}</span>
        </button>)}
        {local && <div className="v-rail-foot"><label className="v-rail-group" htmlFor="r7-persona">Local validation role</label>
          <select id="r7-persona" className="v-select" value={persona} onChange={e => { setArea('workspace'); setPersona(e.target.value as LocalPersona) }}>
            <option value="business">Business User</option><option value="steward">Document Steward</option>
            <option value="businessAdmin">Business Administrator</option><option value="tenantIt">Tenant IT Administrator</option><option value="platform">Platform Operations</option>
          </select></div>}
      </nav>
      <main className="v-main" id="main">
        {pending ? <div className="v-body"><p role="status">Verifying secure session…</p></div> :
          error ? <div className="v-body"><p role="alert">{error}</p></div> :
          !session ? <div className="v-body"><h1>Sign in required</h1>{!local && <button className="v-btn primary" onClick={() => void beginOidcLogin()}>Sign in</button>}</div> :
          !allowed(area) ? <div className="v-body"><h1>Access not assigned</h1><p>Access is enforced by the server.</p></div> :
          area === 'workspace' ? <LiveWorkspace key={`${session.tenantId}:${session.subject}:${persona}`} persona={contentPersona} capabilities={session.capabilities}/> :
          <div className="v-body"><div className="v-panel"><div className="v-panel-body"><h1>{areas.find(x => x.id === area)?.title}</h1><p>This area is being connected to authenticated backend services. No simulated records or actions are available.</p></div></div></div>}
      </main>
    </div>
  </div>
}

export function mount(root: HTMLElement) {
  document.documentElement.classList.add('r7-html')
  document.title = 'Vero'
  ReactDOM.createRoot(root).render(<React.StrictMode><NativeR7/></React.StrictMode>)
}
