import { ConnectedIntegrationsView } from './ConnectedIntegrationsView'
import { ConnectedAuthenticationView } from './ConnectedAuthenticationView'
import { useEffect, useRef, useState } from 'react'
import { itSources, itFields, itUpsertField, itCreateSource, itUpdateSource, itSourceSummary, liveStorage, identityOverview, systemInfo, createStorageVolume, updateStorageVolume, saveIdentityDraft, testIdentityDraft, activateIdentityDraft, rollbackIdentity, type LiveStorageVolume, type IdentityOverview, type ItSourceSummary, type ItSource, type ItField } from './liveApi'
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
 const [section,setSection]=useState<'configuration'|'monitoring'|'contract'>('configuration')
 const [sourceConfirm,setSourceConfirm]=useState(false)
 const selectedSourceRef=useRef(sourceId)
 selectedSourceRef.current=sourceId
 useEffect(()=>{let live=true;itSources().then(rows=>{if(live){setSources(rows);setSourceId(rows[0]?.sourceId??'')}}).catch(e=>{if(live)setError(String(e))});return()=>{live=false}},[])
 useEffect(()=>{if(!sourceId){setFields([]);setSummary(null);return}let live=true;setFields([]);setSummary(null);setError('');Promise.allSettled([itSourceSummary(sourceId),itFields(sourceId)]).then(([sr,fr])=>{if(!live)return;if(sr.status==='fulfilled')setSummary(sr.value);else setError(String(sr.reason));if(fr.status==='fulfilled')setFields(fr.value);else setError(String(fr.reason))});return()=>{live=false}},[sourceId])
 const valid=/^[A-Za-z][A-Za-z0-9_.-]{0,99}$/.test(key)&&label.trim().length>0&&(revising?.fieldKey===key||!fields.some(f=>f.fieldKey===key))&&!(type==='BOOLEAN'&&normalization==='DIGITS_ONLY')&&(!revising||ack)
 const save=async()=>{if(!valid||busy)return;setBusy(true);setError('');try{
  await itUpsertField(sourceId,key,{displayLabel:label.trim(),fieldType:type,required,normalization,validationRules:{}})
  const updatedFields=await itFields(sourceId);if(selectedSourceRef.current===sourceId)setFields(updatedFields);setForm(false);setRevising(null);setAck(false);setKey('');setLabel('');setRequired(false)
 }catch(e){setError(String(e))}finally{setBusy(false)}}
 const source=sources.find(x=>x.sourceId===sourceId)
 const editSource=()=>{if(!source)return;setSourceConfirm(false);setSourceKey(source.sourceKey);setSourceName(source.displayName);setAuthType(source.authType);setCredential(source.credentialReference);setSourceStatus(source.status);setSourceForm('edit')}
 const createSource=()=>{setSourceConfirm(false);setSourceKey('');setSourceName('');setAuthType('OAUTH2_CLIENT_CREDENTIALS');setCredential('');setSourceStatus('ACTIVE');setSourceForm('new')}
 const sourceValid=sourceName.trim().length>0&&(sourceForm==='edit'||/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(sourceKey.trim()))
 const persistSource=async()=>{if(!sourceForm||!sourceValid||sourceBusy)return;
   if(sourceForm==='edit'&&source&&(source.authType!==authType||source.status!==sourceStatus)&&!sourceConfirm){setSourceConfirm(true);return}setSourceBusy(true);setError('')
   try{
     const result=sourceForm==='new'
       ?await itCreateSource({sourceKey:sourceKey.trim(),displayName:sourceName.trim(),authType,credentialReference:credential.trim()})
       :await itUpdateSource(sourceId,{displayName:sourceName.trim(),status:sourceStatus,authType,credentialReference:credential.trim(),limits:source?.limits})
     const rows=await itSources();setSources(rows);setSourceId(result.sourceId);setSection(sourceForm==='new'?'contract':'configuration');setSourceForm(null);setSourceConfirm(false)
   }catch(e){setError(String(e))}finally{setSourceBusy(false)}
 }
 return <>
 {error&&<Callout tone="warn" title="Source configuration unavailable">{error}</Callout>}
 <ConnectedIntegrationsView sources={sources} selected={source} fields={fields} summary={summary} section={section} onSelect={id=>{setSourceId(id);setSection('configuration');setForm(false);setError('')}} onSection={setSection} onCreate={createSource} onEdit={editSource} onAddField={()=>{setRevising(null);setKey('');setLabel('');setType('TEXT');setNormalization('NONE');setRequired(false);setAck(false);setForm(true)}} onRevise={f=>{setRevising(f);setKey(f.fieldKey);setLabel(f.displayLabel);setType(f.fieldType);setNormalization(f.normalization);setRequired(f.required);setAck(false);setForm(true)}}/>
 {sourceForm&&<Drawer eyebrow="Tenant IT · integration" title={sourceForm==='new'?'Add integration':'Edit integration'} onClose={()=>{if(!sourceBusy)setSourceForm(null)}} footer={<><button type="button" className="v-btn" disabled={sourceBusy} onClick={()=>setSourceForm(null)}>Cancel</button><button type="button" className="v-btn primary" disabled={!sourceValid||sourceBusy} onClick={()=>void persistSource()}>{sourceBusy?'Saving…':sourceForm==='new'?'Add integration':'Save integration'}</button></>}>
 <label className="v-field"><span>Source key</span><input className="v-input" readOnly={sourceForm==='edit'} value={sourceKey} onChange={e=>setSourceKey(e.target.value)}/><small>Immutable identity of the external producer after creation.</small></label>
 <label className="v-field"><span>Display name</span><input className="v-input" value={sourceName} onChange={e=>setSourceName(e.target.value)}/></label>
 <label className="v-field"><span>Authentication</span><select className="v-select" value={authType} onChange={e=>setAuthType(e.target.value as ItSource['authType'])}><option value="OAUTH2_CLIENT_CREDENTIALS">OAuth 2.0 client credentials</option><option value="JWT_BEARER">JWT bearer</option><option value="MTLS">Mutual TLS</option></select></label>
 <label className="v-field"><span>Credential reference</span><input className="v-input" value={credential} onChange={e=>setCredential(e.target.value)}/><small>Reference only; do not enter credentials or secret values.</small></label>
 {sourceForm==='edit'&&<label className="v-field"><span>Status</span><select className="v-select" value={sourceStatus} onChange={e=>setSourceStatus(e.target.value as ItSource['status'])}><option value="ACTIVE">Active</option><option value="DISABLED">Disabled</option></select></label>}
 {sourceConfirm&&<Callout tone="warn" title="Confirm production impact">This changes the authentication method or status of a configured producer. Verify the external producer is ready, then click Save again to confirm.</Callout>}
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
 const [editing,setEditing]=useState<LiveStorageVolume|'new'|null>(null)
 const [name,setName]=useState('')
 const [root,setRoot]=useState('')
 const [state,setState]=useState('ACTIVE')
 const [priority,setPriority]=useState('0')
 const [warning,setWarning]=useState('80')
 const [stop,setStop]=useState('95')
 const [busy,setBusy]=useState(false)
 const [confirmed,setConfirmed]=useState(false)
 const refresh=()=>liveStorage().then(setItems).catch(e=>setError(String(e)))
 useEffect(()=>{void refresh()},[])
 const begin=(v:LiveStorageVolume|'new')=>{setEditing(v);setName(v==='new'?'':v.volume.displayName);setRoot(v==='new'?'':v.volume.rootPath);setState(v==='new'?'ACTIVE':v.volume.state);setPriority(String(v==='new'?0:v.volume.priority));setWarning(String(v==='new'?80:v.volume.warningPercent));setStop(String(v==='new'?95:v.volume.stopWritePercent));setConfirmed(false)}
 const valid=name.trim().length>0&&Number.isInteger(Number(priority))&&Number(priority)>=0&&Number.isInteger(Number(warning))&&Number.isInteger(Number(stop))&&Number(warning)>0&&Number(stop)<=100&&Number(stop)>Number(warning)&&(editing!=='new'||root.trim().length>0)
 const save=async()=>{if(!editing||!valid||!confirmed||busy)return;setBusy(true);setError('')
 try{const v={displayName:name.trim(),state,priority:Number(priority),warningPercent:Number(warning),stopWritePercent:Number(stop)}
 if(editing==='new')await createStorageVolume({...v,rootPath:root.trim()});else await updateStorageVolume(editing.volume.volumeId,v)
 await refresh();setEditing(null)}catch(e){setError(String(e))}finally{setBusy(false)}}
 return <>{error&&<Callout tone="warn" title="Storage operation unavailable">{error}</Callout>}
 <Panel flush title="Storage volumes" meta={items.length} description="Live capacity, warning thresholds and stop-write status. Root paths are immutable after creation."
 actions={<button type="button" className="v-btn sm primary" onClick={()=>begin('new')}><Plus size={14}/>Add volume</button>}>
 {items.some(v=>v.observationError||v.stopWriteThresholdReached||v.warningThresholdReached)&&<div className="v-it-storage-alert"><strong>Storage needs attention</strong><span>One or more live volumes have reported a warning, stop-write threshold, or observation problem. Review the individual volume before changing settings.</span></div>}
 <table className="v-table"><thead><tr><th>Volume</th><th>State</th><th>Health</th><th>Capacity</th><th>Used</th><th className="act">Action</th></tr></thead><tbody>{items.map(v=>{const pct=v.capacity?.usedPercent;const observed=pct!=null&&Number.isFinite(pct);return <tr key={v.volume.volumeId}><td className="lead"><strong>{v.volume.displayName}</strong><div className="mono muted" style={{fontSize:12}}>{v.volume.rootPath}</div></td><td><span className="v-it-state" data-state={v.volume.state}>{v.volume.state.replace('_',' ')}</span></td><td><span className="v-it-health" data-attention={!!(v.observationError||v.stopWriteThresholdReached||v.warningThresholdReached)}>{v.observationError?'Probe unavailable':v.stopWriteThresholdReached?'Stop-write reached':v.warningThresholdReached?'Warning threshold':v.capacity?'No alert reported':'No observation'}</span><div className="muted" style={{fontSize:12}}>Writable: {v.capacity?v.capacity.writable?'Yes':'No':'Unknown'}</div></td><td><div className="v-it-capacity" aria-label={observed?`${pct}% used, warning ${v.volume.warningPercent}%, stop-write ${v.volume.stopWritePercent}%`:'Capacity unavailable'}>{observed?<><div className="v-it-capacity-fill" data-attention={!!(v.stopWriteThresholdReached||v.warningThresholdReached)} style={{width:`${Math.max(0,Math.min(100,Number(pct)))}%`}}/><i style={{left:`${v.volume.warningPercent}%`}}/><b style={{left:`${v.volume.stopWritePercent}%`}}/></>:<span className="muted">Unavailable</span>}</div><small className="muted">Warning {v.volume.warningPercent}% · Stop-write {v.volume.stopWritePercent}%</small></td><td className="num">{observed?`${pct}%`:'—'}</td><td className="act"><button className="v-btn sm" type="button" onClick={()=>begin(v)}><Pencil size={14}/>Edit</button></td></tr>})}</tbody></table></Panel>
 {editing&&<Drawer eyebrow="Tenant IT · storage" title={editing==='new'?'Add volume':'Edit volume'} onClose={()=>{if(!busy)setEditing(null)}} footer={<><button type="button" className="v-btn" disabled={busy} onClick={()=>setEditing(null)}>Cancel</button><button type="button" className="v-btn primary" disabled={!valid||!confirmed||busy} onClick={()=>void save()}>{busy?'Saving…':'Save volume'}</button></>}>
 <label className="v-field"><span>Display name</span><input className="v-input" value={name} onChange={e=>setName(e.target.value)}/></label>
 <label className="v-field"><span>Root path</span><input className="v-input" readOnly={editing!=='new'} value={root} onChange={e=>setRoot(e.target.value)}/></label>
 <label className="v-field"><span>State</span><select className="v-select" value={state} onChange={e=>setState(e.target.value)}><option value="ACTIVE">Active</option><option value="READ_ONLY">Read-only</option><option value="OFFLINE">Offline</option></select></label>
 <label className="v-field"><span>Priority</span><input className="v-input" type="number" min="0" value={priority} onChange={e=>setPriority(e.target.value)}/></label>
 <label className="v-field"><span>Warning threshold (%)</span><input className="v-input" type="number" min="1" max="99" value={warning} onChange={e=>setWarning(e.target.value)}/></label>
 <label className="v-field"><span>Stop-write threshold (%)</span><input className="v-input" type="number" min="2" max="100" value={stop} onChange={e=>setStop(e.target.value)}/></label>
 <Callout tone="warn" title="Storage impact">Changes to state or thresholds can stop document ingestion. Existing root paths cannot be changed or migrated here.</Callout>
 <label className="v-check"><input type="checkbox" checked={confirmed} onChange={e=>setConfirmed(e.target.checked)}/><span>I have verified the storage change and its effect on ingestion.</span></label></Drawer>}
 </>
}
function LiveIdentity(){
 const [data,setData]=useState<IdentityOverview|null>(null)
 const [error,setError]=useState('')
 const [edit,setEdit]=useState(false)
 const [provider,setProvider]=useState('')
 const [name,setName]=useState('')
 const [issuer,setIssuer]=useState('')
 const [client,setClient]=useState('')
 const [audience,setAudience]=useState('')
 const [busy,setBusy]=useState(false)
 const [confirm,setConfirm]=useState('')
 const [action,setAction]=useState<'activate'|'rollback'|null>(null)
 const [target,setTarget]=useState<{connectionId:string;configurationVersion:number}|null>(null)
 const refresh=()=>identityOverview().then(setData).catch(e=>setError(String(e)))
 useEffect(()=>{void refresh()},[])
 const draft=()=>{const c=data?.staged??data?.active;setProvider(c?.providerId??'customer-oidc');setName(c?.displayName??'');setIssuer(c?.issuerUri??'');setClient(c?.browserClientId??'');setAudience(c?.apiAudience??'');setEdit(true)}
 const perform=async(fn:()=>Promise<IdentityOverview>)=>{setBusy(true);setError('');try{setData(await fn());setAction(null);setEdit(false);setConfirm('')}catch(e){setError(String(e))}finally{setBusy(false)}}
 const stageValid=name.trim()&&issuer.trim()&&client.trim()&&audience.trim()
 return <>{error&&<Callout tone="warn" title="Enterprise identity operation unavailable">{error}</Callout>}
 {!data?<p className="muted">Loading identity configuration…</p>:<ConnectedAuthenticationView auth={data} busy={busy} onEdit={draft} onTest={()=>void perform(testIdentityDraft)} onActivate={()=>{if(!data.staged)return;setTarget(data.staged);setConfirm('');setAction('activate')}} onRollback={c=>{setTarget(c);setConfirm('');setAction('rollback')}}/>}
 {edit&&<Drawer eyebrow="Tenant IT · authentication" title="Identity draft" onClose={()=>{if(!busy)setEdit(false)}} footer={<><button className="v-btn" type="button" disabled={busy} onClick={()=>setEdit(false)}>Cancel</button><button className="v-btn primary" type="button" disabled={!stageValid||busy} onClick={()=>void perform(()=>saveIdentityDraft({providerId:provider,displayName:name,issuerUri:issuer,browserClientId:client,apiAudience:audience}))}>Save draft</button></>}>
 {([['Provider ID',provider,setProvider],['Display name',name,setName],['Issuer URI',issuer,setIssuer],['Browser client ID',client,setClient],['API audience',audience,setAudience]] as const).map(([label,value,set])=><label key={label} className="v-field"><span>{label}</span><input className="v-input" value={value} onChange={e=>set(e.target.value)}/></label>)}
 <Callout tone="warn" title="Identity safety">Saving creates a staged configuration. It does not activate it. Test the draft before activation.</Callout></Drawer>}
 {action&&target&&<Drawer eyebrow="Tenant IT · authentication" title={action==='activate'?'Activate identity':'Roll back identity'} onClose={()=>{if(!busy)setAction(null)}} footer={<><button type="button" className="v-btn" disabled={busy} onClick={()=>setAction(null)}>Cancel</button><button type="button" className="v-btn primary" disabled={busy||confirm!==(action==='activate'?'ACTIVATE':'ROLLBACK')} onClick={()=>void perform(()=>action==='activate'?activateIdentityDraft(target.connectionId,target.configurationVersion,confirm):rollbackIdentity(target.connectionId,target.configurationVersion,confirm))}>Confirm {action}</button></>}>
 <Callout tone="warn" title="Authentication-impacting operation">This immediately changes the trusted server-side OIDC binding. Confirm the deployed browser/client OIDC configuration already matches the target before activation. The backend checks the exact connection and version and enforces known-good rollback and test freshness.</Callout>
 <dl className="v-kv"><div><dt>Target connection</dt><dd className="mono">{target.connectionId}</dd></div><div><dt>Target version</dt><dd>{target.configurationVersion}</dd></div><div><dt>Current active version</dt><dd>{data?.active?.configurationVersion??"None"}</dd></div></dl>
 <label className="v-field"><span>Enter {action==='activate'?'ACTIVATE':'ROLLBACK'} to confirm</span><input className="v-input" value={confirm} onChange={e=>setConfirm(e.target.value)}/></label></Drawer>}
 </>
}
function LiveEnvironment(){
 const [info,setInfo]=useState<{productName:string;version:string;status:string;activeProfiles:string[];timestamp:string}|null>(null)
 const [error,setError]=useState('')
 useEffect(()=>{let mounted=true;systemInfo().then(x=>{if(mounted)setInfo(x)}).catch(e=>{if(mounted)setError(String(e))});return()=>{mounted=false}},[])
 return <>{error&&<Callout tone="warn" title="Environment unavailable">{error}</Callout>}
 <Panel title="Environment" description="Read-only facts retrieved from the connected backend.">{info?<dl className="v-kv cols">
 <div><dt>Product</dt><dd>{info.productName}</dd></div><div><dt>Version</dt><dd>{info.version}</dd></div><div><dt>Status</dt><dd>{info.status}</dd></div><div><dt>Active profiles</dt><dd>{info.activeProfiles.join(', ')||'None'}</dd></div><div><dt>Observed at</dt><dd>{fmtDate(info.timestamp)}</dd></div>
 </dl>:<p className="muted">Loading environment…</p>}</Panel></>
}
