import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  AUDIT, DEFAULT_ROLE_CAPS, IDENTITY, RETENTION, ROLES, SOURCES, VOLUMES,
  type AreaId, type AuditEvent, type Capability, type Doc, type IdentityConfig, type RecordItem, type RoleId, type Source, type Volume, NOW,
} from './data'
import { session as loadSession } from './liveApi'
import type { SessionInfo } from '../api'
import { failureText } from './CardError'

export type Scenario = 'live' | 'loading' | 'empty' | 'error'
export type Theme = 'light' | 'dark'
export type ThemePref = Theme | 'system'
const THEME_KEY = 'sdv.r7.theme'
const readThemePref = (): ThemePref => {
  try { const v = window.localStorage.getItem(THEME_KEY); return v === 'light' || v === 'dark' || v === 'system' ? v : 'system' } catch { return 'system' }
}
const systemTheme = (): Theme => (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
export type Toast = { id: number; title: string; detail?: string; tone?: 'sim' | 'ok' | 'bad' }
export type Route = { area: AreaId; tab: string }
/** A search started outside Records (e.g. on Home), carried out by the Records page. */
export type SearchRequest = { id: number; box?: string; openFilters?: boolean; incompleteOnly?: boolean; receivedLast30Days?: boolean; sourceId?: string; criteria?: Record<string, string>; selectRef?: string }
let searchRequestSeq = 0

export const AREA_TABS: Record<AreaId, { id: string; label: string }[]> = {
  home: [{ id: 'home', label: 'Home' }],
  workspace: [{ id: 'search', label: 'Search' }],
  // Labels only; the addresses (#/governance/access …) stay the same.
  governance: [{ id: 'access', label: 'People & roles' }, { id: 'roles', label: 'Roles & permissions' }, { id: 'retention', label: 'Keeping documents' }, { id: 'metadata', label: 'Search fields' }],
  tenant: [{ id: 'storage', label: 'Storage' }, { id: 'authentication', label: 'Authentication' }, { id: 'integrations', label: 'Integrations' }, { id: 'environment', label: 'Environment' }],
  operations: [{ id: 'overview', label: 'Overview' }, { id: 'intake', label: 'Intake' }, { id: 'storage', label: 'Storage' }, { id: 'health', label: 'Platform health' }, { id: 'incidents', label: 'Incidents' }, { id: 'audit', label: 'Audit' }, { id: 'configuration', label: 'Configuration' }],
  system: [{ id: 'foundations', label: 'Foundations' }, { id: 'components', label: 'Components' }, { id: 'states', label: 'States' }],
}

const parseHash = (): Route | null => {
  const [area, tab] = window.location.hash.replace(/^#\/?/, '').split('/')
  if (!area || !(area in AREA_TABS)) return null
  const tabs = AREA_TABS[area as AreaId]
  return { area: area as AreaId, tab: tabs.some((t) => t.id === tab) ? tab : tabs[0].id }
}

type Store = ReturnType<typeof useStoreValue>
const Ctx = createContext<Store | null>(null)
export const useStore = () => {
  const value = useContext(Ctx)
  if (!value) throw new Error('useStore outside StoreProvider')
  return value
}

const AREA_ORDER: AreaId[] = ['home', 'workspace', 'governance', 'tenant', 'operations']

let toastSeq = 0
let eventSeq = 0

function useStoreValue() {
  const [roleId, setRoleId] = useState<RoleId>('business')
  const [liveSession, setLiveSession] = useState<SessionInfo | null>(null)
  const [sessionError, setSessionError] = useState<string | null>(null)
  const [sessionLoading, setSessionLoading] = useState(true)
  const [scenario, setScenario] = useState<Scenario>('live')
  // Theme choice: Light, Dark or System (follows the computer; default). Remembered in this browser.
  const [themePref, setThemePrefState] = useState<ThemePref>(readThemePref)
  const [systemThemeValue, setSystemThemeValue] = useState<Theme>(systemTheme)
  const theme: Theme = themePref === 'system' ? systemThemeValue : themePref
  const setThemePref = useCallback((pref: ThemePref) => {
    setThemePrefState(pref)
    try { window.localStorage.setItem(THEME_KEY, pref) } catch { /* storage unavailable: choice lasts for this page only */ }
  }, [])
  useEffect(() => {
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)')
    if (!mq) return
    const on = () => setSystemThemeValue(mq.matches ? 'dark' : 'light')
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])
  // Without an address the person lands on their first area once the session is known (see Shell).
  const [startedWithoutRoute] = useState(() => parseHash() === null)
  const [route, setRouteState] = useState<Route>(() => parseHash() ?? { area: 'home', tab: 'home' })
  const [searchRequest, setSearchRequest] = useState<SearchRequest | null>(null)
  const requestSearch = useCallback((r: Omit<SearchRequest, 'id'>) => { searchRequestSeq += 1; setSearchRequest({ ...r, id: searchRequestSeq }) }, [])
  const [toasts, setToasts] = useState<Toast[]>([])
  const [roleCaps, setRoleCaps] = useState(DEFAULT_ROLE_CAPS)
  const [records, setRecords] = useState<RecordItem[]>([])
  const [retention, setRetention] = useState(RETENTION)
  const [volumes, setVolumes] = useState<Volume[]>(VOLUMES)
  const [sources, setSources] = useState<Source[]>(SOURCES)
  const [identity, setIdentity] = useState(IDENTITY)
  const [audit, setAudit] = useState<AuditEvent[]>(AUDIT)
  const [selectedRef, setSelectedRef] = useState<string | null>(null)
  const [openDocId, setOpenDocId] = useState<string | null>(null)
  const [resultsShown,setResultsShown]=useState(false)

  useEffect(() => {
    let active = true
    loadSession().then(s => {
      if (!active) return
      setLiveSession(s)
      setRoleId(s.roles.includes('PLATFORM_OPERATIONS_ADMIN') ? 'platform' : s.roles.includes('TENANT_IT_ADMIN') ? 'tenantIt' : s.roles.includes('BUSINESS_ADMIN') ? 'businessAdmin' : s.roles.includes('DOCUMENT_STEWARD') ? 'steward' : 'business')
    }).catch(e => { if (active) setSessionError(failureText('Session did not load', e)) })
      .finally(() => { if(active) setSessionLoading(false) })
    return () => { active = false }
  }, [])
  const role = ROLES.find((r) => r.id === roleId)!
  // A person with several roles sees the areas of all of them (the server still enforces each request).
  const heldRoles = useMemo(() => {
    const held = liveSession ? ROLES.filter((r) => liveSession.roles.includes(r.code)) : []
    return held.length ? held : [role]
  }, [liveSession, role])
  const areas = useMemo<AreaId[]>(() => {
    const set = new Set(heldRoles.flatMap((r) => r.areas))
    return AREA_ORDER.filter((a) => set.has(a))
  }, [heldRoles])

  useEffect(() => {
    document.documentElement.dataset.theme = theme
  }, [theme])

  useEffect(() => {
    const onHash = () => { const next = parseHash(); if (next) setRouteState(next) }
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  const navigate = useCallback((area: AreaId, tab?: string, replace?: boolean) => {
    const next = { area, tab: tab ?? AREA_TABS[area][0].id }
    setRouteState(next)
    const hash = `#/${next.area}/${next.tab}`
    if (window.location.hash !== hash) window.history[replace ? 'replaceState' : 'pushState'](null, '', hash)
  }, [])

  const switchRole = useCallback((id: RoleId) => {
    if (liveSession) return
    setRoleId(id)
    setOpenDocId(null)
    const target = ROLES.find((r) => r.id === id)!
    navigate(target.areas[0])
  }, [navigate])

  const toast = useCallback((t: Omit<Toast, 'id'>) => {
    toastSeq += 1
    const id = toastSeq
    setToasts((list) => [...list.slice(-2), { ...t, id }])
    window.setTimeout(() => setToasts((list) => list.filter((x) => x.id !== id)), 4800)
  }, [])

  const caps = useMemo<Capability[]>(() => liveSession ? liveSession.capabilities.filter((c):c is Capability =>
    c in ({ SEARCH_RECORDS:1, VIEW_PDF:1, PERMANENT_REDACTION:1, DOWNLOAD_CURRENT_PDF:1, PRINT_DOCUMENT:1, ADD_DOCUMENT_TAG:1, ADD_DOCUMENT_COMMENT:1, CONFIGURE_ROLE_PERMISSIONS:1, CONFIGURE_RETENTION:1, CONFIGURE_METADATA_SEARCH:1, CONFIGURE_BUSINESS_LIMITS:1 })
  ) : [], [liveSession])
  const can = useCallback((c: Capability) => caps.includes(c), [caps])

  const recordAudit = useCallback((e: Omit<AuditEvent, 'id' | 'at' | 'actorType' | 'actor' | 'correlationId'>) => {
    eventSeq += 1
    setAudit((list) => [{ ...e, id: `sim-${eventSeq}`, at: new Date(NOW + eventSeq * 1000).toISOString(), actorType: 'USER', actor: role.person.toLowerCase().replace(' ', '.'), correlationId: `sim-${eventSeq.toString(16).padStart(6, '0')}` }, ...list])
  }, [role.person])

  const updateDoc = useCallback((docId: string, fn: (d: Doc) => Doc) => {
    setRecords((list) => list.map((r) => (r.docs.some((d) => d.id === docId) ? { ...r, docs: r.docs.map((d) => (d.id === docId ? fn(d) : d)) } : r)))
  }, [])

  const addDocActivity = useCallback((docId: string, action: string, outcome: 'SUCCESS' | 'DENIED' = 'SUCCESS') => {
    eventSeq += 1
    const at = new Date(NOW + eventSeq * 1000).toISOString()
    updateDoc(docId, (d) => ({ ...d, activity: [{ id: `sim-a${eventSeq}`, action, actor: role.person, at, outcome, simulated: true }, ...d.activity] }))
    recordAudit({ action, targetType: 'DOCUMENT', target: docId, outcome, details: { simulated: 'true' } })
  }, [recordAudit, role.person, updateDoc])

  return {
    role, roleId, heldRoles, areas, switchRole, startedWithoutRoute, searchRequest, requestSearch, liveSession, sessionLoading, sessionError, scenario, setScenario, theme, themePref, setThemePref, route, navigate,
    toasts, toast, dismissToast: (id: number) => setToasts((l) => l.filter((t) => t.id !== id)),
    roleCaps, setRoleCaps, caps, can,
    records, setRecords, updateDoc, addDocActivity, selectedRef, setSelectedRef, openDocId, setOpenDocId, resultsShown, setResultsShown,
    retention, setRetention, volumes, setVolumes, sources, setSources,
    identity, setIdentity: setIdentity as (fn: (prev: typeof IDENTITY) => typeof IDENTITY) => void, audit, recordAudit,
  }
}

export type { IdentityConfig }

export function StoreProvider({ children }: { children: ReactNode }) {
  const value = useStoreValue()
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}
