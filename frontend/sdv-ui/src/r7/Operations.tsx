import { useCallback, useEffect, useState } from 'react'
import { ArrowRight, RefreshCw } from 'lucide-react'
import { opsAudit, opsDetail, opsRetention, systemInfo, type OpsAuditEvent, type OpsDetail, type OpsRetention, type SystemInfo } from './liveApi'
import { AREA_TABS, useStore } from './store'
import { AreaFrame } from './Governance'
import { FailureNotice, loadFailed, type Failure } from './CardError'
import { Problems } from './Problems'
import { AuditLog } from './AuditLog'
import { About } from './About'
import { auditSentence } from './auditWords'
import { Callout, Meter, Panel, fmtDateTime } from './ui'
import {
  SERVICE_KEYS, SERVICE_STATE_WORDS, SERVICE_WORDS, ago, asLiveVolume, canStoreWords, cleanupWords, fmtWhen, overviewSentence,
  problemKinds, problemTotal, serviceSentence, serviceState, servicesDown, storageRoleWord, storageWhatToDo, volumeNeedsAttention,
} from './opsWords'

// Task 0012 (change 9): Platform operations Overview, Storage (read-only) and Health in plain words; About is shared
// with Tenant IT. Codes, paths and raw fields are only under Technical details.

const LEDE: Record<string, string> = {
  overview: 'How Vero is doing right now.',
  problems: 'Documents Vero could not store, documents that are stuck, and deliveries with documents missing.',
  storage: 'How full each storage volume is. Tenant IT adds and changes volumes.',
  health: 'The services Vero depends on, and its automatic cleanup.',
  audit: 'Who did what, and when. Document content is never shown here.',
  about: 'The facts support will ask for.',
}

export function Operations() {
  const { route } = useStore()
  const tab = AREA_TABS.operations.find((t) => t.id === route.tab)!
  return (
    <AreaFrame crumbs={['Platform operations', tab.label]} title={tab.label} lede={LEDE[route.tab]}>
      {route.tab === 'overview' && <Overview />}
      {route.tab === 'problems' && <Problems role="platform" />}
      {route.tab === 'storage' && <OpsStorage />}
      {route.tab === 'health' && <Health />}
      {route.tab === 'audit' && <AuditLog />}
      {route.tab === 'about' && <About role="platform" />}
    </AreaFrame>
  )
}

/** The operations summary and the last cleanup run, with when they were read and how long the read took. */
function useOps(withCleanup: boolean) {
  const [data, setData] = useState<OpsDetail | null>(null)
  const [cleanup, setCleanup] = useState<OpsRetention | null>(null)
  const [cleanupError, setCleanupError] = useState(false)
  const [error, setError] = useState<Failure | null>(null)
  const [checkedAt, setCheckedAt] = useState<string | null>(null)
  const [tookMs, setTookMs] = useState<number | null>(null)
  const [loading, setLoading] = useState(false)
  const load = useCallback(async () => {
    setLoading(true); setError(null)
    const started = performance.now()
    const [summary, sweep] = await Promise.allSettled([opsDetail(), withCleanup ? opsRetention() : Promise.resolve(null)])
    setTookMs(Math.round(performance.now() - started))
    if (summary.status === 'fulfilled') { setData(summary.value); setCheckedAt(new Date().toISOString()) }
    else setError(loadFailed('This page did not load', summary.reason))
    if (sweep.status === 'fulfilled') { setCleanup(sweep.value); setCleanupError(false) } else setCleanupError(true)
    setLoading(false)
  }, [withCleanup])
  useEffect(() => { void load() }, [load])
  return { data, cleanup, cleanupError, error, checkedAt, tookMs, loading, load }
}

/** Re-renders every 30 seconds so "Checked 2 minutes ago" stays true. */
function useTick() {
  const [, setN] = useState(0)
  useEffect(() => { const t = window.setInterval(() => setN((n) => n + 1), 30_000); return () => window.clearInterval(t) }, [])
}

const ROUTINE = new Set(['AUDIT_VIEWED', 'BUSINESS_RECENT_RECORDS_LISTED', 'DOCUMENT_PAGE_VIEWED'])
const startOfToday = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d.toISOString() }

function Overview() {
  const { navigate } = useStore()
  const { data, cleanup, error, load } = useOps(true)
  const [latest, setLatest] = useState<OpsAuditEvent[] | null | undefined>(undefined)
  const [deletedToday, setDeletedToday] = useState<{ n: number; more: boolean } | null | undefined>(undefined)
  useEffect(() => {
    let live = true
    // Routine views (each look at the audit writes one) can fill the newest events, so read up to 3 pages back.
    ;(async () => {
      const found: OpsAuditEvent[] = []
      let cursor: string | undefined
      for (let i = 0; i < 3 && found.length < 5; i++) {
        const r = await opsAudit(cursor, {}, 100)
        found.push(...r.events.filter((e) => !ROUTINE.has(e.action)))
        if (!r.nextCursor) break
        cursor = r.nextCursor
      }
      return found.slice(0, 5)
    })().then((x) => { if (live) setLatest(x) }).catch(() => { if (live) setLatest(null) })
    opsAudit(undefined, { action: 'DOCUMENT_RETENTION_DELETED', outcome: 'SUCCESS', from: startOfToday() }, 200)
      .then((r) => { if (live) setDeletedToday({ n: r.events.length, more: r.hasMore }) }).catch(() => { if (live) setDeletedToday(null) })
    return () => { live = false }
  }, [])

  if (error) return <FailureNotice failure={error} loadTitle="The overview didn't load" onRetry={() => void load()} />
  if (!data) return <p className="muted">Loading…</p>
  const volumes = data.storageVolumes.map(asLiveVolume)
  const lead = overviewSentence(data, volumes, cleanup)
  const total = problemTotal(data)
  // The fullest volume that can take documents; one Tenant IT set offline or read only is left out on purpose.
  const active = volumes.filter((v) => v.volume.state === 'ACTIVE')
  const fullest = [...active].sort((a, b) => (b.capacity?.usedPercent ?? -1) - (a.capacity?.usedPercent ?? -1))[0]
  const unreachable = active.find((v) => v.observationError)
  const down = servicesDown(data)
  const sweep = cleanupWords(cleanup)
  const partial = data.dataObservations?.some((x) => x.status === 'DEGRADED')

  const tiles: { id: string; label: string; value: string; detail: string; tab: string; bad: boolean }[] = [
    { id: 'problems', label: 'Problems', tab: 'problems', bad: !!total,
      value: total == null ? 'Couldn’t count' : total === 0 ? 'None' : total.toLocaleString(),
      detail: total ? problemKinds(data) : total === 0 ? 'Every delivery arrived properly' : 'Open Problems to see what is listed' },
    { id: 'storage', label: 'Storage', tab: 'storage', bad: active.some(volumeNeedsAttention),
      value: unreachable ? 'Can’t reach' : fullest?.capacity?.usedPercent != null ? `${fullest.capacity.usedPercent}% full` : active.length ? 'Unknown' : 'No volume',
      detail: unreachable ? `${unreachable.volume.displayName} · Tenant IT checks the folder` : fullest ? `${fullest.volume.displayName} · warns at ${fullest.volume.warningPercent}%, stops at ${fullest.volume.stopWritePercent}%` : 'No volume can take new documents' },
    { id: 'services', label: 'Services', tab: 'health', bad: down.length > 0,
      value: down.length === 0 ? 'All working' : `${down.map((k) => SERVICE_WORDS[k].name).join(', ')} not working`,
      detail: 'Vero, database and key service' },
    { id: 'cleanup', label: 'Cleanup', tab: 'health', bad: sweep.tone === 'bad',
      value: sweep.tone === 'bad' ? 'Last run failed' : deletedToday === undefined ? 'Counting…' : deletedToday === null ? 'Couldn’t count' : deletedToday.n === 0 ? 'Nothing deleted today' : `${deletedToday.more ? 'More than ' : ''}${deletedToday.n.toLocaleString()} deleted today`,
      detail: cleanup?.completedAt ? `Documents past their keep date · last ran ${ago(cleanup.completedAt)}` : 'Documents past their keep date' },
  ]

  return <>
    <section className={`ov-lead ${lead.attention ? 'warn' : 'ok'}`} aria-label="How Vero is doing"><p>{lead.text}</p>
      {partial && <p className="muted">Some counts couldn’t be read just now, so this may not be complete.</p>}</section>
    <div className="v-signals ov-tiles" role="list" aria-label="Overview tiles">
      {tiles.map((t) => (
        <button key={t.id} type="button" role="listitem" className="v-signal" aria-label={`${t.label}: ${t.value}. ${t.detail}. Open`} onClick={() => navigate('operations', t.tab)}>
          <span className="k"><span className={`ov-dot ${t.bad ? 'bad' : 'ok'}`} aria-hidden />{t.label}<span className="ov-open">Open <ArrowRight size={13} aria-hidden /></span></span>
          <span className="v">{t.value}</span>
          <span className="d">{t.detail}</span>
        </button>
      ))}
    </div>
    <Panel flush title="Latest activity" actions={<button type="button" className="v-btn quiet sm" onClick={() => navigate('operations', 'audit')}>All activity <ArrowRight size={14} aria-hidden /></button>}>
      {latest === undefined ? <p className="muted" style={{ padding: 16 }}>Loading…</p>
        : latest === null ? <p className="muted" style={{ padding: 16 }}>The latest activity didn’t load. Open Audit to see it.</p>
        : latest.length === 0 ? <p className="muted" style={{ padding: 16 }}>Nothing lately besides routine views. All activity shows everything.</p>
        : <ul className="ov-activity" aria-label="Latest activity">{latest.map((e) => <li key={e.auditEventId}><span className="faint">{fmtWhen(e.occurredAt)}</span><span>{auditSentence(e)}</span></li>)}</ul>}
    </Panel>
  </>
}

function OpsStorage() {
  const { data, error, load } = useOps(false)
  if (error) return <FailureNotice failure={error} loadTitle="Storage didn't load" onRetry={() => void load()} />
  if (!data) return <p className="muted">Loading…</p>
  const volumes = data.storageVolumes.map(asLiveVolume)
  const todo = storageWhatToDo(volumes)
  return <>
    <Panel flush title="Storage volumes" meta={volumes.length}>
      {volumes.length === 0 ? <p className="muted" style={{ padding: 16 }}>No storage volume is set up. The customer’s Tenant IT adds one.</p> :
      <table className="v-table" aria-label="Storage volumes">
        <thead><tr><th>Volume</th><th>Role</th><th style={{ width: '26%' }}>How full</th><th>Can store new documents</th></tr></thead>
        <tbody>{volumes.map((v) => { const can = canStoreWords(v), pct = v.capacity?.usedPercent; return (
          <tr key={v.volume.volumeId}>
            <td className="lead" data-label="Volume"><strong style={{ fontWeight: 500 }}>{v.volume.displayName}</strong></td>
            <td data-label="Role">{storageRoleWord(volumes, v)}</td>
            <td data-label="How full">{pct == null ? <span className="faint">Not known</span> : <div className="row" style={{ gap: 10 }}><div className="grow" style={{ maxWidth: 180 }}><Meter pct={pct} warn={v.volume.warningPercent} stop={v.volume.stopWritePercent} label={`${v.volume.displayName} how full`} /></div><span>{pct}%</span></div>}</td>
            <td data-label="Can store new documents"><span className={`ov-can ${can.tone}`}>{can.text}</span></td>
          </tr>) })}</tbody>
      </table>}
    </Panel>
    <Callout tone={todo.length ? 'warn' : ''} title="What to do">
      {todo.length ? todo.map((t) => <p key={t} style={{ margin: '0 0 6px' }}>{t}</p>) : 'Nothing right now. The customer’s Tenant IT adds and changes volumes; this page only shows them.'}
    </Callout>
    <details className="st-tech"><summary>Technical details</summary>
      <table className="v-table" aria-label="Storage technical details"><thead><tr><th>Volume</th><th>Folder</th><th>Warns at · stops at</th><th>Order</th><th>Last check</th><th>Write test</th></tr></thead>
        <tbody>{data.storageVolumes.map((v) => <tr key={v.volumeId}>
          <td data-label="Volume">{v.displayName}<div className="mono faint">{v.volumeId}</div></td>
          <td data-label="Folder" className="mono">{v.rootPath ?? '—'}</td>
          <td data-label="Warns at · stops at">{v.warningPercent ?? '—'}% · {v.stopWritePercent ?? '—'}%</td>
          <td data-label="Order">{v.priority ?? '—'}</td>
          <td data-label="Last check">{v.observedAt ? fmtDateTime(v.observedAt) : '—'}</td>
          <td data-label="Write test">{v.state === 'OFFLINE' ? 'Not tested (offline)' : v.writable ? 'Passed' : 'Failed'}{v.observationError && v.state !== 'OFFLINE' && <div className="faint">{v.observationError}</div>}</td>
        </tr>)}</tbody></table>
    </details>
  </>
}

function Health() {
  useTick()
  const { data, cleanup, cleanupError, error, checkedAt, tookMs, loading, load } = useOps(true)
  const sweep = cleanupWords(cleanup)
  // Vero checks the database against its own version once, at start-up. While they don't match, every other
  // request is refused, so the sentence from system information replaces the generic "couldn't check" notice.
  const [schema, setSchema] = useState<SystemInfo['databaseSchema'] | null>(null)
  useEffect(() => {
    if (loading) return
    let live = true
    systemInfo('platform').then((i) => { if (live) setSchema(i.databaseSchema ?? null) }).catch(() => { if (live) setSchema(null) })
    return () => { live = false }
  }, [loading])
  const schemaOff = schema != null && schema.state !== 'CURRENT'
  return <>
    <div className="row ov-checked" style={{ justifyContent: 'flex-end', gap: 12 }}>
      <span className="muted" role="status">{checkedAt ? `Checked ${ago(checkedAt)}` : loading ? 'Checking…' : ''}</span>
      <button type="button" className="v-btn sm" disabled={loading} onClick={() => void load()}><RefreshCw size={14} aria-hidden />{loading ? 'Checking…' : 'Check now'}</button>
    </div>
    {schemaOff && <Callout tone="bad" title="The database doesn’t match this Vero version">{schema.message}
      <span className="muted" style={{ display: 'block', marginTop: 6 }}>Vero checks this when it starts. After the schema step, start Vero again.</span></Callout>}
    {error && !schemaOff && <FailureNotice failure={error} loadTitle="Vero couldn't check its services" onRetry={() => void load()} />}
    {data && <>
      {SERVICE_KEYS.map((k) => { const s = serviceSentence(k, data[k]?.status); return s && serviceState(data[k]?.status) !== 'notConfigured'
        ? <Callout key={k} tone="bad" title={`${SERVICE_WORDS[k].name} isn’t working`}>{s}</Callout> : null })}
      <Panel flush title="Services">
        <table className="v-table" aria-label="Services"><thead><tr><th>Service</th><th>What it does</th><th>Status</th></tr></thead>
          <tbody>{SERVICE_KEYS.map((k) => { const st = serviceState(data[k]?.status); return (
            <tr key={k}><td className="lead" data-label="Service"><strong style={{ fontWeight: 500 }}>{SERVICE_WORDS[k].name}</strong></td>
              <td data-label="What it does">{SERVICE_WORDS[k].does}</td>
              <td data-label="Status"><span className={`v-status ${st === 'working' ? 'ok' : st === 'notConfigured' ? 'warn' : 'bad'}`}>{SERVICE_STATE_WORDS[st]}</span>
                {st === 'notConfigured' && <div className="faint">{serviceSentence(k, data[k]?.status)}</div>}</td></tr>) })}</tbody></table>
      </Panel>
    </>}
    {!schemaOff && <section className={`v-panel ov-cleanup ${sweep.tone}`} aria-label="Automatic cleanup"><div className="v-panel-body">
      <h2 className="ov-h">Automatic cleanup</h2>
      {cleanupError ? <p>Vero couldn’t say how automatic cleanup is doing. Check now again.</p> : <>
        <p>{sweep.sentence}</p>
        {sweep.whatToDo && <p><strong>What to do:</strong> {sweep.whatToDo}</p>}
        <p className="muted">It runs by itself. If a run fails, it shows here in red with what to do.</p>
      </>}
    </div></section>}
    {data && <details className="st-tech"><summary>Technical details</summary>
      <dl className="v-kv">
        {SERVICE_KEYS.map((k) => <div key={k}><dt>{SERVICE_WORDS[k].name}</dt><dd><span className="mono">{data[k]?.component} · {data[k]?.status}</span><div className="faint">{data[k]?.message} · checked {fmtDateTime(data[k]?.observedAt)}</div></dd></div>)}
        <div><dt>Response time</dt><dd>{tookMs == null ? '—' : `${tookMs} ms for the whole check (the server reports no time per service)`}</dd></div>
        {cleanup && <div><dt>Cleanup, last run</dt><dd className="mono">outcome {cleanup.outcome} · started {cleanup.startedAt ? fmtDateTime(cleanup.startedAt) : '—'} · finished {cleanup.completedAt ? fmtDateTime(cleanup.completedAt) : '—'} · candidates {cleanup.candidates} · deleted {cleanup.deleted} · failed {cleanup.failed}{cleanup.reasonCode ? ` · ${cleanup.reasonCode}` : ''}</dd></div>}
        <div><dt>Overall</dt><dd className="mono">{data.overallStatus}</dd></div>
      </dl>
    </details>}
  </>
}
