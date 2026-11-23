import { useCallback, useEffect, useState } from 'react'
import { opsAudit, opsDetail, opsRetention, systemInfo, type OpsAuditEvent, type OpsDetail, type OpsRetention, type SystemInfo } from './liveApi'
import { AREA_TABS, useStore } from './store'
import { AreaFrame } from './Governance'
import { FailureNotice, loadFailed, type Failure } from './CardError'
import { schemaWords } from './problemWords'
import { Problems } from './Problems'
import { AuditLog, AuditWords } from './AuditLog'
import { isMeaningfulActivity } from './auditWords'
import { About } from './About'
import { Banner, Callout, Panel, TechDetails, fmtDateTime } from './ui'
import {
  SERVICE_KEYS, SERVICE_STATE_WORDS, SERVICE_WORDS, ago, asLiveVolume, canStoreWords, cleanupWords, fmtWhen, overviewSentence, whenNext,
  problemKinds, problemTotal, serviceSentence, serviceState, servicesDown, storageRoleWord, storageWhatToDo, volumeNeedsAttention,
} from './opsWords'

// Task 0012 (change 9): Platform operations Overview, Storage (read-only) and Health in plain words; About is shared
// with IT. Codes, paths and raw fields are only under Technical details.

const LEDE: Record<string, string> = {
  overview: 'How Vero is doing right now.',
  problems: 'Documents that could not be stored, and deliveries still missing documents.',
  storage: 'How full each storage volume is. IT adds and changes volumes.',
  health: 'The services Vero depends on, and its automatic cleanup.',
  audit: 'Everything people and systems did in Vero. Document content is never shown here.',
  about: 'The facts support will ask for.',
}

export function Operations() {
  const { route } = useStore()
  const tab = AREA_TABS.operations.find((t) => t.id === route.tab)!
  return (
    <AreaFrame crumbs={['Platform operations', tab.label]} title={route.tab === 'about' ? 'About this installation' : tab.label} lede={LEDE[route.tab]}>
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

const startOfToday = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d.toISOString() }

function Overview() {
  const { navigate } = useStore()
  const { data, cleanup, error, load } = useOps(true)
  const [latest, setLatest] = useState<OpsAuditEvent[] | null | undefined>(undefined)
  const [deletedToday, setDeletedToday] = useState<{ n: number; more: boolean } | null | undefined>(undefined)
  useEffect(() => {
    let live = true
    // Task 0020 (item 2): meaningful events only. Searches and routine reads can fill the newest events, so read up to 5 pages back.
    ;(async () => {
      const found: OpsAuditEvent[] = []
      let cursor: string | undefined
      for (let i = 0; i < 5 && found.length < 5; i++) {
        const r = await opsAudit(cursor, {}, 100)
        found.push(...r.events.filter(isMeaningfulActivity))
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
  // The fullest volume that can take documents; one IT set offline or read only is left out on purpose.
  const active = volumes.filter((v) => v.volume.state === 'ACTIVE')
  const fullest = [...active].sort((a, b) => (b.capacity?.usedPercent ?? -1) - (a.capacity?.usedPercent ?? -1))[0]
  const unreachable = active.find((v) => v.observationError)
  const down = servicesDown(data)
  const sweep = cleanupWords(cleanup)
  const nextRun = whenNext(cleanup?.nextRunAt)
  const partial = data.dataObservations?.some((x) => x.status === 'DEGRADED')

  const tiles: { id: string; label: string; value: string; detail: string; tab: string; bad: boolean; tone?: string }[] = [
    { id: 'problems', label: 'Problems', tab: 'problems', bad: !!total,
      value: total == null ? 'Couldn’t count' : total === 0 ? 'None' : total.toLocaleString(),
      detail: total ? problemKinds(data) : total === 0 ? 'Every delivery arrived properly' : 'Open Problems to see what is listed' },
    { id: 'storage', label: 'Storage', tab: 'storage', bad: active.some(volumeNeedsAttention),
      value: unreachable ? 'Can’t reach' : fullest?.capacity?.usedPercent != null ? `${fullest.capacity.usedPercent}% full` : active.length ? 'Unknown' : 'No volume',
      detail: unreachable ? `${unreachable.volume.displayName} · IT checks the folder` : fullest ? `${fullest.volume.displayName} · warns at ${fullest.volume.warningPercent}%, stops at ${fullest.volume.stopWritePercent}%` : 'No volume can take new documents' },
    { id: 'services', label: 'Services', tab: 'health', bad: down.length > 0,
      value: down.length === 0 ? 'All working' : `${down.map((k) => SERVICE_WORDS[k].name).join(', ')} not working`,
      detail: 'Vero, database and key service' },
    { id: 'cleanup', label: 'Cleanup', tab: 'health', bad: sweep.tone === 'bad',
      value: sweep.tone === 'bad' ? 'Last run failed' : deletedToday === undefined ? 'Counting…' : deletedToday === null ? 'Couldn’t count' : deletedToday.n === 0 ? 'Nothing deleted today' : `${deletedToday.more ? 'More than ' : ''}${deletedToday.n.toLocaleString()} deleted today`,
      // Canvas: "next run tomorrow 07:00"; when the server can't say when, the last run as before (nothing guessed).
      detail: nextRun ? `Documents past their keep date · next run ${nextRun}` : cleanup?.completedAt ? `Documents past their keep date · last ran ${ago(cleanup.completedAt)}` : 'Documents past their keep date' },
  ]
  // Canvas: the value's colour says how it is (problems red, storage amber when it needs attention, services green).
  for (const t of tiles) t.tone = t.id === 'problems' ? (t.bad ? 'bad' : '') : t.id === 'storage' ? (t.bad ? 'warn' : '') : t.id === 'services' ? (t.bad ? 'bad' : 'ok') : t.bad ? 'bad' : ''

  return <>
    <section className={`v-banner ${lead.attention ? 'warn' : 'ok'}`} aria-label="How Vero is doing">
      {(() => { const m = lead.text.match(/^(.*?[.!?])\s+(.*)$/); return m ? <><b>{m[1]}</b> {m[2]}</> : <b>{lead.text}</b> })()}
      {partial && <span className="muted"> Some counts couldn’t be read just now, so this may not be complete.</span>}</section>
    <div className="ov-tiles" role="list" aria-label="Overview tiles">
      {tiles.map((t) => (
        <button key={t.id} type="button" role="listitem" className={`ov-tile ${t.tone}`} aria-label={`${t.label}: ${t.value}. ${t.detail}. Open`} onClick={() => navigate('operations', t.tab)}>
          <span className="k">{t.label}</span>
          <span className="v">{t.value}</span>
          <span className="d">{t.detail}</span>
          <span className="ov-open">Open →</span>
        </button>
      ))}
    </div>
    <Panel flush title="Latest activity" actions={<button type="button" className="v-link" onClick={() => navigate('operations', 'audit')}>All activity →</button>}>
      {latest === undefined ? <p className="muted" style={{ padding: 16 }}>Loading…</p>
        : latest === null ? <p className="muted" style={{ padding: 16 }}>The latest activity didn’t load. Open Audit to see it.</p>
        : latest.length === 0 ? <p className="muted" style={{ padding: 16 }}>Nothing lately besides searches and routine views. All activity shows everything.</p>
        : <ul className="ov-activity" aria-label="Latest activity">{latest.map((e) => <li key={e.auditEventId}><span className="faint">{fmtWhen(e.occurredAt)}</span><span><AuditWords e={e} /></span></li>)}</ul>}
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
    <section className="v-panel">
      {volumes.length === 0 ? <p className="muted" style={{ padding: 16 }}>No storage volume is set up. The customer’s IT adds one.</p> :
      <table className="v-table" aria-label="Storage volumes">
        <thead><tr><th>Volume</th><th>Role</th><th style={{ width: '26%' }}>How full</th><th>Can store new documents</th></tr></thead>
        <tbody>{volumes.map((v) => { const can = canStoreWords(v), pct = v.capacity?.usedPercent; return (
          <tr key={v.volume.volumeId}>
            <td className="lead" data-label="Volume"><strong style={{ fontWeight: 500 }}>{v.volume.displayName}</strong></td>
            <td data-label="Role">{storageRoleWord(volumes, v)}</td>
            <td data-label="How full">{pct == null ? <span className="faint">Not known</span> : <div className="row" style={{ gap: 10 }}><span className="v-bar2" role="img" aria-label={`${v.volume.displayName} is ${pct}% full`}><i className={pct >= (v.volume.stopWritePercent ?? 95) ? 'r' : pct >= (v.volume.warningPercent ?? 80) ? 'y' : ''} style={{ width: `${Math.min(pct, 100)}%` }} /></span><span>{pct}%</span></div>}</td>
            <td data-label="Can store new documents"><span className={`v-dot ${can.tone === 'ok' ? 'g' : can.tone === 'warn' ? 'y' : can.tone === 'bad' ? 'r' : ''}`} aria-hidden />{can.text}</td>
          </tr>) })}</tbody>
      </table>}
    </section>
    {todo.length ? <Banner lead="What to do:">{todo.join(' ')}</Banner>
      : <Banner tone="info" lead="What to do:">Nothing right now. The customer’s IT adds and changes volumes; this page only shows them.</Banner>}
    <TechDetails hint="folder paths, thresholds, last check, write test result">
      <table className="v-table" aria-label="Storage technical details"><thead><tr><th>Volume</th><th>Folder</th><th>Warns at · stops at</th><th>Order</th><th>Last check</th><th>Write test</th></tr></thead>
        <tbody>{data.storageVolumes.map((v) => <tr key={v.volumeId}>
          <td data-label="Volume">{v.displayName}<div className="mono faint">{v.volumeId}</div></td>
          <td data-label="Folder" className="mono">{v.rootPath ?? '—'}</td>
          <td data-label="Warns at · stops at">{v.warningPercent ?? '—'}% · {v.stopWritePercent ?? '—'}%</td>
          <td data-label="Order">{v.priority ?? '—'}</td>
          <td data-label="Last check">{v.observedAt ? fmtDateTime(v.observedAt) : '—'}</td>
          <td data-label="Write test">{v.state === 'OFFLINE' ? 'Not tested (offline)' : v.writable ? 'Passed' : 'Failed'}{v.observationError && v.state !== 'OFFLINE' && <div className="faint">{v.observationError}</div>}</td>
        </tr>)}</tbody></table>
    </TechDetails>
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
    <div className="row ov-checked" style={{ justifyContent: 'space-between', gap: 12 }}>
      <span className="faint" role="status">{checkedAt ? `Checked ${ago(checkedAt)}` : loading ? 'Checking…' : ''}</span>
      <button type="button" className="v-btn sm" disabled={loading} onClick={() => void load()}>{loading ? 'Checking…' : 'Check now'}</button>
    </div>
    {schemaOff && <Callout tone="bad" title="The database doesn’t match this Vero version">{schemaWords(schema.state)}
      <span className="muted" style={{ display: 'block', marginTop: 6 }}>Vero checks this when it starts. After the schema step, start Vero again.</span></Callout>}
    {error && !schemaOff && <FailureNotice failure={error} loadTitle="Vero couldn't check its services" onRetry={() => void load()} />}
    {data && <>
      {SERVICE_KEYS.map((k) => { const s = serviceSentence(k, data[k]?.status); return s && serviceState(data[k]?.status) !== 'notConfigured'
        ? <Callout key={k} tone="bad" title={`${SERVICE_WORDS[k].name} isn’t working`}>{s}</Callout> : null })}
      <section className="v-panel">
        <table className="v-table" aria-label="Services"><thead><tr><th>Service</th><th>What it does</th><th>Status</th></tr></thead>
          <tbody>{SERVICE_KEYS.map((k) => { const st = serviceState(data[k]?.status); return (
            <tr key={k}><td className="lead" data-label="Service"><strong style={{ fontWeight: 500 }}>{SERVICE_WORDS[k].name}</strong></td>
              <td data-label="What it does">{SERVICE_WORDS[k].does}</td>
              <td data-label="Status"><span className={`v-dot ${st === 'working' ? 'g' : st === 'notConfigured' ? 'y' : 'r'}`} aria-hidden />{SERVICE_STATE_WORDS[st]}
                {st === 'notConfigured' && <div className="faint">{serviceSentence(k, data[k]?.status)}</div>}</td></tr>) })}</tbody></table>
      </section>
    </>}
    {!schemaOff && <section className={`v-panel ov-cleanup ${sweep.tone}`} aria-label="Automatic cleanup"><div className="v-panel-body">
      <h2 className="v-sec">Automatic cleanup</h2>
      {cleanupError ? <p>Vero couldn’t say how automatic cleanup is doing. Check now again.</p> : <>
        <p className="ov-sweep"><span className={`v-dot ${sweep.tone === 'bad' ? 'r' : 'g'}`} aria-hidden />{sweep.sentence}</p>
        {sweep.whatToDo && <p><strong>What to do:</strong> {sweep.whatToDo}</p>}
        <p className="faint">It runs by itself. If a run fails, it shows here in red with what to do, and in Problems.</p>
      </>}
    </div></section>}
    {data && <TechDetails hint="component names, response times, raw cleanup fields">
      <dl className="v-kv">
        {SERVICE_KEYS.map((k) => <div key={k}><dt>{SERVICE_WORDS[k].name}</dt><dd><span className="mono">{data[k]?.component} · {data[k]?.status}</span><div className="faint">checked {fmtDateTime(data[k]?.observedAt)}</div></dd></div>)}
        <div><dt>Response time</dt><dd>{tookMs == null ? '—' : `${tookMs} ms for the whole check (the server reports no time per service)`}</dd></div>
        {cleanup && <div><dt>Cleanup, last run</dt><dd className="mono">outcome {cleanup.outcome} · started {cleanup.startedAt ? fmtDateTime(cleanup.startedAt) : '—'} · finished {cleanup.completedAt ? fmtDateTime(cleanup.completedAt) : '—'} · candidates {cleanup.candidates} · deleted {cleanup.deleted} · failed {cleanup.failed}{cleanup.reasonCode ? ` · ${cleanup.reasonCode}` : ''}</dd></div>}
        <div><dt>Overall</dt><dd className="mono">{data.overallStatus}</dd></div>
      </dl>
    </TechDetails>}
  </>
}
