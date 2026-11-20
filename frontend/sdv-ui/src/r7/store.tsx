import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { KNOWN_CAPABILITIES, ROLES, type AreaId, type Capability, type RecordItem, type RoleId } from './model'
import { session as loadSession, type RecentRecord } from './liveApi'
import type { SessionInfo } from '../api'
import { failureText } from './CardError'

export type Theme = 'light' | 'dark'
export type ThemePref = Theme | 'system'
const THEME_KEY = 'sdv.r7.theme'
const readThemePref = (): ThemePref => {
  try { const v = window.localStorage.getItem(THEME_KEY); return v === 'light' || v === 'dark' || v === 'system' ? v : 'system' } catch { return 'system' }
}
const systemTheme = (): Theme => (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
export type Toast = { id: number; title: string; detail?: string; tone?: 'ok' | 'bad' }
export type Route = { area: AreaId; tab: string }
/** A search started outside Records (e.g. on Home), carried out by the Records page. */
export type SearchRequest = { id: number; /** Task 0022: text searched in every searchable field. */ anywhere?: string; openFilters?: boolean; incompleteOnly?: boolean; receivedLast30Days?: boolean; sourceId?: string; criteria?: Record<string, string>; selectRef?: string }
let searchRequestSeq = 0

export const AREA_TABS: Record<AreaId, { id: string; label: string }[]> = {
  home: [{ id: 'home', label: 'Home' }],
  workspace: [{ id: 'search', label: 'Search' }],
  // Labels only; the addresses (#/governance/access …) stay the same.
  governance: [{ id: 'access', label: 'People & roles' }, { id: 'roles', label: 'Roles & permissions' }, { id: 'retention', label: 'Keeping documents' }, { id: 'metadata', label: 'Search fields' }],
  tenant: [{ id: 'storage', label: 'Storage' }, { id: 'authentication', label: 'Sign-in' }, { id: 'integrations', label: 'Integrations' }, { id: 'problems', label: 'Problems' }, { id: 'about', label: 'About' }],
  operations: [{ id: 'overview', label: 'Overview' }, { id: 'problems', label: 'Problems' }, { id: 'storage', label: 'Storage' }, { id: 'health', label: 'Health' }, { id: 'audit', label: 'Audit' }, { id: 'about', label: 'About' }],
  system: [{ id: 'foundations', label: 'Foundations' }, { id: 'components', label: 'Components' }, { id: 'states', label: 'States' }],
}

const parseHash = (): Route | null => {
  const [area, tab] = window.location.hash.replace(/^#\/?/, '').split('/')
  if (!area || !(area in AREA_TABS)) return null
  const tabs = AREA_TABS[area as AreaId]
  // Old addresses: Intake and Incidents are now Problems (task 0011); Environment and Configuration are now About (task 0012).
  const wanted = area === 'operations' && (tab === 'intake' || tab === 'incidents') ? 'problems' : (tab === 'environment' || tab === 'configuration') ? 'about' : tab
  return { area: area as AreaId, tab: tabs.some((t) => t.id === wanted) ? wanted : tabs[0].id }
}

type Store = ReturnType<typeof useStoreValue>
const Ctx = createContext<Store | null>(null)
export const useStore = () => {
  const value = useContext(Ctx)
  if (!value) throw new Error('useStore outside StoreProvider')
  return value
}

const AREA_ORDER: AreaId[] = ['home', 'workspace', 'governance', 'tenant', 'operations']

/** The role the session names first, in this order; Business user when it names none of them. */
const primaryRole = (roles: string[]): RoleId =>
  roles.includes('PLATFORM_OPERATIONS_ADMIN') ? 'platform' : roles.includes('TENANT_IT_ADMIN') ? 'tenantIt' : roles.includes('BUSINESS_ADMIN') ? 'businessAdmin' : roles.includes('DOCUMENT_STEWARD') ? 'steward' : 'business'

let toastSeq = 0

function useStoreValue() {
  const [liveSession, setLiveSession] = useState<SessionInfo | null>(null)
  const [sessionError, setSessionError] = useState<string | null>(null)
  const [sessionLoading, setSessionLoading] = useState(true)
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
  // The records the last search found (Records); "Go to" lists them too.
  const [records, setRecords] = useState<RecordItem[]>([])
  const [selectedRef, setSelectedRef] = useState<string | null>(null)
  const [openDocId, setOpenDocId] = useState<string | null>(null)
  const [resultsShown,setResultsShown]=useState(false)
  // The person's own Recently viewed list as Home last loaded it; the search list offers matching records from it
  // (task 0022) without asking the server again.
  const [recentRecords, setRecentRecords] = useState<RecentRecord[] | null>(null)

  useEffect(() => {
    let active = true
    loadSession().then(s => { if (active) setLiveSession(s) })
      .catch(e => { if (active) setSessionError(failureText('Session did not load', e)) })
      .finally(() => { if(active) setSessionLoading(false) })
    return () => { active = false }
  }, [])
  const role = ROLES.find((r) => r.id === primaryRole(liveSession?.roles ?? []))!
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

  const toast = useCallback((t: Omit<Toast, 'id'>) => {
    toastSeq += 1
    const id = toastSeq
    setToasts((list) => [...list.slice(-2), { ...t, id }])
    window.setTimeout(() => setToasts((list) => list.filter((x) => x.id !== id)), 4800)
  }, [])

  const caps = useMemo<Capability[]>(() => liveSession ? liveSession.capabilities.filter((c): c is Capability => (KNOWN_CAPABILITIES as readonly string[]).includes(c)) : [], [liveSession])
  const can = useCallback((c: Capability) => caps.includes(c), [caps])

  return {
    role, heldRoles, areas, startedWithoutRoute, searchRequest, requestSearch, liveSession, sessionLoading, sessionError, theme, themePref, setThemePref, route, navigate,
    toasts, toast, dismissToast: (id: number) => setToasts((l) => l.filter((t) => t.id !== id)),
    caps, can,
    records, setRecords, selectedRef, setSelectedRef, openDocId, setOpenDocId, resultsShown, setResultsShown, recentRecords, setRecentRecords,
  }
}

export function StoreProvider({ children }: { children: ReactNode }) {
  const value = useStoreValue()
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}
