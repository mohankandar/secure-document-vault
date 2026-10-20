import { useEffect, useState } from 'react'
import { FileSearch, Moon, Sun, LogOut } from 'lucide-react'
import { Workspace } from './Workspace'
import { beginOidcLogin, endOidcSession, getAccessToken, handleOidcCallback, isLocalSecurityMode, OIDC_SESSION_EXPIRED_EVENT } from '../auth'
import { roleApi, type SessionInfo } from '../api'

const local=isLocalSecurityMode()
type Persona='business'|'steward'
function R7Shell(){
  const [persona,setPersona]=useState<Persona>('business')
  const [session,setSession]=useState<SessionInfo|null>(null)
  const [pending,setPending]=useState(true)
  const [error,setError]=useState('')
  const [theme,setTheme]=useState<'light'|'dark'>(()=>window.matchMedia?.('(prefers-color-scheme: dark)').matches?'dark':'light')
  useEffect(()=>{
    let active=true
    const load=async()=>{
      setPending(true);setSession(null);setError('')
      try{
        if(!local){await handleOidcCallback();if(!getAccessToken())return}
        const current=await roleApi<SessionInfo>('/api/v1/session',persona)
        if(active)setSession(current)
      }catch(e){if(active)setError(e instanceof Error?e.message:'Session verification failed')}
      finally{if(active)setPending(false)}
    }
    void load()
    return()=>{active=false}
  },[persona])
  useEffect(()=>{
    const expired=()=>{setSession(null);setError('Session expired. Please sign in again.')}
    window.addEventListener(OIDC_SESSION_EXPIRED_EVENT,expired)
    return()=>window.removeEventListener(OIDC_SESSION_EXPIRED_EVENT,expired)
  },[])
  const allowed=!!session?.capabilities.includes('SEARCH_RECORDS')&&(session.roles.includes('BUSINESS_USER')||session.roles.includes('DOCUMENT_STEWARD'))
  const viewerPersona:Persona=session?.roles.includes('DOCUMENT_STEWARD')?'steward':'business'
  return <div className={`r7 ${theme==='dark'?'dark':''}`}>
    <div className="v-app">
      <header className="v-top">
        <span className="v-brand">vero<i aria-hidden/></span>
        <div className="v-tenant"><strong>{session?.tenantDisplayName??'Secure workspace'}</strong><span>Document workspace</span></div>
        <div className="grow"/>
        <button className="v-btn quiet icon" type="button" aria-label="Toggle color theme" onClick={()=>setTheme(x=>x==='light'?'dark':'light')}>{theme==='light'?<Moon size={18}/>:<Sun size={18}/>}</button>
        {!local&&session&&<button className="v-btn quiet" onClick={()=>{setSession(null);void endOidcSession()}}><LogOut size={15}/>Sign out</button>}
        <span className="v-avatar" title={session?.subject??'Secure session'}>{(session?.subject??'V').slice(0,2).toUpperCase()}</span>
      </header>
      <nav className="v-rail" aria-label="Primary"><div className="v-rail-group">Work</div>
        {allowed&&<button type="button" className="v-rail-item" aria-current="page"><FileSearch size={20}/><span className="lbl">Records workspace</span></button>}
        {local&&<div className="v-rail-foot"><label className="v-rail-group" htmlFor="r7-local-role">Local validation role</label>
          <select id="r7-local-role" className="v-select" value={persona} onChange={e=>setPersona(e.target.value as Persona)}>
            <option value="business">Business User</option><option value="steward">Document Steward</option>
          </select></div>}
      </nav>
      <main className="v-main" id="main">
        {pending?<div className="v-body"><p role="status">Verifying secure session…</p></div>:
          error?<div className="v-body"><p role="alert">{error}</p>{!local&&<button className="v-btn primary" onClick={()=>void beginOidcLogin()}>Sign in again</button>}</div>:
          !session?<div className="v-body"><h1>Sign in required</h1>{!local&&<button className="v-btn primary" onClick={()=>void beginOidcLogin()}>Sign in</button>}</div>:
          !allowed?<div className="v-body"><div className="v-panel"><div className="v-panel-body"><h1>Access not assigned</h1><p>This first R7 release supports Business User and Document Steward roles only. Access is enforced by SDV backend permissions.</p></div></div></div>:
          <Workspace key={session.tenantId+':'+session.subject+':'+viewerPersona} persona={viewerPersona} capabilities={session.capabilities}/>}
      </main>
    </div>
  </div>
}
export function R7App(){return <R7Shell/>}
