import { useEffect, useState } from 'react'
import { itSources, itFields, itUpsertField, itCreateSource, itUpdateSource, itSourceSummary, liveStorage, identityOverview, systemInfo, type LiveStorageVolume, type IdentityOverview, type ItSourceSummary, type ItSource, type ItField } from './liveApi'
import { CheckCircle2, Circle, History, Pencil, Plus, RotateCcw } from 'lucide-react'
import { AREA_TABS, useStore } from './store'
import { SYSTEM, type IdentityConfig, type Source, type Volume } from './data'
import { AreaFrame } from './Governance'
import { Badge, Callout, Dialog, Drawer, Meter, Panel, Scenario, Status, fmtDate, fmtRel } from './ui'

const AUTH_LABEL = { OAUTH2_CLIENT_CREDENTIALS: 'OAuth 2.0 client credentials', JWT_BEARER: 'JWT bearer', MTLS: 'Mutual TLS' } as const
const LEDE: Record<string, string> = {
  storage: 'Encrypted document volumes for this tenant, with warning and stop-write thresholds.',
  authentication: 'The enterprise identity provider for sign-in. Test a draft before activating; roll back to a known-good version.',
  integrations: 'Producer systems that submit documents, how they authenticate, and their metadata contract.',
  environment: 'Read-only facts about this customer environment.',
}

export const volumeAttention = (v: Volume) => v.error != null || v.usedPct >= v.warnPct && v.state === 'ACTIVE' || (v.state === 'ACTIVE' && (v.health !== 'UP' || !v.writable))

export function TenantAdmin() {
  const { route, volumes } = useStore()
  const tab = AREA_TABS.tenant.find((t) => t.id === route.tab)!
  return (
    <AreaFrame crumbs={['Tenant IT', tab.label]} title={tab.label} lede={LEDE[route.tab]} attention={volumes.some(volumeAttention) ? ['storage'] : []}>
      {route.tab === 'storage' && <LiveStorage />}
      {route.tab === 'authentication' && <LiveIdentity />}
      {route.tab === 'integrations' && <ConnectedIntegrations />}
      {route.tab === 'environment' && <LiveEnvironment />}
    </AreaFrame>
  )
}

export function StorageTab({ editable }: { editable?: boolean }) {
  const { volumes, setVolumes, toast, recordAudit } = useStore()
  const [edit, setEdit] = useState<Volume | 'new' | null>(null)
  const attention = volumes.filter(volumeAttention)
  return (
    <>
      {attention.length > 0 && <Callout tone="warn" title={`${attention.length} volume needs attention`}>{attention.map((v) => `${v.name} is at ${v.usedPct}% (warning ${v.warnPct}%, stop-write ${v.stopPct}%).`).join(' ')} Add capacity or a new ACTIVE volume before stop-write is reached.</Callout>}
      <Panel flush title="Storage volumes" meta={volumes.length} actions={editable && <button type="button" className="v-btn sm primary" onClick={() => setEdit('new')}><Plus size={15} aria-hidden />Add volume</button>}>
        <Scenario what="storage volumes" empty={<Callout tone="warn" title="No storage volume configured">Add an ACTIVE volume before ingesting production documents.</Callout>}>
          <table className="v-table">
            <thead><tr><th>Volume</th><th>State</th><th>Health</th><th style={{ width: '28%' }}>Capacity</th><th className="num">Used</th>{editable && <th className="act"><span className="sr-only">Edit</span></th>}</tr></thead>
            <tbody>{volumes.map((v) => (
              <tr key={v.id}>
                <td className="lead" data-label="Volume"><div className="stack" style={{ gap: 0 }}><strong style={{ fontWeight: 500 }}>{v.name}</strong><span className="mono faint" style={{ fontSize: 12 }}>{v.path}</span></div></td>
                <td data-label="State"><Badge tone={v.state === 'ACTIVE' ? 'ok' : v.state === 'READ_ONLY' ? 'outline' : 'bad'}>{v.state.replace('_', '-').toLowerCase()}</Badge></td>
                <td data-label="Health"><Status value={v.health} label={`${v.health === 'UP' ? 'Healthy' : v.health}${v.writable ? '' : ' · read-only'}`} /></td>
                <td data-label="Capacity"><div style={{ width: '100%', maxWidth: 220, marginLeft: 'auto' }}><Meter pct={v.usedPct} warn={v.warnPct} stop={v.stopPct} label={`${v.name} capacity`} /></div></td>
                <td className="num" data-label="Used">{v.usedPct}% <span className="faint">of {(v.totalGb / 1024).toFixed(0)} TB</span></td>
                {editable && <td className="act"><button type="button" className="v-btn sm" onClick={() => setEdit(v)}><Pencil size={14} aria-hidden />Edit</button></td>}
              </tr>
            ))}</tbody>
          </table>
        </Scenario>
      </Panel>
      {edit && <VolumeDrawer volume={edit === 'new' ? null : edit} onClose={() => setEdit(null)} onSave={(v) => {
        setVolumes((list) => (list.some((x) => x.id === v.id) ? list.map((x) => (x.id === v.id ? v : x)) : [...list, v]))
        recordAudit({ action: edit === 'new' ? 'STORAGE_VOLUME_CREATED' : 'STORAGE_VOLUME_UPDATED', targetType: 'STORAGE_VOLUME', target: v.id, outcome: 'SUCCESS', details: { simulated: 'true' } })
        toast({ title: edit === 'new' ? 'Volume added (simulated)' : 'Volume updated (simulated)', detail: `${v.name} · ${v.state}` }); setEdit(null)
      }} />}
    </>
  )
}

function VolumeDrawer({ volume, onClose, onSave }: { volume: Volume | null; onClose: () => void; onSave: (v: Volume) => void }) {
  const [v, setV] = useState<Volume>(volume ?? { id: `vol-0${Math.floor(Math.random() * 9) + 3}`, name: '', path: '/mnt/sdv/', state: 'ACTIVE', health: 'UP', writable: true, usedPct: 0, totalGb: 2048, warnPct: 80, stopPct: 95, observedAt: new Date().toISOString(), error: null })
  const thresholdsOk = v.warnPct > 0 && v.warnPct < v.stopPct && v.stopPct <= 100
  const ok = v.name.trim() && v.path.startsWith('/') && thresholdsOk
  return (
    <Drawer eyebrow="Storage" title={volume ? `Edit ${volume.name}` : 'Add storage volume'} onClose={onClose} footer={<><button type="button" className="v-btn" onClick={onClose}>Cancel</button><button type="button" className="v-btn primary" disabled={!ok} onClick={() => onSave({ ...v, writable: v.state === 'ACTIVE' })}>{volume ? 'Save volume' : 'Add volume'}</button></>}>
      <label className="v-field"><span>Display name</span><input className="v-input" value={v.name} onChange={(e) => setV({ ...v, name: e.target.value })} /></label>
      <label className="v-field"><span>Mount path</span><input className="v-input mono" value={v.path} readOnly={!!volume} onChange={(e) => setV({ ...v, path: e.target.value })} /><small>{volume ? 'Immutable after creation.' : 'Absolute path on the vault host.'}</small></label>
      <label className="v-field"><span>State</span><select className="v-select" value={v.state} onChange={(e) => setV({ ...v, state: e.target.value as Volume['state'] })}><option value="ACTIVE">Active — accepts new documents</option><option value="READ_ONLY">Read-only — serves existing documents</option><option value="DISABLED">Disabled</option></select></label>
      <div className="row" style={{ alignItems: 'flex-start' }}>
        <label className="v-field grow"><span>Warning at %</span><input className={`v-input${thresholdsOk ? '' : ' invalid'}`} inputMode="numeric" value={v.warnPct} onChange={(e) => setV({ ...v, warnPct: Number(e.target.value.replace(/\D/g, '')) })} /></label>
        <label className="v-field grow"><span>Stop-write at %</span><input className={`v-input${thresholdsOk ? '' : ' invalid'}`} inputMode="numeric" value={v.stopPct} onChange={(e) => setV({ ...v, stopPct: Number(e.target.value.replace(/\D/g, '')) })} /></label>
      </div>
      {!thresholdsOk && <small style={{ color: 'var(--danger)' }}>Warning must be lower than stop-write, and stop-write at most 100%.</small>}
      {volume && volume.state === 'ACTIVE' && v.state === 'DISABLED' && <Callout tone="bad" title="Disabling an active volume">Documents on this volume become unavailable until it is re-enabled.</Callout>}
    </Drawer>
  )
}

function AuthTab() {
  const { identity, setIdentity, toast, recordAudit, role } = useStore()
  const [test, setTest] = useState<'idle' | 'running' | 'passed'>('idle')
  const [editDraft, setEditDraft] = useState(false)
  const [activate, setActivate] = useState(false)
  const [rollback, setRollback] = useState<IdentityConfig | null>(null)
  const a = identity.active
  const d = identity.draft
  const runTest = () => { setTest('running'); window.setTimeout(() => { setTest('passed'); recordAudit({ action: 'IDENTITY_DRAFT_TESTED', targetType: 'IDENTITY_CONFIGURATION', target: `v${d?.version}`, outcome: 'SUCCESS', details: { simulated: 'true' } }) }, 1100) }
  const checks = ['Issuer discovery document reachable', 'Signing keys (JWKS) retrieved', 'Browser client accepted by issuer', 'Roles claim present in sample token']
  return (
    <>
      <Panel title="Active configuration" meta={`v${a.version}`} actions={<Status value="ACTIVE" label="In use" />}>
        <Scenario what="identity configuration">
          <dl className="v-kv cols">
            <div><dt>Provider</dt><dd>{a.name}</dd></div><div><dt>Type</dt><dd>{a.type}</dd></div>
            <div><dt>Issuer</dt><dd className="mono">{a.issuer}</dd></div><div><dt>Browser client</dt><dd className="mono">{a.clientId}</dd></div>
            <div><dt>API audience</dt><dd className="mono">{a.audience}</dd></div><div><dt>Roles claim</dt><dd className="mono">{a.rolesClaim}</dd></div>
            <div><dt>Last changed</dt><dd>{fmtDate(a.updatedAt)} · {a.updatedBy}</dd></div><div><dt>Client secret</dt><dd className="faint">Not stored in SDV</dd></div>
          </dl>
        </Scenario>
      </Panel>
      <Panel title={d ? `Draft v${d.version}` : 'Draft'} description="Change identity safely: edit a draft, test it against the provider, then activate atomically."
        actions={d && <button type="button" className="v-btn sm" onClick={() => setEditDraft(true)}><Pencil size={14} aria-hidden />Edit draft</button>}>
        {!d ? (
          <div className="stack"><p className="muted">No draft in progress.</p><button type="button" className="v-btn" style={{ alignSelf: 'flex-start' }} onClick={() => setIdentity((s) => ({ ...s, draft: { ...s.active, version: s.active.version + 1, updatedAt: new Date().toISOString(), updatedBy: role.person } }))}><Plus size={15} aria-hidden />Start draft from active</button></div>
        ) : (
          <div className="stack-lg">
            <dl className="v-kv cols">
              <div><dt>Browser client</dt><dd className="mono">{d.clientId}{d.clientId !== a.clientId && <> <Badge tone="warn">changed</Badge></>}</dd></div>
              <div><dt>Roles claim</dt><dd className="mono">{d.rolesClaim}{d.rolesClaim !== a.rolesClaim && <> <Badge tone="warn">changed</Badge></>}</dd></div>
            </dl>
            <ol className="stack" style={{ listStyle: 'none', margin: 0, padding: 0, gap: 8 }}>
              {checks.map((c, i) => (
                <li key={c} className="row" style={{ fontSize: 14 }}>
                  {test === 'passed' ? <CheckCircle2 size={18} style={{ color: 'var(--primary)' }} aria-hidden /> : <Circle size={18} className="faint" aria-hidden style={test === 'running' ? { opacity: 0.4 + (i % 2) * 0.3 } : undefined} />}
                  <span className={test === 'passed' ? '' : 'muted'}>{c}</span>
                </li>
              ))}
            </ol>
            <div className="row wrap">
              <button type="button" className="v-btn" onClick={runTest} disabled={test === 'running'}>{test === 'running' ? 'Testing…' : test === 'passed' ? 'Re-run test' : 'Test draft'}</button>
              <button type="button" className="v-btn primary" disabled={test !== 'passed'} onClick={() => setActivate(true)}>Activate draft</button>
              {test !== 'passed' && <small className="faint">Activation is available after a passing test.</small>}
            </div>
            <Callout tone="sim" title="Simulated checks">No request is sent to the identity provider.</Callout>
          </div>
        )}
      </Panel>
      <Panel flush title="Known-good rollback" meta={identity.rollback.length} description="Previously active configurations. Rollback atomically replaces the active binding.">
        <Scenario what="rollback targets" empty={<p className="faint" style={{ padding: 16 }}>The previously active configuration will appear here after the first activation.</p>}>
          <table className="v-table">
            <thead><tr><th>Version</th><th>Provider</th><th>Client</th><th>Last active</th><th className="act"><span className="sr-only">Rollback</span></th></tr></thead>
            <tbody>{identity.rollback.map((r) => (
              <tr key={r.version}><td className="lead" data-label="Version"><strong style={{ fontWeight: 500 }}>v{r.version}</strong></td><td data-label="Provider">{r.name}</td><td data-label="Client"><span className="mono">{r.clientId}</span></td><td data-label="Last active">{fmtDate(r.updatedAt)}</td>
                <td className="act"><button type="button" className="v-btn sm" onClick={() => setRollback(r)}><RotateCcw size={14} aria-hidden />Roll back</button></td></tr>
            ))}</tbody>
          </table>
        </Scenario>
      </Panel>

      {editDraft && d && <DraftDrawer draft={d} onClose={() => setEditDraft(false)} onSave={(nd) => { setIdentity((s) => ({ ...s, draft: nd })); setTest('idle'); setEditDraft(false); toast({ title: 'Draft saved (simulated)', detail: 'Re-test before activating.' }) }} />}
      {activate && d && (
        <ConfirmIdentity title={`Activate v${d.version}?`} body="New sign-ins will use this configuration immediately. Existing sessions continue until they expire. The current version becomes a rollback target." cta="Activate" onClose={() => setActivate(false)}
          onConfirm={() => {
            setIdentity((s) => ({ active: { ...d, updatedAt: new Date().toISOString(), updatedBy: role.person }, draft: null, rollback: [s.active, ...s.rollback] }))
            recordAudit({ action: 'IDENTITY_CONFIGURATION_ACTIVATED', targetType: 'IDENTITY_CONFIGURATION', target: `v${d.version}`, outcome: 'SUCCESS', details: { simulated: 'true' } })
            toast({ title: `v${d.version} active (simulated)` }); setActivate(false); setTest('idle')
          }} />
      )}
      {rollback && (
        <ConfirmIdentity title={`Roll back to v${rollback.version}?`} body={`${rollback.name} with client ${rollback.clientId} becomes active for new sign-ins. Users missing the "${rollback.rolesClaim}" claim will be denied.`} cta="Roll back" onClose={() => setRollback(null)}
          onConfirm={() => {
            setIdentity((s) => ({ ...s, active: { ...rollback, updatedAt: new Date().toISOString(), updatedBy: role.person }, rollback: [s.active, ...s.rollback.filter((x) => x.version !== rollback.version)] }))
            recordAudit({ action: 'IDENTITY_CONFIGURATION_ROLLED_BACK', targetType: 'IDENTITY_CONFIGURATION', target: `v${rollback.version}`, outcome: 'SUCCESS', details: { simulated: 'true' } })
            toast({ title: `Rolled back to v${rollback.version} (simulated)` }); setRollback(null)
          }} />
      )}
    </>
  )
}

function ConfirmIdentity({ title, body, cta, onClose, onConfirm }: { title: string; body: string; cta: string; onClose: () => void; onConfirm: () => void }) {
  const [ack, setAck] = useState(false)
  return (
    <Dialog title={title} onClose={onClose} footer={<><button type="button" className="v-btn" onClick={onClose}>Cancel</button><button type="button" className="v-btn primary" disabled={!ack} onClick={onConfirm}>{cta}</button></>}>
      <Callout tone="warn" title="Affects sign-in for the whole tenant">{body}</Callout>
      <label className="v-check"><input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} /><span>I have a break-glass path if sign-in fails.</span></label>
    </Dialog>
  )
}

function DraftDrawer({ draft, onClose, onSave }: { draft: IdentityConfig; onClose: () => void; onSave: (d: IdentityConfig) => void }) {
  const [d, setD] = useState(draft)
  const ok = d.issuer.startsWith('https://') && d.clientId.trim() && d.audience.trim() && d.rolesClaim.trim()
  return (
    <Drawer eyebrow={`Identity draft v${d.version}`} title="Edit draft" onClose={onClose} footer={<><button type="button" className="v-btn" onClick={onClose}>Cancel</button><button type="button" className="v-btn primary" disabled={!ok} onClick={() => onSave(d)}>Save draft</button></>}>
      <label className="v-field"><span>Display name</span><input className="v-input" value={d.name} onChange={(e) => setD({ ...d, name: e.target.value })} /></label>
      <label className="v-field"><span>Issuer URI</span><input className={`v-input mono${d.issuer.startsWith('https://') ? '' : ' invalid'}`} value={d.issuer} onChange={(e) => setD({ ...d, issuer: e.target.value })} /><small>Must be HTTPS.</small></label>
      <label className="v-field"><span>Browser client ID</span><input className="v-input mono" value={d.clientId} onChange={(e) => setD({ ...d, clientId: e.target.value })} /></label>
      <label className="v-field"><span>API audience</span><input className="v-input mono" value={d.audience} onChange={(e) => setD({ ...d, audience: e.target.value })} /></label>
      <label className="v-field"><span>Roles claim</span><input className="v-input mono" value={d.rolesClaim} onChange={(e) => setD({ ...d, rolesClaim: e.target.value })} /><small>Token claim containing SDV role codes.</small></label>
    </Drawer>
  )
}

function IntegrationsTab() {
  const { sources, setSources, toast, recordAudit } = useStore()
  const [edit, setEdit] = useState<Source | null>(null)
  return (
    <>
      <Panel flush title="Ingestion integrations" meta={sources.length} description="Each source authenticates to the ingestion API with machine credentials. SDV stores a reference, never the secret.">
        <Scenario what="integrations" empty={<p className="faint" style={{ padding: 16 }}>No integrations configured. Add a producer source to define its technical contract.</p>}>
          <table className="v-table">
            <thead><tr><th>Source</th><th>Status</th><th>Authentication</th><th className="num">Documents</th><th>Last activity</th><th className="act"><span className="sr-only">Edit</span></th></tr></thead>
            <tbody>{sources.map((s) => (
              <tr key={s.id}>
                <td className="lead" data-label="Source"><div className="stack" style={{ gap: 0 }}><strong style={{ fontWeight: 500 }}>{s.name}</strong><span className="mono faint" style={{ fontSize: 12 }}>{s.key}</span></div></td>
                <td data-label="Status"><Status value={s.status} label={s.status === 'ACTIVE' ? 'Active' : 'Disabled'} /></td>
                <td data-label="Authentication">{AUTH_LABEL[s.authType]}</td>
                <td className="num" data-label="Documents">{s.documents.toLocaleString()}</td>
                <td data-label="Last activity">{fmtRel(s.lastActivityAt)}</td>
                <td className="act"><button type="button" className="v-btn sm" onClick={() => setEdit(s)}><Pencil size={14} aria-hidden />Manage</button></td>
              </tr>
            ))}</tbody>
          </table>
        </Scenario>
      </Panel>
      {edit && <SourceDrawer source={edit} onClose={() => setEdit(null)} onSave={(s) => {
        setSources((l) => l.map((x) => (x.id === s.id ? s : x)))
        recordAudit({ action: 'INGESTION_SOURCE_UPDATED', targetType: 'INGESTION_SOURCE', target: s.key, outcome: 'SUCCESS', details: { simulated: 'true' } })
        toast({ title: 'Integration saved (simulated)', detail: s.name }); setEdit(null)
      }} />}
    </>
  )
}

function SourceDrawer({ source, onClose, onSave }: { source: Source; onClose: () => void; onSave: (s: Source) => void }) {
  const [s, setS] = useState(source)
  return (
    <Drawer eyebrow="Integration" title={source.name} onClose={onClose} footer={<><button type="button" className="v-btn" onClick={onClose}>Cancel</button><button type="button" className="v-btn primary" disabled={!s.name.trim() || !s.credentialRef.trim()} onClick={() => onSave(s)}>Save integration</button></>}>
      <dl className="v-kv"><div><dt>Source key</dt><dd className="mono">{s.key} <span className="faint">· immutable</span></dd></div></dl>
      <label className="v-field"><span>Display name</span><input className="v-input" value={s.name} onChange={(e) => setS({ ...s, name: e.target.value })} /></label>
      <div className="row" style={{ alignItems: 'flex-start' }}>
        <label className="v-field grow"><span>Status</span><select className="v-select" value={s.status} onChange={(e) => setS({ ...s, status: e.target.value as Source['status'] })}><option value="ACTIVE">Active</option><option value="DISABLED">Disabled</option></select></label>
        <label className="v-field grow"><span>Authentication</span><select className="v-select" value={s.authType} onChange={(e) => setS({ ...s, authType: e.target.value as Source['authType'] })}>{Object.entries(AUTH_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
      </div>
      <label className="v-field"><span>Credential reference</span><input className="v-input mono" value={s.credentialRef} onChange={(e) => setS({ ...s, credentialRef: e.target.value })} /><small>Reference only. SDV never displays the secret value.</small></label>
      {source.status === 'ACTIVE' && s.status === 'DISABLED' && <Callout tone="warn" title="Disabling stops new submissions">In-flight submissions from this source will be rejected. Stored documents stay available.</Callout>}
      <div className="stack" style={{ gap: 4 }}>
        <h3>Operational summary</h3>
        <dl className="v-kv cols"><div><dt>Submissions</dt><dd>{s.submissions.toLocaleString()}</dd></div><div><dt>Documents</dt><dd>{s.documents.toLocaleString()}</dd></div><div><dt>Created</dt><dd>{fmtDate(s.createdAt)}</dd></div><div><dt>Last activity</dt><dd>{fmtRel(s.lastActivityAt)}</dd></div></dl>
      </div>
      <div className="stack" style={{ gap: 8 }}>
        <div className="row between"><h3>Metadata contract</h3><span className="faint" style={{ fontSize: 13 }}>{s.fields.length} fields</span></div>
        <ul className="stack" style={{ listStyle: 'none', margin: 0, padding: 0, gap: 6 }}>
          {s.fields.map((f) => <li key={f.key} className="row between" style={{ padding: '8px 12px', border: '1px solid var(--line)', borderRadius: 7 }}><span className="mono">{f.key}</span><span className="row">{f.required && <Badge>required</Badge>}<Badge tone="outline">{f.type}</Badge></span></li>)}
        </ul>
        <small className="faint"><History size={12} aria-hidden style={{ verticalAlign: -2 }} /> Field keys are defined here. Business Administration controls labels and matching.</small>
      </div>
    </Drawer>
  )
}

export function EnvironmentPanel() {
  return (
    <Panel title="Customer environment" description="Settings apply only to this customer's SDV environment. Licensing and branding aren't editable yet.">
      <Scenario what="environment">
        <dl className="v-kv cols">
          <div><dt>Product</dt><dd>{SYSTEM.product}</dd></div><div><dt>Tenant</dt><dd>{SYSTEM.tenant}</dd></div>
          <div><dt>Environment</dt><dd>{SYSTEM.environment}</dd></div><div><dt>Region</dt><dd>{SYSTEM.region}</dd></div>
          <div><dt>Version</dt><dd className="mono">{SYSTEM.version}</dd></div><div><dt>Build</dt><dd className="mono">{SYSTEM.build}</dd></div>
          <div><dt>Active profiles</dt><dd className="row wrap" style={{ gap: 6 }}>{SYSTEM.profiles.map((p) => <Badge key={p} tone="outline">{p}</Badge>)}</dd></div><div><dt>Status</dt><dd><Status value="UP" label="Operational" /></dd></div>
        </dl>
      </Scenario>
    </Panel>
  )
}


function ConnectedIntegrations(){
 const [sources,setSources]=useState<ItSource[]>([])
 const [sourceId,setSourceId]=useState('')
 const [fields,setFields]=useState<ItField[]>([])
 const [error,setError]=useState('')
 const [form,setForm]=useState(false)
 const [revising,setRevising]=useState<ItField|null>(null)
 const [ack,setAck]=useState(false)
 const [key,setKey]=useState('')
 const [label,setLabel]=useState('')
 const [type,setType]=useState('TEXT')
 const [normalization,setNormalization]=useState('NONE')
 const [required,setRequired]=useState(false)
 const [busy,setBusy]=useState(false)
 const [sourceForm,setSourceForm]=useState<'new'|'edit'|null>(null)
 const [sourceKey,setSourceKey]=useState('')
 const [sourceName,setSourceName]=useState('')
 const [authType,setAuthType]=useState<ItSource['authType']>('OAUTH2_CLIENT_CREDENTIALS')
 const [credential,setCredential]=useState('')
 const [sourceStatus,setSourceStatus]=useState<ItSource['status']>('ACTIVE')
 const [sourceBusy,setSourceBusy]=useState(false)
 const [summary,setSummary]=useState<ItSourceSummary|null>(null)
 useEffect(()=>{let live=true;itSources().then(rows=>{if(live){setSources(rows);setSourceId(rows[0]?.sourceId??'')}}).catch(e=>{if(live)setError(String(e))});return()=>{live=false}},[])
 useEffect(()=>{if(!sourceId)return;let live=true;setFields([]);setSummary(null);itSourceSummary(sourceId).then(v=>{if(live)setSummary(v)}).catch(e=>{if(live)setError(String(e))});itFields(sourceId).then(rows=>{if(live)setFields(rows)}).catch(e=>{if(live)setError(String(e))});return()=>{live=false}},[sourceId])
 const valid=/^[A-Za-z][A-Za-z0-9_.-]{0,99}$/.test(key)&&label.trim().length>0&&(revising?.fieldKey===key||!fields.some(f=>f.fieldKey===key))&&!(type==='BOOLEAN'&&normalization==='DIGITS_ONLY')&&(!revising||ack)
 const save=async()=>{if(!valid||busy)return;setBusy(true);setError('');try{
  await itUpsertField(sourceId,key,{displayLabel:label.trim(),fieldType:type,required,normalization,validationRules:{}})
  setFields(await itFields(sourceId));setForm(false);setRevising(null);setAck(false);setKey('');setLabel('');setRequired(false)
 }catch(e){setError(String(e))}finally{setBusy(false)}}
 const source=sources.find(x=>x.sourceId===sourceId)
 const editSource=()=>{if(!source)return;setSourceKey(source.sourceKey);setSourceName(source.displayName);setAuthType(source.authType);setCredential(source.credentialReference);setSourceStatus(source.status);setSourceForm('edit')}
 const createSource=()=>{setSourceKey('');setSourceName('');setAuthType('OAUTH2_CLIENT_CREDENTIALS');setCredential('');setSourceStatus('ACTIVE');setSourceForm('new')}
 const sourceValid=sourceName.trim().length>0&&(sourceForm==='edit'||/^[a-z][a-z0-9-]*$/.test(sourceKey.trim()))
 const persistSource=async()=>{if(!sourceForm||!sourceValid||sourceBusy)return;setSourceBusy(true);setError('')
   try{
     const result=sourceForm==='new'
       ?await itCreateSource({sourceKey:sourceKey.trim(),displayName:sourceName.trim(),authType,credentialReference:credential.trim()})
       :await itUpdateSource(sourceId,{displayName:sourceName.trim(),status:sourceStatus,authType,credentialReference:credential.trim(),limits:source?.limits})
     const rows=await itSources();setSources(rows);setSourceId(result.sourceId);setSourceForm(null)
   }catch(e){setError(String(e))}finally{setSourceBusy(false)}
 }
 return <>
 {error&&<Callout tone="warn" title="Source configuration unavailable">{error}</Callout>}
 <Panel flush title="Integrations" description="Tenant IT owns the incoming producer contract: source identity, authentication and metadata field definitions."
 actions={<span className="row" style={{gap:8}}><select className="v-select" aria-label="Integration" value={sourceId} onChange={e=>{setSourceId(e.target.value);setForm(false)}}>{sources.map(s=><option key={s.sourceId} value={s.sourceId}>{s.displayName}</option>)}</select><button type="button" className="v-btn sm" disabled={!source} onClick={editSource}><Pencil size={14}/>Edit integration</button><button type="button" className="v-btn sm primary" onClick={createSource}><Plus size={14}/>Add integration</button></span>}>
 {source?<dl className="v-kv cols"><div><dt>Immutable source key</dt><dd className="mono">{source.sourceKey}</dd></div><div><dt>Status</dt><dd>{source.status}</dd></div><div><dt>Authentication</dt><dd>{source.authType.replace(/_/g,' ')}</dd></div><div><dt>Credential reference</dt><dd className="mono">{source.credentialReference||'Not configured'}</dd></div></dl>:<Callout title="No integration selected">Create an integration to define its incoming metadata contract.</Callout>}
 </Panel>
 {summary&&<Panel title="Operational summary"><dl className="v-kv cols"><div><dt>Submissions</dt><dd>{summary.submissionCount}</dd></div><div><dt>Documents</dt><dd>{summary.documentCount}</dd></div><div><dt>Last activity</dt><dd>{summary.lastActivityAt?fmtDate(summary.lastActivityAt):'None'}</dd></div><div><dt>Last available document</dt><dd>{summary.lastDocumentAvailableAt?fmtDate(summary.lastDocumentAvailableAt):'None'}</dd></div></dl></Panel>}
 <Panel flush title="Source metadata contract" description="Tenant IT defines the exact metadata keys and types that the external producer must send. Business Administration separately controls Search visibility, matching, labels and display order."
 actions={<span className="row" style={{gap:8}}><select className="v-select" aria-label="Source" value={sourceId} onChange={e=>{setSourceId(e.target.value);setForm(false)}}>{sources.map(s=><option key={s.sourceId} value={s.sourceId}>{s.displayName}</option>)}</select><button type="button" className="v-btn sm primary" disabled={!sourceId} onClick={()=>{setRevising(null);setKey('');setLabel('');setType('TEXT');setNormalization('NONE');setRequired(false);setAck(false);setForm(true)}}><Plus size={14}/>Add field</button></span>}>
 <table className="v-table"><thead><tr><th>Field key</th><th>Label</th><th>Type</th><th>Required</th><th>Version</th><th className="act">Action</th></tr></thead><tbody>{fields.map(f=><tr key={f.fieldKey}><td className="lead" data-label="Field key">{f.fieldKey}</td><td data-label="Label">{f.displayLabel}</td><td data-label="Type">{f.fieldType}</td><td data-label="Required">{f.required?'Yes':'No'}</td><td data-label="Version">{f.version}</td><td className="act"><button type="button" className="v-btn sm" onClick={()=>{setRevising(f);setKey(f.fieldKey);setLabel(f.displayLabel);setType(f.fieldType);setNormalization(f.normalization);setRequired(f.required);setAck(false);setForm(true)}}><Pencil size={14}/>Revise</button></td></tr>)}</tbody></table>
 </Panel>
 <Callout title="Division of responsibility">Tenant IT defines what metadata the external system sends and validates those incoming fields. Business Administration decides which of those existing fields are searchable, how they match and in which order they appear. Defining a field does not automatically enable it in Business Search.</Callout>
 <Callout title="Incoming metadata contract">The producer must submit each value using the exact field key shown above. These are source metadata keys, not database columns. To show a new field in Business Search, enable its search behavior in Business Administration → Metadata Configuration.</Callout>
 <Callout title="Field lifecycle">Adding a definition does not populate historical documents. Retirement is not enabled until dependency-aware backend support is available.</Callout>
 {sourceForm&&<Drawer eyebrow="Tenant IT · integration" title={sourceForm==='new'?'Add integration':'Edit integration'} onClose={()=>{if(!sourceBusy)setSourceForm(null)}} footer={<><button type="button" className="v-btn" disabled={sourceBusy} onClick={()=>setSourceForm(null)}>Cancel</button><button type="button" className="v-btn primary" disabled={!sourceValid||sourceBusy} onClick={()=>void persistSource()}>{sourceBusy?'Saving…':sourceForm==='new'?'Add integration':'Save integration'}</button></>}>
 <label className="v-field"><span>Source key</span><input className="v-input" readOnly={sourceForm==='edit'} value={sourceKey} onChange={e=>setSourceKey(e.target.value)}/><small>Immutable identity of the external producer after creation.</small></label>
 <label className="v-field"><span>Display name</span><input className="v-input" value={sourceName} onChange={e=>setSourceName(e.target.value)}/></label>
 <label className="v-field"><span>Authentication</span><select className="v-select" value={authType} onChange={e=>setAuthType(e.target.value as ItSource['authType'])}><option value="OAUTH2_CLIENT_CREDENTIALS">OAuth 2.0 client credentials</option><option value="JWT_BEARER">JWT bearer</option><option value="MTLS">Mutual TLS</option></select></label>
 <label className="v-field"><span>Credential reference</span><input className="v-input" value={credential} onChange={e=>setCredential(e.target.value)}/><small>Reference only; do not enter credentials or secret values.</small></label>
 {sourceForm==='edit'&&<label className="v-field"><span>Status</span><select className="v-select" value={sourceStatus} onChange={e=>setSourceStatus(e.target.value as ItSource['status'])}><option value="ACTIVE">Active</option><option value="DISABLED">Disabled</option></select></label>}
 <Callout tone="warn" title="Producer impact">Changes to authentication, status or source contract can affect incoming submissions. Verify the producer configuration before saving.</Callout>
 </Drawer>}
 {form&&<Drawer eyebrow="Tenant IT · metadata contract" title={revising?'Revise field':'Add field'} onClose={()=>{if(!busy)setForm(false)}} footer={<><button type="button" className="v-btn" disabled={busy} onClick={()=>setForm(false)}>Cancel</button><button type="button" className="v-btn primary" disabled={!valid||busy} onClick={()=>void save()}>{busy?'Saving…':revising?'Save revision':'Add field'}</button></>}>
 <label className="v-field"><span>Field key</span><input className="v-input" value={key} readOnly={!!revising} onChange={e=>setKey(e.target.value)}/><small>Letters, numbers, dots, underscores and hyphens; starts with a letter.</small></label>
 <Callout title="Incoming metadata key">The producer must send this exact key: <strong>{key||'(enter a key)'}</strong>. No external field-name transformation is configured here.</Callout>
 <label className="v-field"><span>Display label</span><input className="v-input" value={label} onChange={e=>setLabel(e.target.value)}/></label>
 <label className="v-field"><span>Data type</span><select className="v-select" value={type} onChange={e=>setType(e.target.value)}><option value="TEXT">Text</option><option value="INTEGER">Integer</option><option value="DECIMAL">Decimal</option><option value="DATE">Date</option><option value="DATETIME">Date and time</option><option value="BOOLEAN">Boolean</option><option value="EMAIL">Email</option><option value="UUID">UUID</option></select></label>
 <label className="v-field"><span>Normalization</span><select className="v-select" value={normalization} onChange={e=>setNormalization(e.target.value)}><option value="NONE">None</option><option value="UPPERCASE">Uppercase</option><option value="DIGITS_ONLY">Digits only</option></select></label>
 <label className="v-check"><input type="checkbox" checked={required} onChange={e=>setRequired(e.target.checked)}/><span>Required in the ingestion contract</span></label>
 <Callout tone="warn" title="Integration impact">Required, type, or normalization changes can reject future submissions. Confirm the producer contract is compatible before saving.</Callout>
 {revising&&<label className="v-check"><input type="checkbox" checked={ack} onChange={e=>setAck(e.target.checked)}/><span>I have verified the producer contract supports this revision.</span></label>}
 </Drawer>}
 </>
}

function LiveStorage(){
 const [items,setItems]=useState<LiveStorageVolume[]>([])
 const [error,setError]=useState('')
 useEffect(()=>{let mounted=true;liveStorage().then(x=>{if(mounted)setItems(x)}).catch(e=>{if(mounted)setError(String(e))});return()=>{mounted=false}},[])
 return <>{error&&<Callout tone="warn" title="Storage data unavailable">{error}</Callout>}<Panel flush title="Storage volumes" meta={items.length} description="Live tenant storage. Configuration changes are disabled until the existing backend editor is integrated safely.">
 <table className="v-table"><thead><tr><th>Volume</th><th>State</th><th>Capacity used</th><th>Writable</th></tr></thead><tbody>{items.map(v=><tr key={v.volume.volumeId}><td className="lead">{v.volume.displayName}</td><td>{v.volume.state}</td><td>{v.capacity?.usedPercent??'Unavailable'}{v.capacity?.usedPercent!=null?'%':''}</td><td>{v.capacity?.writable?'Yes':'No'}</td></tr>)}</tbody></table>
 </Panel></>
}
function LiveIdentity(){
 const [data,setData]=useState<IdentityOverview|null>(null)
 const [error,setError]=useState('')
 useEffect(()=>{let mounted=true;identityOverview().then(x=>{if(mounted)setData(x)}).catch(e=>{if(mounted)setError(String(e))});return()=>{mounted=false}},[])
 return <>{error&&<Callout tone="warn" title="Enterprise identity unavailable">{error}</Callout>}
 <Panel title="Enterprise identity" description="Backend-backed connection status. Draft testing, activation and rollback remain disabled in this candidate to protect known-good identity configuration.">
 {!data?<p className="muted">Loading identity configuration…</p>:<dl className="v-kv cols">
 <div><dt>Active connection</dt><dd>{data.active?.displayName??'None'}</dd></div>
 <div><dt>Issuer</dt><dd>{data.active?.issuerUri??'Not configured'}</dd></div>
 <div><dt>Active version</dt><dd>{data.active?.configurationVersion??'—'}</dd></div>
 <div><dt>Staged draft</dt><dd>{data.staged?.displayName??'None'}</dd></div>
 <div><dt>Last draft test</dt><dd>{data.staged?.lastTestStatus??'Not tested'}</dd></div>
 <div><dt>Known-good rollback candidates</dt><dd>{data.rollbackCandidates.length}</dd></div>
 </dl>}</Panel></>
}
function LiveEnvironment(){
 const [info,setInfo]=useState<{productName:string;version:string;status:string;activeProfiles:string[];timestamp:string}|null>(null)
 const [error,setError]=useState('')
 useEffect(()=>{let mounted=true;systemInfo().then(x=>{if(mounted)setInfo(x)}).catch(e=>{if(mounted)setError(String(e))});return()=>{mounted=false}},[])
 return <>{error&&<Callout tone="warn" title="Environment unavailable">{error}</Callout>}
 <Panel title="Environment" description="Read-only facts retrieved from the connected backend.">{info?<dl className="v-kv cols">
 <div><dt>Product</dt><dd>{info.productName}</dd></div><div><dt>Version</dt><dd>{info.version}</dd></div><div><dt>Status</dt><dd>{info.status}</dd></div><div><dt>Active profiles</dt><dd>{info.activeProfiles.join(', ')||'None'}</dd></div>
 </dl>:<p className="muted">Loading environment…</p>}</Panel></>
}
