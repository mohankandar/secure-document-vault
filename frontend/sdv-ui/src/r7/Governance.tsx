import { useEffect, useState, type ReactNode } from 'react'
import { Lock, Pencil, Plus } from 'lucide-react'
import { AREA_TABS, useStore } from './store'
import { BASELINE, BUSINESS_LIMITS, CAPABILITY_LABELS, CONFIGURABLE, ROLES, type Capability, type SearchMode } from './data'
import { Badge, Callout, Drawer, PageHead, Panel, Scenario, Status, Switch, Tabs, fmtDate } from './ui'
import { getRolePolicy, saveRolePolicy, getRetentionPolicy, activateRetentionPolicy, type RolePolicy, type RetentionPolicyOverview, adminMetadataSources, adminMetadataFields, updateBusinessMetadata, type AdminMetadataSource, type AdminMetadataField } from './liveApi'

export function AreaFrame({ crumbs, title, lede, actions, children, attention = [] }: { crumbs: string[]; title: string; lede: ReactNode; actions?: ReactNode; children: ReactNode; attention?: string[] }) {
  const { route, navigate } = useStore()
  return (
    <div className="v-page">
      <PageHead crumbs={crumbs} title={title} lede={lede} actions={actions} />
      <Tabs label={`${crumbs[0]} sections`} value={route.tab} onChange={(t) => navigate(route.area, t)} tabs={AREA_TABS[route.area].map((t) => ({ ...t, attention: attention.includes(t.id) }))} />
      <div className="v-body">{children}</div>
    </div>
  )
}

const LEDE: Record<string, string> = {
  access: 'Who can do what with business content. Sign-in and role assignment live in your identity provider.',
  roles: 'Choose the document actions each business role may perform. Search and view are baseline.',
  retention: 'How long newly ingested documents are kept. Each activation creates an immutable version.',
  metadata: 'How each source’s fields are labelled and matched in business search.',
}

export function Governance() {
  const { route } = useStore()
  const tab = AREA_TABS.governance.find((t) => t.id === route.tab)!
  return (
    <AreaFrame crumbs={['Business administration', tab.label]} title={tab.label} lede={LEDE[route.tab]}>
      {route.tab === 'access' && <AccessTab />}
      {route.tab === 'roles' && <RolesTab />}
      {route.tab === 'retention' && <RetentionTab />}
      {route.tab === 'metadata' && <LiveMetadataTab />}
    </AreaFrame>
  )
}

function AccessTab() {
  const { roleCaps } = useStore()
  const business = ROLES.filter((r) => ['BUSINESS_USER', 'DOCUMENT_STEWARD', 'BUSINESS_ADMIN'].includes(r.code))
  return (
    <>
      <Panel flush title="Business roles" meta={business.length} description="Roles SDV recognises for business content, read from the sign-in token's roles claim.">
        <Scenario what="roles">
          <table className="v-table">
            <thead><tr><th>Role</th><th>Claim value</th><th>Responsibility</th><th className="num">Document actions</th><th>Configurable</th></tr></thead>
            <tbody>
              {business.map((r) => {
                const caps = r.code === 'BUSINESS_ADMIN' ? null : roleCaps[r.code as 'BUSINESS_USER']
                return (
                  <tr key={r.code}>
                    <td className="lead" data-label="Role"><strong style={{ fontWeight: 500 }}>{r.name}</strong></td>
                    <td data-label="Claim value"><span className="mono">{r.code}</span></td>
                    <td data-label="Responsibility" className="muted">{r.responsibility}</td>
                    <td data-label="Document actions" className="num">{caps ? `${caps.length} of 7` : '—'}</td>
                    <td data-label="Configurable">{caps ? <Badge tone="ok">Yes</Badge> : <Badge><Lock size={11} aria-hidden />Fixed</Badge>}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </Scenario>
      </Panel>
      <Callout title="Assignments are managed in your identity provider">
        Add or remove people from these roles in the IdP group or app-role assignment. SDV never stores passwords or grants roles itself, so there is no user editor here by design.
      </Callout>
    </>
  )
}

function RolesTab() {
  const { can, toast } = useStore()
  const [policy,setPolicy] = useState<RolePolicy|null>(null)
  const [draft,setDraft] = useState<RolePolicy['roles']>({})
  const [loading,setLoading] = useState(true)
  const [saving,setSaving] = useState(false)
  const [error,setError] = useState('')
  useEffect(()=>{let active=true;getRolePolicy().then(p=>{if(active){setPolicy(p);setDraft(p.roles)}}).catch(e=>{if(active)setError(String(e))}).finally(()=>{if(active)setLoading(false)});return()=>{active=false}},[])
  const cols = [{code:'BUSINESS_USER',name:'Business User'},{code:'DOCUMENT_STEWARD',name:'Document Steward'}]
  const caps:Capability[]=[...BASELINE,...CONFIGURABLE]
  const dirty=policy!==null && JSON.stringify(draft)!==JSON.stringify(policy.roles)
  const toggle=(code:string,c:Capability,on:boolean)=>setDraft(d=>({...d,[code]:{...d[code],[c]:on}}))
  const save=async()=>{
    if(saving||!can('CONFIGURE_ROLE_PERMISSIONS'))return
    setSaving(true);setError('')
    try{const result=await saveRolePolicy(draft);setPolicy(result);setDraft(result.roles);toast({title:'Permissions saved'})}
    catch(e){setError(String(e))}
    finally{setSaving(false)}
  }
  return <>
    {loading && <Callout title="Loading role permissions">Reading current tenant permissions…</Callout>}
    {error && <Callout tone="warn" title="Role permissions unavailable">{error}</Callout>}
    {policy && <Panel flush title="Document actions by role" description="Changes apply on each user's next request. Every change is audited.">
      <table className="v-table"><thead><tr><th>Action</th>{cols.map(c=><th key={c.code}>{c.name}</th>)}</tr></thead>
      <tbody>{caps.map(c=><tr key={c}><td className="lead" data-label="Action"><div className="stack" style={{gap:0}}><span style={{fontWeight:500}}>{CAPABILITY_LABELS[c]}</span><span className="mono faint" style={{fontSize:12}}>{c}</span></div>{c==='PERMANENT_REDACTION'&&<Badge tone="bad">Irreversible</Badge>}</td>
      {cols.map(col=><td key={col.code} data-label={col.name}>{BASELINE.includes(c)?<span className="row faint" style={{fontSize:13}}><Lock size={13}/>Baseline</span>:<Switch label={CAPABILITY_LABELS[c]+' for '+col.name} checked={!!draft[col.code]?.[c]} onChange={on=>toggle(col.code,c,on)} disabled={!can('CONFIGURE_ROLE_PERMISSIONS') || saving || !policy.configurableCapabilities.includes(c)}/>}</td>)}</tr>)}</tbody></table>
    </Panel>}
    {dirty&&<div className="v-panel" style={{position:'sticky',bottom:'calc(var(--bottom) + 12px)',zIndex:5}}><div className="v-panel-body row between wrap"><span><strong>Unsaved changes.</strong> <span className="muted">Changes take effect on the next request.</span></span><span className="row"><button type="button" className="v-btn" onClick={()=>setDraft(policy!.roles)} disabled={saving}>Discard</button><button type="button" className="v-btn primary" disabled={saving||!can('CONFIGURE_ROLE_PERMISSIONS')} onClick={()=>void save()}>Save changes</button></span></div></div>}
  </>
}

function RetentionTab() {
  const {can,toast}=useStore()
  const [policy,setPolicy]=useState<RetentionPolicyOverview|null>(null)
  const [error,setError]=useState('')
  const [loading,setLoading]=useState(true)
  const [saving,setSaving]=useState(false)
  const [open,setOpen]=useState(false)
  const [days,setDays]=useState('')
  const [note,setNote]=useState('')
  const [ack,setAck]=useState(false)
  useEffect(()=>{let active=true;getRetentionPolicy().then(p=>{if(active)setPolicy(p)}).catch(e=>{if(active)setError(String(e))}).finally(()=>{if(active)setLoading(false)});return()=>{active=false}},[])
  const n=Number(days)
  const valid=days.trim().length>0&&Number.isInteger(n)&&n>=1&&n<=36500
  const activate=async()=>{
    if(!policy||saving||!valid||!ack||!can('CONFIGURE_RETENTION'))return
    setSaving(true);setError('')
    try{
      const current=await getRetentionPolicy()
      if(current.policyVersionId!==policy.policyVersionId)throw new Error('Retention policy changed since this screen was loaded. Reload before activating a new version.')
      const updated=await activateRetentionPolicy('Retention policy version '+(policy.version+1),note,n)
      setPolicy(updated);setOpen(false);toast({title:'Retention policy activated',detail:'Newly ingested documents use the new retention period.'})
    }catch(e){setError(String(e))}
    finally{setSaving(false)}
  }
  return <>
    {loading&&<Callout title="Loading retention policy">Reading tenant retention configuration…</Callout>}
    {error&&<Callout tone="warn" title="Retention operation unavailable">{error}</Callout>}
    {policy&&<>
      <section className="v-panel"><div className="v-panel-body" style={{display:'flex',flexDirection:'column',gap:16}}>
        <div className="row between wrap" style={{gap:12}}>
          <div className="stack" style={{gap:4}}>
            <span className="row"><Status value="ACTIVE" label={'Version '+policy.version+' · active'}/></span>
            <span style={{fontSize:32,fontWeight:600,letterSpacing:'-0.02em',lineHeight:1.1}}>{policy.indefinite?'Indefinite':policy.defaultRetentionDays?.toLocaleString()+' days'}</span>
            <span className="muted">Effective {fmtDate(policy.effectiveFrom)} · by {policy.createdBy}</span>
          </div>
          <button type="button" className="v-btn primary" disabled={!can('CONFIGURE_RETENTION')} onClick={()=>{setOpen(true);setAck(false);setNote('');setDays(String(policy.defaultRetentionDays??365))}}><Plus size={16} aria-hidden/>New version</button>
        </div>
        <p className="muted" style={{fontSize:14}}>{policy.description||policy.policyName}</p>
      </div></section>
      <Panel flush title="Version history" meta={policy.history.length} description="Documents keep the retention they were ingested with. Older documents are never rewritten.">
        <table className="v-table"><thead><tr><th>Version</th><th className="num">Retention</th><th>Effective</th><th>Activated by</th><th>Status</th></tr></thead>
          <tbody>{policy.history.map(h=><tr key={h.policyVersionId}>
            <td className="lead" data-label="Version"><strong>v{h.version}</strong></td>
            <td className="num" data-label="Retention">{h.indefinite?'Indefinite':h.defaultRetentionDays?.toLocaleString()+' days'}</td>
            <td data-label="Effective">{fmtDate(h.effectiveFrom)}</td><td data-label="Activated by">{h.createdBy}</td>
            <td data-label="Status"><Status value={h.effectiveUntil?'RETIRED':'ACTIVE'} label={h.effectiveUntil?'Superseded':'Active'}/></td>
          </tr>)}</tbody>
        </table>
      </Panel>
      {open&&<Drawer eyebrow="Retention policy" title={'Activate version '+(policy.version+1)} onClose={()=>{if(!saving)setOpen(false)}} footer={<>
        <button type="button" className="v-btn" disabled={saving} onClick={()=>setOpen(false)}>Cancel</button>
        <button type="button" className="v-btn primary" disabled={!valid||!ack||saving} onClick={()=>void activate()}>{saving?'Activating…':'Activate version'}</button>
      </>}>
        <label className="v-field"><span>Retention period (days)</span><input className={'v-input'+(valid?'':' invalid')} inputMode="numeric" value={days} onChange={e=>setDays(e.target.value.replace(/\D/g,''))}/><small>{valid?'≈ '+(n/365).toFixed(1)+' years':'Enter a whole number between 1 and 36,500.'}</small></label>
        <label className="v-field"><span>Change note</span><textarea className="v-textarea" value={note} maxLength={1000} onChange={e=>setNote(e.target.value)} placeholder="Reference the records schedule or approval."/></label>
        <Callout tone="warn" title="Applies only to newly ingested documents">Existing documents retain their current retention snapshots. This version becomes immutable once active.</Callout>
        <label className="v-check"><input type="checkbox" checked={ack} onChange={e=>setAck(e.target.checked)}/><span>I confirm this retention change has been approved.</span></label>
      </Drawer>}
    </>}
  </>
}

function LiveMetadataTab(){
  const {can,toast}=useStore()
  const [sources,setSources]=useState<AdminMetadataSource[]>([])
  const [sourceId,setSourceId]=useState('')
  const [fields,setFields]=useState<AdminMetadataField[]>([])
  const [editing,setEditing]=useState<AdminMetadataField|null>(null)
  const [draft,setDraft]=useState<AdminMetadataField|null>(null)
  const [error,setError]=useState('')
  const [loading,setLoading]=useState(true)
  const [saving,setSaving]=useState(false)
  useEffect(()=>{let active=true;adminMetadataSources().then(rows=>{if(active){setSources(rows);setSourceId(rows[0]?.sourceId??'')}}).catch(e=>{if(active)setError(String(e))}).finally(()=>{if(active)setLoading(false)});return()=>{active=false}},[])
  useEffect(()=>{if(!sourceId)return;let active=true;setLoading(true);setError('');setFields([]);adminMetadataFields(sourceId).then(rows=>{if(active)setFields(rows)}).catch(e=>{if(active)setError(String(e))}).finally(()=>{if(active)setLoading(false)});return()=>{active=false}},[sourceId])
  const save=async()=>{
    if(!draft||!can('CONFIGURE_METADATA_SEARCH')||saving||!draft.displayLabel.trim())return
    setSaving(true);setError('')
    try{
      const result=await updateBusinessMetadata(sourceId,{...draft,displayLabel:draft.displayLabel.trim()})
      setFields(rows=>rows.map(f=>f.fieldKey===result.fieldKey?result:f))
      setEditing(null);setDraft(null);toast({title:'Field updated'})
    }catch(e){setError(String(e))}
    finally{setSaving(false)}
  }
  return <>
    {error&&<Callout tone="warn" title="Metadata operation unavailable">{error}</Callout>}
    <Panel flush title="Searchable fields" meta={fields.length} description="Field keys come from Tenant IT's integration contract. Here you control labels and matching."
      actions={<select className="v-select" aria-label="Source" value={sourceId} onChange={e=>setSourceId(e.target.value)} style={{minWidth:180}}>
        {sources.map(s=><option key={s.sourceId} value={s.sourceId}>{s.displayName}</option>)}
      </select>}>
      {loading?<p className="muted">Loading metadata…</p>:fields.length===0?<Callout title="No source fields defined">Tenant IT must define the integration metadata contract first.</Callout>:
      <table className="v-table"><thead><tr><th>Label</th><th>Field key</th><th>Role</th><th>Search</th><th>Matching</th><th className="act"><span className="sr-only">Edit</span></th></tr></thead>
        <tbody>{fields.map(f=><tr key={f.fieldKey}>
          <td className="lead" data-label="Label"><strong>{f.displayLabel}</strong></td><td data-label="Field key"><span className="mono">{f.fieldKey}</span></td>
          <td data-label="Role">{f.businessIdentifier?<Badge tone="ok">Identifier</Badge>:<span className="muted">Attribute</span>}</td>
          <td data-label="Search">{f.searchable?'Searchable':<span className="faint">Hidden</span>}</td>
          <td data-label="Matching">{f.searchable?MODE_LABEL[f.searchMode]:'—'}</td>
          <td className="act"><button type="button" className="v-btn sm" disabled={!can('CONFIGURE_METADATA_SEARCH')} onClick={()=>{setEditing(f);setDraft({...f})}}><Pencil size={14} aria-hidden/>Edit</button></td>
        </tr>)}</tbody></table>}
    </Panel>
    <Panel title="Business limits" description="Configured business operation limits."><dl className="v-kv cols">
      <div><dt>Maximum search results</dt><dd>{BUSINESS_LIMITS.maxResults}</dd></div><div><dt>Results per page</dt><dd>{BUSINESS_LIMITS.pageSize}</dd></div>
      <div><dt>Comment length</dt><dd>{BUSINESS_LIMITS.maxCommentLength.toLocaleString()} characters</dd></div><div><dt>Tag length</dt><dd>{BUSINESS_LIMITS.maxTagLength} characters</dd></div>
    </dl></Panel>
    {editing&&draft&&<Drawer eyebrow={sources.find(s=>s.sourceId===sourceId)?.displayName??''} title="Edit search behavior" onClose={()=>{if(!saving){setEditing(null);setDraft(null)}}}
      footer={<><button type="button" className="v-btn" disabled={saving} onClick={()=>{setEditing(null);setDraft(null)}}>Cancel</button><button type="button" className="v-btn primary" disabled={!draft.displayLabel.trim()||saving} onClick={()=>void save()}>{saving?'Saving…':'Save field'}</button></>}>
      <dl className="v-kv"><div><dt>Field key</dt><dd className="mono">{draft.fieldKey}</dd></div><div><dt>Type · normalization</dt><dd>{draft.fieldType} · {draft.normalization}</dd></div><div><dt>Identifier</dt><dd>{draft.businessIdentifier?'Yes — defined by the integration contract':'No'}</dd></div></dl>
      <label className="v-field"><span>Display label</span><input className="v-input" value={draft.displayLabel} onChange={e=>setDraft({...draft,displayLabel:e.target.value})}/></label>
      <div className="row between"><span className="v-label">Show in business search</span><Switch label="Searchable" checked={draft.searchable} disabled={draft.businessIdentifier} onChange={v=>setDraft({...draft,searchable:v})}/></div>
      {draft.businessIdentifier&&<small className="faint">Identifiers are always searchable.</small>}
      <label className="v-field"><span>Match behavior</span><select className="v-select" value={draft.searchMode} onChange={e=>setDraft({...draft,searchMode:e.target.value as AdminMetadataField['searchMode']})}>{Object.entries(MODE_LABEL).map(([mode,label])=><option key={mode} value={mode}>{label}</option>)}</select></label>
    </Drawer>}
  </>
}

const MODE_LABEL: Record<SearchMode, string> = { EXACT: 'Exact match', PREFIX: 'Starts with', PARTIAL: 'Contains' }
