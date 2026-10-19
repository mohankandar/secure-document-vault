import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  AUDIT, DEFAULT_ROLE_CAPS, IDENTITY, RECORDS, RETENTION, ROLES, SOURCES, VOLUMES,
  type AreaId, type AuditEvent, type Capability, type Doc, type IdentityConfig, type RecordItem, type RoleId, type Source, type Volume, NOW,
} from './data'

export type Scenario = 'live' | 'loading' | 'empty' | 'error'
export type Theme = 'light' | 'dark'
export type Toast = { id: number; title: string; detail?: string; tone?: 'sim' | 'ok' | 'bad' }
export type Route = { area: AreaId; tab: string }

export const AREA_TABS: Record<AreaId, { id: string; label: string }[]> = {
  workspace: [{ id: 'search', label: 'Search' }],
  governance: [{ id: 'access', label: 'Users & access' }, { id: 'roles', label: 'Roles & permissions' }, { id: 'retention', label: 'Retention policies' }, { id: 'metadata', label: 'Metadata configuration' }],
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

let toastSeq = 0
let eventSeq = 0

function useStoreValue() {
  const [roleId, setRoleId] = useState<RoleId>('steward')
  const [scenario, setScenario] = useState<Scenario>('live')
  const [theme, setThemeState] = useState<Theme>(() => (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'))
  const [route, setRouteState] = useState<Route>(() => parseHash() ?? { area: 'workspace', tab: 'search' })
  const [toasts, setToasts] = useState<Toast[]>([])
  const [roleCaps, setRoleCaps] = useState(DEFAULT_ROLE_CAPS)
  const [records, setRecords] = useState<RecordItem[]>(RECORDS)
  const [retention, setRetention] = useState(RETENTION)
  const [volumes, setVolumes] = useState<Volume[]>(VOLUMES)
  const [sources, setSources] = useState<Source[]>(SOURCES)
  const [identity, setIdentity] = useState(IDENTITY)
  const [audit, setAudit] = useState<AuditEvent[]>(AUDIT)
  const [selectedRef, setSelectedRef] = useState<string | null>(null)
  const [openDocId, setOpenDocId] = useState<string | null>(null)

  const role = ROLES.find((r) => r.id === roleId)!

  useEffect(() => {
    document.documentElement.dataset.theme = theme
  }, [theme])

  useEffect(() => {
    const onHash = () => { const next = parseHash(); if (next) setRouteState(next) }
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  const navigate = useCallback((area: AreaId, tab?: string) => {
    const next = { area, tab: tab ?? AREA_TABS[area][0].id }
    setRouteState(next)
    const hash = `#/${next.area}/${next.tab}`
    if (window.location.hash !== hash) window.history.pushState(null, '', hash)
  }, [])

  const switchRole = useCallback((id: RoleId) => {
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

  const caps = useMemo<Capability[]>(() => {
    if (role.code === 'BUSINESS_USER' || role.code === 'DOCUMENT_STEWARD') return roleCaps[role.code]
    return []
  }, [role.code, roleCaps])
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
    role, roleId, switchRole, scenario, setScenario, theme, setTheme: setThemeState, route, navigate,
    toasts, toast, dismissToast: (id: number) => setToasts((l) => l.filter((t) => t.id !== id)),
    roleCaps, setRoleCaps, caps, can,
    records, updateDoc, addDocActivity, selectedRef, setSelectedRef, openDocId, setOpenDocId,
    retention, setRetention, volumes, setVolumes, sources, setSources,
    identity, setIdentity: setIdentity as (fn: (prev: typeof IDENTITY) => typeof IDENTITY) => void, audit, recordAudit,
  }
}

export type { IdentityConfig }

export function StoreProvider({ children }: { children: ReactNode }) {
  const value = useStoreValue()
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}
