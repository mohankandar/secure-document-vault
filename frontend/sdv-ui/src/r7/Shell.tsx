import { useEffect, useMemo, useRef, useState } from 'react'
import { Building2, FileSearch, House, FlaskConical, Layers, Search, ServerCog, ShieldCheck, CornerDownLeft, UserRound } from 'lucide-react'
import { AREA_TABS, StoreProvider, useStore } from './store'
import { type AreaId, type RoleId } from './data'
import { opSummary } from './liveApi'
import { Toasts } from './ui'
import { getLocalTestPersona, localRoleSwitchEnabled, setLocalTestPersona, type LocalPersona, type SessionInfo } from '../api'
import { endOidcSession, isLocalSecurityMode } from '../auth'
import { Workspace } from './Workspace'
import { Home } from './Home'
import { Governance } from './Governance'
import { TenantAdmin } from './TenantAdmin'
import { Operations } from './Operations'
import { DesignSystem } from './DesignSystem'
import { PRODUCT_NAME } from './brand'
import { TopBar, type TopBarTab } from './topbar/TopBar'
import { AccountMenu, type AccountPerson } from './topbar/AccountMenu'

const showDevelopmentUtilities = import.meta.env.DEV

const AREA_META: Record<AreaId, { label: string; tab: string; short: string; Icon: typeof Search }> = {
  home: { label: 'Home', tab: 'Home', short: 'Home', Icon: House },
  workspace: { label: 'Records workspace', tab: 'Records', short: 'Records', Icon: FileSearch },
  governance: { label: 'Business administration', tab: 'Business administration', short: 'Governance', Icon: ShieldCheck },
  tenant: { label: 'Tenant IT', tab: 'Tenant IT', short: 'Tenant IT', Icon: Building2 },
  operations: { label: 'Platform operations', tab: 'Platform operations', short: 'Operations', Icon: ServerCog },
  system: { label: 'Design system', tab: 'Design system', short: 'System', Icon: Layers },
}

/** The role in plain words, as shown in the account menu. */
const ROLE_LABEL: Record<RoleId, string> = {
  business: 'Business user',
  steward: 'Document steward',
  businessAdmin: 'Business administrator',
  tenantIt: 'Tenant IT administrator',
  platform: 'Platform operations administrator',
}
/** Readable names for the local test sign-in (keyed by local test persona), which has no name claim. */
const LOCAL_NAME: Record<LocalPersona, string> = {
  business: 'Local business user',
  steward: 'Local document steward',
  businessAdmin: 'Local business administrator',
  tenantIt: 'Local Tenant IT administrator',
  platform: 'Local platform operations administrator',
  admin: 'Local administrator',
}
const LOCAL_INITIALS: Record<LocalPersona, string> = { business: 'BU', steward: 'DS', businessAdmin: 'BA', tenantIt: 'IT', platform: 'PO', admin: 'LA' }

const initialsOf = (name: string) => {
  const words = name.trim().split(/\s+/).filter((w) => /^\p{L}/u.test(w))
  if (words.length === 0) return undefined
  return (words.length === 1 ? words[0][0] : words[0][0] + words[words.length - 1][0]).toUpperCase()
}

/**
 * Never shows the technical subject: name claim, else (LOCAL) a per-persona name, else email, else "Signed in".
 * The role line lists every role the person holds, in plain words.
 */
export function accountPerson(session: SessionInfo | null, roleIds: RoleId[], localPersona: LocalPersona | null): AccountPerson {
  const roleLabel = roleIds.map((id) => ROLE_LABEL[id]).join(', ')
  const organization = session?.tenantDisplayName?.trim() || PRODUCT_NAME
  const email = session?.email?.trim() || undefined
  const displayName = session?.displayName?.trim()
  if (!session) return { name: 'Signing in…', roleLabel, organization }
  if (displayName) return { name: displayName, initials: initialsOf(displayName), email, roleLabel, organization }
  if (localPersona) return { name: LOCAL_NAME[localPersona], initials: LOCAL_INITIALS[localPersona], email, roleLabel, organization }
  if (email) return { name: email, initials: email[0].toUpperCase(), email, roleLabel, organization }
  return { name: 'Signed in', roleLabel, organization }
}

const LOCAL_ROLE_OPTIONS: { value: LocalPersona; label: string }[] = [
  { value: 'business', label: 'Business User' },
  { value: 'steward', label: 'Document Steward' },
  { value: 'businessAdmin', label: 'Business Administrator' },
  { value: 'tenantIt', label: 'Tenant IT Administrator' },
  { value: 'platform', label: 'Platform Operations Administrator' },
  { value: 'admin', label: 'Local Administrator (Tenant IT + Business admin)' },
]
const switchLocalRole = (selected: LocalPersona) => {
  const area = selected === 'business' || selected === 'steward' ? 'home' : selected === 'businessAdmin' || selected === 'admin' ? 'governance' : selected === 'tenantIt' ? 'tenant' : 'operations'
  setLocalTestPersona(selected)
  // Reload discards previous persona's documents, cached queries, and pending component state.
  window.history.replaceState(null, '', '#/' + area + '/' + (area === 'home' ? 'home' : area === 'governance' ? 'access' : area === 'tenant' ? 'storage' : 'overview'))
  window.location.reload()
}

function useIncidentCount(enabled: boolean, roleKey: string) {
  const [count, setCount] = useState<number | null>(null)
  useEffect(() => {
    if (!enabled) return
    let live = true
    opSummary().then((s) => {
      if (!live) return
      const counts = [s.processingFailureCount, s.stuckDocumentCount, s.attentionSubmissionCount]
      setCount(counts.every((x) => typeof x === 'number') ? counts.reduce<number>((sum, x) => sum + (x ?? 0), 0) : null)
    }).catch(() => { if (live) setCount(null) })
    return () => { live = false }
  }, [enabled, roleKey])
  return count
}

function BottomNav({ onOpenAccount, onOpenPalette }: { onOpenAccount: () => void; onOpenPalette: () => void }) {
  const { areas, route, navigate } = useStore()
  return (
    <nav className="v-bottom" aria-label="Bottom navigation">
      {areas.map((area) => {
        const { short, Icon } = AREA_META[area]
        return <button key={area} type="button" aria-current={route.area === area ? 'page' : undefined} onClick={() => navigate(area)}><Icon size={22} aria-hidden />{short}</button>
      })}
      <button type="button" onClick={onOpenPalette}><Search size={22} aria-hidden />Go to</button>
      {showDevelopmentUtilities && <button type="button" aria-current={route.area === 'system' ? 'page' : undefined} onClick={() => navigate('system')}><Layers size={22} aria-hidden />System</button>}
      {showDevelopmentUtilities && <button type="button" onClick={onOpenAccount}><UserRound size={22} aria-hidden />Session</button>}
    </nav>
  )
}

function Palette({ onClose }: { onClose: () => void }) {
  const { areas, navigate, records, can, setSelectedRef } = useStore()
  const [q, setQ] = useState('')
  const [active, setActive] = useState(0)
  const items = useMemo(() => {
    const dest = [...areas, ...(showDevelopmentUtilities ? ['system' as AreaId] : [])].flatMap((a) => AREA_TABS[a].map((t) => ({ key: `${a}/${t.id}`, label: t.label, hint: AREA_META[a].label, run: () => navigate(a, t.id) })))
    const recs = can('SEARCH_RECORDS') ? records.map((r) => ({ key: r.ref, label: `${r.identifiers[0].value} · ${r.title}`, hint: 'Record', run: () => { navigate('workspace'); setSelectedRef(r.ref) } })) : []
    const needle = q.trim().toLowerCase()
    return [...dest, ...recs].filter((i) => !needle || `${i.label} ${i.hint}`.toLowerCase().includes(needle)).slice(0, 12)
  }, [q, areas, records, can, navigate, setSelectedRef])
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

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)

function ShellInner() {
  const { role, heldRoles, areas, route, navigate, themePref, setThemePref, liveSession, sessionLoading, sessionError, startedWithoutRoute } = useStore()
  // Opened without an address: land on the person's first area (Home for search roles, their own area for admins).
  const landed = useRef(false)
  useEffect(() => {
    if (landed.current || !liveSession) return
    landed.current = true
    if (startedWithoutRoute && areas[0]) navigate(areas[0], undefined, true)
  }, [liveSession, startedWithoutRoute, areas, navigate])
  const [palette, setPalette] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)
  const localMode = isLocalSecurityMode()
  const incidentCount = useIncidentCount(areas.includes('operations'), role.id)

  useEffect(() => {
    const k = (e: KeyboardEvent) => { if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setPalette((p) => !p) } }
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [])

  const tabs: TopBarTab[] = areas.map((a) => ({ id: a, label: AREA_META[a].tab, count: a === 'operations' && incidentCount ? incidentCount : undefined }))
  // In LOCAL mode the test persona names the person; without a stored choice the session is the role's own persona.
  const localPersona: LocalPersona | null = localMode ? (getLocalTestPersona() ?? role.id) : null
  const person = accountPerson(liveSession, heldRoles.map((r) => r.id), localPersona)

  const allowed = (route.area === 'system' && showDevelopmentUtilities) || ((route.area === 'workspace' || route.area === 'home') && !!liveSession?.capabilities.includes('SEARCH_RECORDS')) || (route.area === 'governance' && !!liveSession && ['CONFIGURE_ROLE_PERMISSIONS','CONFIGURE_RETENTION','CONFIGURE_METADATA_SEARCH','CONFIGURE_BUSINESS_LIMITS'].some(c=>liveSession.capabilities.includes(c))) || (route.area === 'tenant' && !!liveSession?.capabilities.includes('CONFIGURE_INGESTION_SOURCES')) || (route.area === 'operations' && !!liveSession?.capabilities.includes('VIEW_TECHNICAL_FAILURES'))
  return (
    <div className="r7">
      <div className="v-app">
        <TopBar
          productName={PRODUCT_NAME}
          tabs={tabs}
          currentTabId={route.area}
          onSelectTab={(id) => navigate(id as AreaId)}
          onOpenCommand={() => setPalette(true)}
          commandShortcut={isMac ? '⌘K' : 'Ctrl K'}
          extra={showDevelopmentUtilities && <span className="v-sim-chip" title="Business search uses SDV backend; other workflows pending"><FlaskConical size={13} aria-hidden /><span className="long">R7 · business integration</span><span className="short">Simulated</span></span>}
          account={
            <AccountMenu
              person={person}
              theme={themePref}
              onThemeChange={setThemePref}
              open={accountOpen}
              onOpenChange={setAccountOpen}
              // Entity switch: SDV has no entities yet, so no entities are passed and the section stays hidden.
              onSignOut={localMode ? undefined : () => { void endOidcSession() }}
              extraSection={showDevelopmentUtilities ? { title: 'Development only', render: (close) => (
                <>
                  {localRoleSwitchEnabled() && (
                    <label className="am-field">
                      <span>Local test role</span>
                      <select value={localPersona ?? role.id} data-menu-stop="" onChange={(e) => switchLocalRole(e.target.value as LocalPersona)}>
                        {LOCAL_ROLE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                      </select>
                      <small>Switching reloads with the new test identity.</small>
                    </label>
                  )}
                  <button type="button" className="am-item" data-menu-stop="" onClick={() => { navigate('system'); close() }}><Layers size={16} aria-hidden />Design system</button>
                </>
              ) } : undefined}
            />
          }
        />
        <main className="v-main" id="main">
          {/* Records stays mounted (hidden) to keep its state, but only for roles that may search: other roles must not call it. */}
          {liveSession?.capabilities.includes('SEARCH_RECORDS') && <div style={{ display: route.area === 'workspace' && !sessionLoading && !sessionError && allowed ? 'contents' : 'none' }}><Workspace /></div>}
          {sessionLoading ? <div className="v-body">Connecting to {PRODUCT_NAME}…</div> : sessionError ? <div className="v-body" role="alert"><strong>Your session didn&apos;t start.</strong> <span className="muted">{sessionError}</span></div> : route.area === 'workspace' && allowed ? null : !allowed ? <AccessBoundary /> :
            route.area === 'home' ? <Home /> :
            route.area === 'system' ? <DesignSystem /> :
            route.area === 'governance' ? <Governance /> :
            route.area === 'tenant' ? <TenantAdmin /> :
            route.area === 'operations' ? <Operations /> : null}
        </main>
        <BottomNav onOpenAccount={() => setAccountOpen(true)} onOpenPalette={() => setPalette(true)} />
      </div>
      {palette && <Palette onClose={() => setPalette(false)} />}
      <Toasts />
    </div>
  )
}

/** Sign out from the no-access page. OIDC: the normal sign-out. LOCAL (no real sign-in): forget the local test role and start again. */
const signOutHere = () => {
  if (!isLocalSecurityMode()) { void endOidcSession(); return }
  try { window.sessionStorage.removeItem('sdv.r7.local-test-persona') } catch { /* storage unavailable */ }
  window.location.assign(window.location.pathname)
}

function AccessBoundary() {
  const { heldRoles, areas, navigate } = useStore()
  const roles = heldRoles.map((r) => ROLE_LABEL[r.id]).join(', ')
  return (
    <div className="v-body" style={{ maxWidth: 600, margin: '10vh auto' }}>
      <div className="v-panel"><div className="v-panel-body stack">
        <h1>This page isn&apos;t part of your role</h1>
        <p className="muted">Your role is {roles}. If you need this page, ask your administrator to add the right role in your company&apos;s sign-in system.</p>
        <div className="row wrap" style={{ gap: 10 }}>
          <button type="button" className="v-btn primary" onClick={() => navigate(areas[0])}>Go to {AREA_META[areas[0]].tab}</button>
          <button type="button" className="v-btn" onClick={signOutHere}>Sign out</button>
        </div>
      </div></div>
    </div>
  )
}

export function R7App() {
  return <StoreProvider><ShellInner /></StoreProvider>
}
export { AREA_META }
