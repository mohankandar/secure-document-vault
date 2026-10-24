import { useEffect, useMemo, useState } from 'react'
import { opSummary, opsDetail, opsAudit, opsRetention, systemInfo, type OpSummary, type OpsDetail, type OpsAuditEvent } from './liveApi'
import { ArrowRight, CheckCircle2, ChevronRight, FileWarning, HardDrive, PackageOpen, Timer } from 'lucide-react'
import { AREA_TABS, useStore } from './store'
import { FAILURES, RETENTION_SWEEP, SERVICES, STUCK, SUBMISSIONS, type AuditEvent, type Submission } from './data'
import { AreaFrame } from './Governance'
import { EnvironmentPanel, StorageTab, volumeAttention } from './TenantAdmin'
import { Badge, Callout, Drawer, Panel, Scenario, Seg, Status, fmtDateTime, fmtRel, humanize, toneFor } from './ui'

const LEDE: Record<string, string> = {
  overview: 'What needs attention across this deployment right now.',
  intake: 'Submissions arriving from producer systems through the ingestion API, and where they are in processing.',
  storage: 'Capacity and health of processing storage. Edit volumes in Tenant IT.',
  health: 'Live status of the services SDV depends on.',
  incidents: 'Processing failures, stuck transitions and incomplete submissions.',
  audit: 'Append-only technical evidence. Document content is never shown here.',
  configuration: 'Deployment facts for this customer environment.',
}

const incomplete = SUBMISSIONS.filter((s) => s.status === 'INCOMPLETE')

export function Operations() {
  const { route } = useStore()
  const tab = AREA_TABS.operations.find((t) => t.id === route.tab)!
  const attention: string[] = [] // Never derive live operational badges from prototype fixtures.
  return (
    <AreaFrame crumbs={['Platform operations', tab.label]} title={tab.label} lede={LEDE[route.tab]} attention={attention}>
      {route.tab === 'overview' && <ConnectedOverview />}
      {route.tab === 'intake' && <LiveOperationsSection section="intake" />}
      {route.tab === 'storage' && <LiveOperationsSection section="storage" />}
      {route.tab === 'health' && <LiveOperationsSection section="health" />}
      {route.tab === 'incidents' && <LiveOperationsSection section="incidents" />}
      {route.tab === 'audit' && <LiveOperationsSection section="audit" />}
      {route.tab === 'configuration' && <LiveOperationsSection section="configuration" />}
    </AreaFrame>
  )
}

function Overview() {
  const { navigate, volumes, audit } = useStore()
  const storageAttn = volumes.filter(volumeAttention).length
  const degraded = SERVICES.filter((s) => s.status !== 'UP')
  const signals = [
    { k: 'Processing failures', v: FAILURES.length, d: `Oldest ${fmtRel(FAILURES[FAILURES.length - 1].at)}`, tab: 'incidents', Icon: FileWarning, bad: FAILURES.length > 0 },
    { k: 'Stuck transitions', v: STUCK.length, d: STUCK.length ? `In ${STUCK[0].state.toLowerCase()} for ${fmtRel(STUCK[0].since).replace(' ago', '')}` : 'None', tab: 'incidents', Icon: Timer, bad: STUCK.length > 0 },
    { k: 'Incomplete submissions', v: incomplete.length, d: 'Awaiting producer documents', tab: 'intake', Icon: PackageOpen, bad: incomplete.length > 0 },
    { k: 'Storage attention', v: storageAttn, d: storageAttn ? 'Above warning threshold' : 'All volumes healthy', tab: 'storage', Icon: HardDrive, bad: storageAttn > 0 },
  ]
  return (
    <Scenario what="operational overview" rows={8} empty={<Callout tone="ok" title="Nothing needs attention">All services are healthy and no submissions are waiting.</Callout>}>
      {degraded.length > 0 && <Callout tone="warn" title={`${degraded.length} service degraded`}>{degraded.map((s) => `${s.name}: ${s.detail}.`).join(' ')}</Callout>}
      <div className="v-signals" role="list">
        {signals.map((s) => (
          <button key={s.k} type="button" role="listitem" className="v-signal" onClick={() => navigate('operations', s.tab)}>
            <span className="k"><s.Icon size={16} aria-hidden style={{ color: s.bad ? 'var(--attention)' : 'var(--ink-3)' }} />{s.k}<ChevronRight size={14} aria-hidden style={{ marginLeft: 'auto' }} /></span>
            <span className="v">{s.v}</span>
            <span className="d">{s.d}</span>
          </button>
        ))}
      </div>
      <div className="v-grid-2">
        <Panel flush title="Core services" actions={<button type="button" className="v-btn quiet sm" onClick={() => navigate('operations', 'health')}>Details<ArrowRight size={14} aria-hidden /></button>}>
          <ServiceTable />
        </Panel>
        <div className="stack-lg">
          <Panel title="Retention sweep" actions={<Status value={RETENTION_SWEEP.outcome} label="Succeeded" />}>
            <dl className="v-kv cols">
              <div><dt>Last run</dt><dd>{fmtRel(RETENTION_SWEEP.completedAt)}</dd></div>
              <div><dt>Expired · deleted</dt><dd>{RETENTION_SWEEP.candidates} · {RETENTION_SWEEP.deleted}</dd></div>
            </dl>
          </Panel>
          <Panel flush title="Latest audit" actions={<button type="button" className="v-btn quiet sm" onClick={() => navigate('operations', 'audit')}>All<ArrowRight size={14} aria-hidden /></button>}>
            <ul className="v-timeline" style={{ padding: 16 }}>
              {audit.slice(0, 4).map((e) => <li key={e.id}><span className={`pt ${e.id.startsWith('sim') ? 'sim' : toneFor(e.outcome) === 'ok' ? 'ok' : 'bad'}`} aria-hidden /><div className="stack" style={{ gap: 0 }}><strong>{humanize(e.action)}</strong><span>{e.actor} · {fmtRel(e.at)}</span></div></li>)}
            </ul>
          </Panel>
        </div>
      </div>
    </Scenario>
  )
}

function ServiceTable() {
  return (
    <table className="v-table">
      <thead><tr><th>Service</th><th>Status</th><th>Detail</th><th className="num">Latency</th></tr></thead>
      <tbody>{SERVICES.map((s) => (
        <tr key={s.name}><td className="lead" data-label="Service"><strong style={{ fontWeight: 500 }}>{s.name}</strong></td><td data-label="Status"><Status value={s.status} label={s.status === 'UP' ? 'Healthy' : humanize(s.status)} /></td><td data-label="Detail" className="muted">{s.detail}</td><td className="num" data-label="Latency">{s.latencyMs != null ? `${s.latencyMs} ms` : '—'}</td></tr>
      ))}</tbody>
    </table>
  )
}

function Health() {
  return <Panel flush title="Core services" description={`Observed ${fmtDateTime(new Date().toISOString())}`}><Scenario what="service health"><ServiceTable /></Scenario></Panel>
}

const STAGES = [
  { id: 'OPEN', label: 'Submission created', d: 'Producer declares expected documents' },
  { id: 'RECEIVING', label: 'Receiving documents', d: 'Each PDF uploaded independently' },
  { id: 'FINALIZE', label: 'Finalization', d: 'Encrypt, store, index metadata' },
  { id: 'COMPLETE', label: 'Available', d: 'Searchable by authorized users' },
]

function Intake() {
  const { navigate, setSelectedRef } = useStore()
  const [sel, setSel] = useState<Submission | null>(null)
  const counts = { OPEN: SUBMISSIONS.filter((s) => s.status === 'OPEN').length, RECEIVING: SUBMISSIONS.filter((s) => s.status === 'RECEIVING').length, FINALIZE: STUCK.length + FAILURES.length, COMPLETE: 48211 }
  return (
    <>
      <section className="v-panel" aria-label="Ingestion pipeline">
        <div className="v-signals" style={{ border: 0, borderRadius: 0 }}>
          {STAGES.map((s) => (
            <div key={s.id} className="v-signal" style={{ cursor: 'default' }}>
              <span className="k">{s.label}</span>
              <span className="v">{counts[s.id as keyof typeof counts].toLocaleString()}</span>
              <span className="d">{s.d}</span>
            </div>
          ))}
        </div>
      </section>
      <Panel flush title="Submissions needing attention" meta={SUBMISSIONS.length}>
        <Scenario what="submissions" empty={<p className="faint" style={{ padding: 16 }}>No open or incomplete submissions.</p>}>
          <table className="v-table">
            <thead><tr><th>Producer submission</th><th>Source</th><th>Status</th><th className="num">Documents</th><th>Created</th><th className="act"><span className="sr-only">Open</span></th></tr></thead>
            <tbody>{SUBMISSIONS.map((s) => {
              const got = s.expected.filter((e) => e.state === 'RECEIVED').length
              return (
                <tr key={s.id} className="clickable" onClick={() => setSel(s)}>
                  <td className="lead" data-label="Submission"><div className="stack" style={{ gap: 0 }}><span className="mono" style={{ fontWeight: 500 }}>{s.sourceSubmissionId}</span><span className="mono faint" style={{ fontSize: 12 }}>{s.id}</span></div></td>
                  <td data-label="Source">{s.source}</td>
                  <td data-label="Status"><Status value={s.status} /></td>
                  <td className="num" data-label="Documents">{got} / {s.expected.length}</td>
                  <td data-label="Created">{fmtRel(s.createdAt)}</td>
                  <td className="act"><button type="button" className="v-btn sm" onClick={(e) => { e.stopPropagation(); setSel(s) }}>Inspect</button></td>
                </tr>
              )
            })}</tbody>
          </table>
        </Scenario>
      </Panel>
      <Callout title="Ingestion in SDV is API-driven">
        Producer systems create a submission, upload each PDF, and SDV finalizes it. The current product has no operator upload, scanning, package-building or manual-review screens, so none are shown here. Recovery happens through the producer API (retry finalization) or by the producer re-sending.
      </Callout>
      {sel && (
        <Drawer eyebrow={sel.source} title={sel.sourceSubmissionId} onClose={() => setSel(null)}
          footer={sel.recordRef ? <button type="button" className="v-btn" onClick={() => { setSel(null); setSelectedRef(sel.recordRef); navigate('operations', 'incidents') }}>View related incidents</button> : undefined}>
          <dl className="v-kv cols">
            <div><dt>Submission ID</dt><dd className="mono">{sel.id}</dd></div><div><dt>Status</dt><dd><Status value={sel.status} /></dd></div>
            <div><dt>Created</dt><dd>{fmtDateTime(sel.createdAt)}</dd></div><div><dt>Linked record</dt><dd className="mono">{sel.recordRef ?? 'Assigned on first document'}</dd></div>
          </dl>
          <div className="stack" style={{ gap: 8 }}>
            <h3>Expected documents</h3>
            <ol className="v-timeline">
              {sel.expected.map((e) => <li key={e.name}><span className={`pt ${e.state === 'RECEIVED' ? 'ok' : ''}`} aria-hidden /><div className="row between grow"><strong>{e.name}</strong><Badge tone={e.state === 'RECEIVED' ? 'ok' : 'warn'}>{humanize(e.state)}</Badge></div></li>)}
            </ol>
          </div>
          <Callout tone="sim" title="No operator action available">
            Retry finalization exists only in the producer ingestion API (<span className="mono">POST …/documents/{'{id}'}/retry-finalization</span>). This screen shows state; it does not trigger recovery.
          </Callout>
        </Drawer>
      )}
    </>
  )
}

function Incidents() {
  return (
    <>
      <Panel flush title="Processing failures" meta={FAILURES.length}>
        <Scenario what="processing failures" empty={<p className="faint" style={{ padding: 16 }}><CheckCircle2 size={14} aria-hidden /> No processing failures.</p>}>
          <table className="v-table">
            <thead><tr><th>Document</th><th>Source</th><th>Error</th><th className="num">Attempts</th><th>When</th></tr></thead>
            <tbody>{FAILURES.map((f) => (
              <tr key={f.id}><td className="lead" data-label="Document"><div className="stack" style={{ gap: 0 }}><span style={{ fontWeight: 500 }}>{f.filename}</span><span className="mono faint" style={{ fontSize: 12 }}>{f.id} · {f.submissionId}</span></div></td><td data-label="Source">{f.source}</td><td data-label="Error"><Badge tone="bad">{f.errorCode}</Badge></td><td className="num" data-label="Attempts">{f.attempts}</td><td data-label="When">{fmtRel(f.at)}</td></tr>
            ))}</tbody>
          </table>
        </Scenario>
      </Panel>
      <Panel flush title="Stuck transitions" meta={STUCK.length}>
        <Scenario what="stuck transitions">
          <table className="v-table">
            <thead><tr><th>Document</th><th>State</th><th>Since</th><th>Submission</th></tr></thead>
            <tbody>{STUCK.map((s) => <tr key={s.id}><td className="lead" data-label="Document"><div className="stack" style={{ gap: 0 }}><span style={{ fontWeight: 500 }}>{s.filename}</span><span className="mono faint" style={{ fontSize: 12 }}>{s.id}</span></div></td><td data-label="State"><Badge tone="warn">{s.state}</Badge></td><td data-label="Since">{fmtRel(s.since)}</td><td data-label="Submission"><span className="mono">{s.submissionId}</span></td></tr>)}</tbody>
          </table>
        </Scenario>
      </Panel>
      <Panel flush title="Incomplete submissions" meta={incomplete.length}>
        <Scenario what="incomplete submissions">
          <table className="v-table">
            <thead><tr><th>Submission</th><th>Source</th><th className="num">Received</th><th>Created</th></tr></thead>
            <tbody>{incomplete.map((s) => <tr key={s.id}><td className="lead" data-label="Submission"><span className="mono">{s.sourceSubmissionId}</span></td><td data-label="Source">{s.source}</td><td className="num" data-label="Received">{s.expected.filter((e) => e.state === 'RECEIVED').length} of {s.expected.length}</td><td data-label="Created">{fmtRel(s.createdAt)}</td></tr>)}</tbody>
          </table>
        </Scenario>
      </Panel>
    </>
  )
}

function Audit() {
  const { audit } = useStore()
  const [outcome, setOutcome] = useState<'ALL' | 'SUCCESS' | 'DENIED' | 'FAILED'>('ALL')
  const [q, setQ] = useState('')
  const [sel, setSel] = useState<AuditEvent | null>(null)
  const [shown, setShown] = useState(8)
  const rows = useMemo(() => audit.filter((e) => (outcome === 'ALL' || e.outcome === outcome) && (!q || `${e.action} ${e.actor} ${e.target} ${e.correlationId}`.toLowerCase().includes(q.toLowerCase()))), [audit, outcome, q])
  return (
    <>
      <div className="row wrap" style={{ gap: 12 }}>
        <input className="v-input" style={{ flex: '1 1 240px' }} aria-label="Filter audit events" placeholder="Filter by action, actor, target or correlation ID" value={q} onChange={(e) => setQ(e.target.value)} />
        <Seg label="Outcome" value={outcome} onChange={setOutcome} options={[{ value: 'ALL', label: 'All' }, { value: 'SUCCESS', label: 'Success' }, { value: 'DENIED', label: 'Denied' }, { value: 'FAILED', label: 'Failed' }]} />
      </div>
      <Panel flush title="Audit events" meta={rows.length}>
        <Scenario what="audit events" rows={8}>
          {rows.length === 0 ? <p className="faint" style={{ padding: 16 }}>No events match these filters.</p> : (
            <table className="v-table">
              <thead><tr><th>When</th><th>Action</th><th>Actor</th><th>Target</th><th>Outcome</th></tr></thead>
              <tbody>{rows.slice(0, shown).map((e) => (
                <tr key={e.id} className="clickable" tabIndex={0} onClick={() => setSel(e)} onKeyDown={(k) => { if (k.key === 'Enter') setSel(e) }}>
                  <td className="lead" data-label="When"><span className="row between"><span style={{ fontWeight: 500 }}>{humanize(e.action)}</span><span className="faint" style={{ fontSize: 13 }}>{fmtDateTime(e.at)}</span></span></td>
                  <td data-label="Action" className="mono" style={{ fontSize: 12 }}>{e.action}</td>
                  <td data-label="Actor">{e.actor} <span className="faint">· {e.actorType.toLowerCase()}</span></td>
                  <td data-label="Target" className="mono" style={{ fontSize: 12 }}>{e.target}</td>
                  <td data-label="Outcome"><Status value={e.outcome} />{e.id.startsWith('sim') && <> <Badge tone="warn">simulated</Badge></>}</td>
                </tr>
              ))}</tbody>
            </table>
          )}
        </Scenario>
      </Panel>
      {shown < rows.length && <button type="button" className="v-btn" style={{ alignSelf: 'center' }} onClick={() => setShown((n) => n + 8)}>Load older events</button>}
      {sel && (
        <Drawer eyebrow="Audit evidence" title={humanize(sel.action)} onClose={() => setSel(null)}>
          <dl className="v-kv">
            <div><dt>Outcome</dt><dd><Status value={sel.outcome} /></dd></div>
            <div><dt>Occurred</dt><dd>{fmtDateTime(sel.at)}</dd></div>
            <div><dt>Actor</dt><dd>{sel.actor} · {sel.actorType}</dd></div>
            <div><dt>Target</dt><dd className="mono">{sel.targetType} · {sel.target}</dd></div>
            <div><dt>Correlation ID</dt><dd className="mono">{sel.correlationId ?? '—'}</dd></div>
            <div><dt>Event ID</dt><dd className="mono">{sel.id}</dd></div>
          </dl>
          <div className="stack" style={{ gap: 6 }}>
            <h3>Safe details</h3>
            <pre className="mono" style={{ margin: 0, padding: 12, borderRadius: 7, background: 'var(--surface-2)', fontSize: 13, overflowX: 'auto' }}>{JSON.stringify(sel.details, null, 2)}</pre>
            <small className="faint">Audit details never include document content or personal data.</small>
          </div>
        </Drawer>
      )}
    </>
  )
}


function ConnectedOverview(){
 const {navigate}=useStore()
 const [data,setData]=useState<OpSummary|null>(null)
 const [error,setError]=useState('')
 useEffect(()=>{let live=true;opSummary().then(d=>{if(live)setData(d)}).catch(e=>{if(live)setError(String(e))});return()=>{live=false}},[])
 const signals=data?[
  {label:'Processing failures',count:data.processingFailureCount,description:'Documents requiring investigation',tab:'incidents',Icon:FileWarning},
  {label:'Stuck documents',count:data.stuckDocumentCount,description:'Documents stalled in processing',tab:'incidents',Icon:Timer},
  {label:'Attention submissions',count:data.attentionSubmissionCount,description:'Incomplete or attention-required intake',tab:'intake',Icon:PackageOpen}
 ] as const:[]
 return <>{error&&<Callout tone="warn" title="Operations unavailable">{error}</Callout>}
 {!data&&!error&&<Callout title="Loading operations">Reading operational summary…</Callout>}
 {data&&<>
 <Panel title="Platform status" actions={<Status value={data.overallStatus}/>} description="Live operational counts and core service health reported by SDV.">
  <div className="v-signals" role="list">{signals.map(x=><button key={x.label} type="button" role="listitem" className="v-signal" onClick={()=>navigate('operations',x.tab)}>
   <span className="k"><x.Icon size={16} aria-hidden style={{color:x.count!=null&&x.count>0?'var(--attention)':'var(--ink-3)'}}/>{x.label}<ChevronRight size={14} aria-hidden style={{marginLeft:'auto'}}/></span>
   <span className="v">{x.count==null?'—':x.count.toLocaleString()}</span><span className="d">{x.count==null?'Not available from backend':x.description}</span>
  </button>)}</div>
 </Panel>
 <Panel flush title="Core services" actions={<button type="button" className="v-btn quiet sm" onClick={()=>navigate('operations','health')}>Details <ArrowRight size={14} aria-hidden/></button>}><table className="v-table"><thead><tr><th>Service</th><th>Status</th><th>Details</th></tr></thead><tbody>{[data.application,data.database,data.keyService].map(x=><tr key={x.component}><td className="lead">{x.component}</td><td><Status value={x.status}/></td><td>{x.message}</td></tr>)}</tbody></table></Panel>
 {data.dataObservations.some(x=>x.status==='DEGRADED')&&<Callout tone="warn" title="Incomplete operational data">Some sections are degraded; do not interpret missing counts as zero.</Callout>}
 </>}
 </>
}


function LiveOperationsSection({section}:{section:string}){
 const [data,setData]=useState<OpsDetail|null>(null)
 const [events,setEvents]=useState<OpsAuditEvent[]>([])
 const [selected,setSelected]=useState<OpsAuditEvent|null>(null)
 const [cursor,setCursor]=useState<string|null>(null)
 const [filters,setFilters]=useState({action:'',outcome:'',actorId:'',actorType:'',targetType:'',targetId:'',correlationId:'',from:'',to:''})
 const [applied,setApplied]=useState({action:'',outcome:'',actorId:'',actorType:'',targetType:'',targetId:'',correlationId:'',from:'',to:''})
 const [error,setError]=useState('')
 const [loading,setLoading]=useState(false)
 const [retention,setRetention]=useState<Record<string,unknown>|null>(null)
 const [configuration,setConfiguration]=useState('')
 const [refreshedAt,setRefreshedAt]=useState<string|null>(null)
 const [incident,setIncident]=useState<{title:string;details:[string,string][]}|null>(null)
 const load=async(f=applied)=>{setLoading(true);setError('');try{
 if(section==='audit'){const r=await opsAudit(undefined,f);setEvents(r.events);setCursor(r.nextCursor)}
 else if(section==='configuration'){const x=await systemInfo('platform');setConfiguration([x.productName,x.version,x.status,x.activeProfiles.join(', ')].join(' · '))}
 else if(section==='health'){const results=await Promise.allSettled([opsDetail(),opsRetention()]);if(results[0].status==='fulfilled')setData(results[0].value);else throw results[0].reason;if(results[1].status==='fulfilled')setRetention(results[1].value);else setError('Retention status unavailable: '+String(results[1].reason))}
 else setData(await opsDetail())
 setRefreshedAt(new Date().toISOString())
 }catch(e){setError(String(e))}finally{setLoading(false)}}
 useEffect(()=>{void load()},[section])
 const renderRecords=(heading:string,columns:string[],rows:(string|number|null|undefined)[][])=> <Panel flush title={heading} meta={rows.length}><table className="v-table"><thead><tr>{columns.map(c=><th key={c}>{c}</th>)}</tr></thead><tbody>{rows.map((r,i)=><tr key={i}>{r.map((v,j)=><td key={j} className={j===0?'lead':undefined}>{v??'Unavailable'}</td>)}</tr>)}</tbody></table>{rows.length===0&&<Callout title="No records returned">No records returned by the backend for this view.</Callout>}</Panel>
 return <div className="stack-lg">
 <div className="row" style={{justifyContent:'flex-end',gap:12,alignItems:'center'}}>{refreshedAt&&<span className="muted">Refreshed {fmtDateTime(refreshedAt)}</span>}<button type="button" className="v-btn sm" disabled={loading} onClick={()=>void load()}>{loading?'Refreshing…':'Refresh'}</button></div>
 {error&&<Callout tone="warn" title="Operational data unavailable">{error}</Callout>}
 {section==='audit'?<>
 <Panel title="Audit filters"><div className="row wrap" style={{gap:8}}>
 <input className="v-input" aria-label="Action" placeholder="Action" value={filters.action} onChange={e=>setFilters(x=>({...x,action:e.target.value}))}/>
 <select className="v-select" aria-label="Outcome" value={filters.outcome} onChange={e=>setFilters(x=>({...x,outcome:e.target.value}))}><option value="">All outcomes</option><option value="SUCCESS">Success</option><option value="FAILED">Failed</option><option value="DENIED">Denied</option></select>
 <input className="v-input" aria-label="Actor ID" placeholder="Actor ID" value={filters.actorId} onChange={e=>setFilters(x=>({...x,actorId:e.target.value}))}/>
 <input className="v-input" aria-label="Target type" placeholder="Target type" value={filters.targetType} onChange={e=>setFilters(x=>({...x,targetType:e.target.value}))}/>
 <input className="v-input" aria-label="Target ID" placeholder="Target ID" value={filters.targetId} onChange={e=>setFilters(x=>({...x,targetId:e.target.value}))}/>
 <input className="v-input" aria-label="Correlation ID" placeholder="Correlation ID" value={filters.correlationId} onChange={e=>setFilters(x=>({...x,correlationId:e.target.value}))}/>
 <input className="v-input" aria-label="Actor type" placeholder="Actor type" value={filters.actorType} onChange={e=>setFilters(x=>({...x,actorType:e.target.value}))}/>
 <label className="v-field"><span>From</span><input className="v-input" type="datetime-local" value={filters.from} onChange={e=>setFilters(x=>({...x,from:e.target.value}))}/></label>
 <label className="v-field"><span>To</span><input className="v-input" type="datetime-local" value={filters.to} onChange={e=>setFilters(x=>({...x,to:e.target.value}))}/></label>
 <button type="button" className="v-btn primary" disabled={!!filters.from&&!!filters.to&&filters.from>filters.to} onClick={()=>{const f={...filters,from:filters.from?new Date(filters.from).toISOString():'',to:filters.to?new Date(filters.to).toISOString():''};setApplied(f);void load(f)}}>Apply filters</button></div></Panel>
 <Panel flush title="Audit events" meta={events.length}><table className="v-table"><thead><tr><th>Time</th><th>Action</th><th>Actor</th><th>Outcome</th><th>Details</th></tr></thead><tbody>{events.map(e=><tr key={e.auditEventId}><td>{fmtDateTime(e.occurredAt)}</td><td className="lead">{e.action}</td><td>{e.actorId}</td><td>{e.outcome}</td><td><button type="button" className="v-btn sm" onClick={()=>setSelected(e)}>View</button></td></tr>)}</tbody></table>{cursor&&<button type="button" className="v-btn" onClick={()=>opsAudit(cursor,applied).then(r=>{setEvents(x=>[...x,...r.events]);setCursor(r.nextCursor)}).catch(e=>setError(String(e)))}>Load more</button>}</Panel>
 {selected&&<Drawer eyebrow="Audit evidence" title={selected.action} onClose={()=>setSelected(null)}><dl className="v-kv"><div><dt>Time</dt><dd>{fmtDateTime(selected.occurredAt)}</dd></div><div><dt>Event ID</dt><dd className="mono">{selected.auditEventId}</dd></div><div><dt>Target</dt><dd>{selected.targetType} · {selected.targetId}</dd></div><div><dt>Correlation ID</dt><dd>{selected.correlationId??'—'}</dd></div><div><dt>Actor</dt><dd>{selected.actorType} · {selected.actorId}</dd></div><div><dt>Safe audit details</dt><dd className="mono">{selected.safeDetailsJson??'—'}</dd></div></dl></Drawer>}
 </>:section==='configuration'?<Panel title="Deployment configuration"><p>{configuration||'Unavailable'}</p></Panel>:
 <>
 {data&&section==='health'&&<><Panel title="Core services"><dl className="v-kv cols">{[data.application,data.database,data.keyService].map(x=><div key={x.component}><dt>{x.component}</dt><dd>{x.status} · {x.message}</dd></div>)}</dl></Panel><Panel title="Retention sweep"><dl className="v-kv">{Object.entries(retention??{}).map(([k,v])=><div key={k}><dt>{k}</dt><dd>{v==null?'Unavailable':String(v)}</dd></div>)}</dl>{!retention&&<p className="muted">Retention status unavailable.</p>}</Panel></>}
 {data&&section==='storage'&&renderRecords('Processing storage',['Volume','State','Health','Used','Write protection'],data.storageVolumes.map(v=>[v.displayName,v.state,v.observationError?'Probe unavailable':v.health,v.usedPercent==null?'Unavailable':v.usedPercent+'%',v.observationError|| (v.stopWriteThresholdReached?'Stop-write':v.warningThresholdReached?'Warning':v.writable?'Writable':'Not writable')]))}
 {data&&section==='incidents'&&<>{renderRecords('Processing failures',['Document','Error','Attempts','Ingested'],data.processingFailures.map(v=>[v.documentId,v.errorCode,v.processingAttemptCount,fmtDateTime(v.ingestedAt)]))}{renderRecords('Stuck transitions',['Document','Status','Since','Error'],data.stuckDocuments.map(v=>[v.documentId,v.status,fmtDateTime(v.stateSince),v.processingErrorCode]))}</>}
 {data&&section==='intake'&&renderRecords('Incomplete submissions',['Submission','Status','Documents','Last activity'],data.attentionSubmissions.map(v=>[v.sourceSubmissionId||v.submissionId,v.status,v.receivedDocumentCount+' / '+v.expectedDocumentCount,fmtDateTime(v.lastActivityAt)]))}
 {data&&section==='incidents'&&<Panel title="Inspect incident details" description="Select an incident to view its backend identifiers and diagnostic fields."><div className="row wrap" style={{gap:8}}>{data.processingFailures.map(v=><button type="button" className="v-btn sm" key={v.documentId} onClick={()=>setIncident({title:'Processing failure',details:[['Document',v.documentId],['Submission',v.submissionId],['Source',v.sourceId],['Error',v.errorCode],['Attempts',String(v.processingAttemptCount)],['Ingested',fmtDateTime(v.ingestedAt)]]})}>Failure · {v.documentId.slice(0,8)}</button>)}{data.stuckDocuments.map(v=><button type="button" className="v-btn sm" key={v.documentId} onClick={()=>setIncident({title:'Stuck transition',details:[['Document',v.documentId],['Submission',v.submissionId],['Status',v.status],['Since',fmtDateTime(v.stateSince)],['Error',v.processingErrorCode??'None']]})}>Stuck · {v.documentId.slice(0,8)}</button>)}</div></Panel>}
 {incident&&<Drawer eyebrow="Platform operations · incident" title={incident.title} onClose={()=>setIncident(null)}><dl className="v-kv">{incident.details.map(([k,v])=><div key={k}><dt>{k}</dt><dd className="mono">{v}</dd></div>)}</dl></Drawer>}
 {data&&data.dataObservations?.some(x=>x.status==='DEGRADED')&&<Callout tone="warn" title="Partial backend observations">Some operational queries are degraded. Missing records do not indicate zero incidents.</Callout>}
 </>}
 </div>
}
