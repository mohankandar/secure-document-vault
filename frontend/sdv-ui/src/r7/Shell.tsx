import { useEffect, useMemo, useRef, useState } from 'react'
import { Activity, Building2, FileSearch, FlaskConical, Layers, Moon, Search, ServerCog, ShieldCheck, Sun, CornerDownLeft, PanelLeftOpen, PanelLeftClose } from 'lucide-react'
import { AREA_TABS, StoreProvider, useStore } from './store'
import { type AreaId } from './data'
import { opSummary } from './liveApi'
import { Drawer, Toasts } from './ui'
import { localRoleSwitchEnabled, setLocalTestPersona, type LocalPersona } from '../api'
import { Workspace } from './Workspace'
import { Governance } from './Governance'
import { TenantAdmin } from './TenantAdmin'
import { Operations } from './Operations'
import { DesignSystem } from './DesignSystem'

const showDevelopmentUtilities = import.meta.env.DEV

const AREA_META: Record<AreaId, { label: string; short: string; Icon: typeof Search; group: string }> = {
  workspace: { label: 'Records workspace', short: 'Records', Icon: FileSearch, group: 'Work' },
  governance: { label: 'Business administration', short: 'Governance', Icon: ShieldCheck, group: 'Administration' },
  tenant: { label: 'Tenant IT', short: 'Tenant IT', Icon: Building2, group: 'Administration' },
  operations: { label: 'Platform operations', short: 'Operations', Icon: ServerCog, group: 'Operations' },
  system: { label: 'Design system', short: 'System', Icon: Layers, group: 'Prototype' },
}

function useMediaQuery(q: string) {
  const [m, setM] = useState(() => window.matchMedia(q).matches)
  useEffect(() => {
    const mq = window.matchMedia(q)
    const on = () => setM(mq.matches)
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [q])
  return m
}

function Rail({ collapsed, onToggle, onNavigated, onOpenSession }: { collapsed: boolean; onToggle: () => void; onNavigated: () => void; onOpenSession: () => void }) {
  const { role, route, navigate } = useStore()
  const [incidentCount,setIncidentCount]=useState<number|null>(null)
  const ToggleIcon = collapsed ? PanelLeftOpen : PanelLeftClose
  const go = (a: AreaId) => { navigate(a); onNavigated() }
  useEffect(()=>{
    if(!role.areas.includes('operations'))return
    let live=true
    opSummary().then(s=>{if(live){const counts=[s.processingFailureCount,s.stuckDocumentCount,s.attentionSubmissionCount];setIncidentCount(counts.every(x=>typeof x==='number')?counts.reduce<number>((sum,x)=>sum+(x??0),0):null)}}).catch(()=>{if(live)setIncidentCount(null)})
    return()=>{live=false}
  },[role.id])
  return (
    <nav className="v-rail" id="primary-nav" aria-label="Primary" data-collapsed={collapsed}>
      <div className="v-rail-group">{AREA_META[role.areas[0]].group}</div>
      {role.areas.map((a) => {
        const { label, Icon } = AREA_META[a]
        const attention = a === 'operations' ? incidentCount : null
        return (
          <button key={a} type="button" className="v-rail-item" aria-current={route.area === a ? 'page' : undefined} onClick={() => go(a)} title={label} aria-label={label} data-tip={label}>
            <Icon size={20} aria-hidden /><span className="lbl">{label}</span>
            {attention != null && attention > 0 && <><span className="count" aria-label={`${attention} need attention`}>{attention}</span><span className="v-rail-dot" aria-hidden /></>}
          </button>
        )
      })}
      <div className="v-rail-foot">
        {showDevelopmentUtilities && <>
          <div className="v-rail-group">Prototype</div>
          <button type="button" className="v-rail-item" aria-current={route.area === 'system' ? 'page' : undefined} onClick={() => go('system')} title="Design system" aria-label="Design system" data-tip="Design system"><Layers size={20} aria-hidden /><span className="lbl">Design system</span></button>
          <button type="button" className="v-rail-item" onClick={onOpenSession} title="Local test session" aria-label="Local test session" data-tip="Local test session"><FlaskConical size={20} aria-hidden /><span className="lbl">Session & scenarios</span></button>
        </>}
        <button type="button" className="v-rail-item v-rail-toggle" onClick={onToggle} aria-expanded={!collapsed} aria-controls="primary-nav" aria-label={collapsed ? "Expand navigation" : "Collapse navigation"} data-tip={collapsed ? "Expand navigation" : "Collapse navigation"}><ToggleIcon size={20} aria-hidden /><span className="lbl" aria-hidden>Collapse</span><kbd className="v-rail-kbd" aria-hidden>[</kbd></button>
      </div>
    </nav>
  )
}

function BottomNav({ onOpenSession, onOpenPalette }: { onOpenSession: () => void; onOpenPalette: () => void }) {
  const { role, route, navigate } = useStore()
  const area = role.areas[0]
  const { short, Icon } = AREA_META[area]
  return (
    <nav className="v-bottom" aria-label="Primary">
      <button type="button" aria-current={route.area === area ? 'page' : undefined} onClick={() => navigate(area)}><Icon size={22} aria-hidden />{short}</button>
      <button type="button" onClick={onOpenPalette}><Search size={22} aria-hidden />Find</button>
      {showDevelopmentUtilities && <button type="button" aria-current={route.area === 'system' ? 'page' : undefined} onClick={() => navigate('system')}><Layers size={22} aria-hidden />System</button>}
      <button type="button" onClick={onOpenSession}><FlaskConical size={22} aria-hidden />Session</button>
    </nav>
  )
}

function Palette({ onClose }: { onClose: () => void }) {
  const { role, navigate, records, can, setSelectedRef } = useStore()
  const [q, setQ] = useState('')
  const [active, setActive] = useState(0)
  const items = useMemo(() => {
    const dest = [...role.areas, ...(showDevelopmentUtilities ? ['system' as AreaId] : [])].flatMap((a) => AREA_TABS[a].map((t) => ({ key: `${a}/${t.id}`, label: t.label, hint: AREA_META[a].label, run: () => navigate(a, t.id) })))
    const recs = can('SEARCH_RECORDS') ? records.map((r) => ({ key: r.ref, label: `${r.identifiers[0].value} · ${r.title}`, hint: 'Record', run: () => { navigate('workspace'); setSelectedRef(r.ref) } })) : []
    const needle = q.trim().toLowerCase()
    return [...dest, ...recs].filter((i) => !needle || `${i.label} ${i.hint}`.toLowerCase().includes(needle)).slice(0, 12)
  }, [q, role.areas, records, can, navigate, setSelectedRef])
  const run = (i: number) => { items[i]?.run(); onClose() }
  return (
    <>
      <div className="v-scrim v-dialog-scrim" onClick={onClose} aria-hidden />
      <div className="v-palette" role="dialog" aria-modal="true" aria-label="Go to">
        <input autoFocus value={q} placeholder={can('SEARCH_RECORDS') ? 'Jump to a page or record number…' : 'Jump to a page…'} aria-label="Search destinations"
          onChange={(e) => { setQ(e.target.value); setActive(0) }}
          onKeyDown={(e) => {
            if (e.key === 'Escape') onClose()
            if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(a + 1, items.length - 1)) }
            if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)) }
            if (e.key === 'Enter' && !e.nativeEvent.isComposing && e.keyCode !== 229) run(active)
          }} />
        <ul>
          {items.length === 0 && <li className="faint" style={{ padding: 16 }}>No matches</li>}
          {items.map((i, idx) => (
            <li key={i.key}><button type="button" data-active={idx === active} onMouseEnter={() => setActive(idx)} onClick={() => run(idx)}>
              <span className="grow truncate">{i.label}</span><span className="faint" style={{ fontSize: 13 }}>{i.hint}</span>{idx === active && <CornerDownLeft size={14} aria-hidden />}
            </button></li>
          ))}
        </ul>
      </div>
    </>
  )
}

function ShellInner() {
  const { role, route, theme, setTheme, liveSession, sessionLoading, sessionError, navPref, setNavPref } = useStore()
  const [session, setSession] = useState(false)
  const [palette, setPalette] = useState(false)

  const docked = useMediaQuery('(min-width: 1280px)')
  const [peek,setPeek] = useState(false)
  const dockedCollapsed = navPref === 'collapsed'
  const collapsed = docked ? dockedCollapsed : !peek
  const overlay = !docked && peek
  const toggleNav = () => { if(docked)setNavPref(dockedCollapsed?'expanded':'collapsed');else setPeek(p=>!p) }
  const toggleRef=useRef(toggleNav)
  toggleRef.current=toggleNav
  useEffect(()=>{if(docked)setPeek(false)},[docked])
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setPalette((p) => !p) }
      if(e.key==='Escape')setPeek(false)
      const t=e.target as HTMLElement|null
      if(e.key==='['&&!e.metaKey&&!e.ctrlKey&&!e.altKey&&!(t&&(t.isContentEditable||/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName)))){e.preventDefault();toggleRef.current()} }
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [])

  const allowed = (route.area === 'system' && showDevelopmentUtilities) || (route.area === 'workspace' && !!liveSession?.capabilities.includes('SEARCH_RECORDS')) || (route.area === 'governance' && !!liveSession && ['CONFIGURE_ROLE_PERMISSIONS','CONFIGURE_RETENTION','CONFIGURE_METADATA_SEARCH','CONFIGURE_BUSINESS_LIMITS'].some(c=>liveSession.capabilities.includes(c))) || (route.area === 'tenant' && !!liveSession?.capabilities.includes('CONFIGURE_INGESTION_SOURCES')) || (route.area === 'operations' && !!liveSession?.capabilities.includes('VIEW_TECHNICAL_FAILURES'))
  return (
    <div className="r7">
      <div className="v-app" data-nav={docked?(collapsed?"collapsed":"expanded"):overlay?"overlay":"rail"}>
        <header className="v-top">
          <a className="v-brand" href="#/" aria-label="vero home" onClick={(e) => { e.preventDefault() }}>vero<i aria-hidden /></a>
          <div className="v-tenant"><strong>{liveSession?.tenantDisplayName ?? 'Vero'}</strong><span>{liveSession ? 'Connected session' : 'Connecting'}</span></div>
          <div className="grow" />
          <button type="button" className="v-command" onClick={() => setPalette(true)} aria-label="Go to page or record"><Search size={16} aria-hidden /><span>Go to…</span><kbd>⌘K</kbd></button>
          <span className="v-sim-chip" title="Business search uses SDV backend; other workflows pending"><FlaskConical size={13} aria-hidden /><span className="long">R7 · business integration</span><span className="short">Simulated</span></span>
          <button type="button" className="v-btn quiet icon vw-hide-sm" onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')} aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}>{theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}</button>
          <button type="button" className="v-me" onClick={() => setSession(true)} aria-label={`Signed in as ${liveSession?.subject ?? 'Connecting'}, ${role.name}`}>
            <span className="v-avatar" aria-hidden>{role.initials}</span>
            <span className="who"><strong>{liveSession?.subject ?? 'Connecting'}</strong><span>{role.name}</span></span>
          </button>
        </header>
        <Rail collapsed={collapsed} onToggle={toggleNav} onNavigated={()=>setPeek(false)} onOpenSession={() => {setPeek(false);setSession(true)}} />
        {overlay&&<div className="v-rail-scrim" onClick={()=>setPeek(false)} aria-hidden />}
        <main className="v-main" id="main">
          <div style={{ display: route.area === 'workspace' && !sessionLoading && !sessionError && allowed ? 'contents' : 'none' }}><Workspace /></div>
          {sessionLoading ? <div className="v-body">Connecting to SDV…</div> : sessionError ? <div className="v-body" role="alert">Session unavailable: {sessionError}</div> : route.area === 'workspace' && allowed ? null : !allowed ? <AccessBoundary /> :
            route.area === 'system' ? <DesignSystem /> :
            route.area === 'governance' ? <Governance /> :
            route.area === 'tenant' ? <TenantAdmin /> :
            route.area === 'operations' ? <Operations /> : null}
        </main>
        <BottomNav onOpenSession={() => setSession(true)} onOpenPalette={() => setPalette(true)} />
      </div>
      {session && <Drawer eyebrow="Session" title="Authenticated session" onClose={() => setSession(false)}>
        {localRoleSwitchEnabled() ? <>
          <label className="v-field"><span>Local test role</span><select className="v-select" value={role.id} onChange={e=>{
            const selected=e.target.value as LocalPersona
            const area=selected==='business'||selected==='steward'?'workspace':selected==='businessAdmin'?'governance':selected==='tenantIt'?'tenant':'operations'
            setLocalTestPersona(selected)
            // Reload discards previous persona's documents, cached queries, and pending component state.
            window.history.replaceState(null,'','#/'+area+'/'+(area==='workspace'?'search':area==='governance'?'access':area==='tenant'?'storage':'overview'))
            window.location.reload()
          }}>
            <option value="business">Business User</option>
            <option value="steward">Document Steward</option>
            <option value="businessAdmin">Business Administrator</option>
            <option value="tenantIt">Tenant IT Administrator</option>
            <option value="platform">Platform Operations Administrator</option>
          </select><small>Local development only. Switching refreshes the authenticated test identity and clears the previous workspace.</small></label>
          <p>{liveSession?.subject ?? 'Not authenticated'}</p>
        </> : <><p>Identity and permissions are provided by the SDV backend. Role switching and simulated scenarios are unavailable in connected mode.</p><p>{liveSession?.subject ?? 'Not authenticated'}</p></>}
      </Drawer>}
      {palette && <Palette onClose={() => setPalette(false)} />}
      <Toasts />
    </div>
  )
}

function AccessBoundary() {
  const { role, navigate } = useStore()
  return (
    <div className="v-body" style={{ maxWidth: 560, margin: '10vh auto' }}>
      <div className="v-panel"><div className="v-panel-body stack">
        <Activity size={22} aria-hidden className="faint" />
        <h1>This area isn&apos;t assigned to your role</h1>
        <p className="muted">{role.name} can&apos;t open this area. Access is decided by roles in your identity provider and enforced by the server; the interface only reflects it.</p>
        <button type="button" className="v-btn primary" style={{ alignSelf: 'flex-start' }} onClick={() => navigate(role.areas[0])}>Go to {AREA_META[role.areas[0]].label}</button>
      </div></div>
    </div>
  )
}

export function R7App() {
  return <StoreProvider><ShellInner /></StoreProvider>
}
export { AREA_META }
