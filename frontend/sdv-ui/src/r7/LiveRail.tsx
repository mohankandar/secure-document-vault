import { FileSearch, ShieldCheck, Building2, ServerCog, ScrollText, LogOut, PanelLeftClose, PanelLeftOpen } from 'lucide-react'
import type { LocalPersona, SessionInfo } from '../api'

type Destination = 'search' | 'admin' | 'operations' | 'audit'
type Props = {
  active: Destination
  session: SessionInfo | null
  localMode: boolean
  persona: LocalPersona
  collapsed: boolean
  drawerOpen: boolean
  canSearch: boolean
  canAdmin: boolean
  canOperations: boolean
  onNavigate: (to: Destination) => void
  onChangePersona: (persona: LocalPersona) => void
  onToggleCollapsed: () => void
  onSignOut: () => void
}

/** Real session/capability-bound R7 navigation. No mock roles or prototype store. */
export function LiveRail(props: Props) {
  const { active, session, localMode, persona, collapsed, drawerOpen, canSearch, canAdmin,
    canOperations, onNavigate, onChangePersona, onToggleCollapsed, onSignOut } = props
  const label = session?.tenantDisplayName || 'Organization'
  const entries = [
    { id: 'search' as const, title: 'Records workspace', icon: FileSearch, enabled: canSearch, group: 'Work' },
    { id: 'admin' as const, title: persona === 'tenantIt' ? 'Tenant IT' : 'Business administration', icon: persona === 'tenantIt' ? Building2 : ShieldCheck, enabled: canAdmin, group: 'Administration' },
    { id: 'operations' as const, title: 'Platform operations', icon: ServerCog, enabled: canOperations, group: 'Operations' },
    { id: 'audit' as const, title: 'Audit', icon: ScrollText, enabled: canOperations, group: 'Operations' },
  ].filter(item => item.enabled)
  return <aside className={`v7-live-rail modern-sidebar ${drawerOpen ? 'drawer-open' : ''}`} aria-label="Vero navigation">
    <div className="v7-live-brand"><strong>vero<span aria-hidden="true">.</span></strong>
      <button type="button" onClick={onToggleCollapsed} aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'}>
        {collapsed ? <PanelLeftOpen size={18}/> : <PanelLeftClose size={18}/>}</button>
    </div>
    <div className="v7-live-tenant" title={label}><span className="v7-live-tenant-icon">{label.slice(0,1).toUpperCase()}</span>
      <div><strong>{label}</strong><small>Document workspace</small></div></div>
    <nav className="v7-live-links" aria-label="Primary navigation">
      {entries.map((item, index) => {
        const Icon = item.icon
        return <div key={item.id}>
          {(index === 0 || entries[index - 1].group !== item.group) && <div className="v7-live-navgroup">{item.group}</div>}
          <button type="button" aria-current={active === item.id ? 'page' : undefined}
            className={`v7-live-navitem ${active === item.id ? 'is-active' : ''}`}
            title={item.title} onClick={() => onNavigate(item.id)}>
            <Icon size={19} aria-hidden="true"/><span>{item.title}</span></button>
        </div>
      })}
    </nav>
    <div className="v7-live-footer">
      {localMode && <label className="v7-live-persona"><span>Local validation role</span>
        <select value={persona} onChange={e => onChangePersona(e.target.value as LocalPersona)}>
          <option value="business">Business User</option><option value="steward">Document Steward</option>
          <option value="businessAdmin">Business Administrator</option><option value="tenantIt">Tenant IT Administrator</option>
          <option value="platform">Platform Operations Administrator</option>
        </select></label>}
      <div className="v7-live-user"><span className="v7-live-avatar">{(session?.subject || 'U').slice(0,2).toUpperCase()}</span>
        <div><strong>{session?.subject || 'Secure session'}</strong><small>{localMode ? 'Local validation' : 'Signed in'}</small></div></div>
      {!localMode && <button type="button" className="v7-live-signout" onClick={onSignOut}><LogOut size={16}/> Sign out</button>}
    </div>
  </aside>
}
