import { useEffect, useState, type ReactNode } from 'react'
import { Lock, Pencil, Plus } from 'lucide-react'
import { AREA_TABS, useStore } from './store'
import { BUSINESS_LIMITS, type Capability, type SearchMode } from './data'
import { ActionError, FailureNotice, LoadErrorCard, actionFailed, loadFailed, toFailure, type Failure } from './CardError'
import { Badge, Callout, Drawer, PageHead, Panel, Scenario, Status, Switch, Tabs, fmtDate } from './ui'
import { getRolePolicy, saveRolePolicy, getRetentionPolicy, activateRetentionPolicy, type RolePolicy, type RetentionPolicyOverview, adminMetadataSources, adminMetadataFields, updateBusinessMetadata, getMetadataDisplayOrder, saveMetadataDisplayOrder, type AdminMetadataSource, type AdminMetadataField } from './liveApi'

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
  access: 'The roles Vero knows, and what each one is for. People get a role in your company’s sign-in system.',
  roles: 'Choose what each business role may do with documents.',
  retention: 'How long newly ingested documents are kept. Each activation creates an immutable version.',
  metadata: 'How each source’s fields are labelled and matched in business search.',
}

export function Governance() {
  const { route } = useStore()
  const tab = AREA_TABS.governance.find((t) => t.id === route.tab)!
  return (
    <AreaFrame crumbs={['Business administration', tab.label]} title={tab.label} lede={LEDE[route.tab]}>
      {route.tab === 'access' && <PeopleTab />}
      {route.tab === 'roles' && <RolesTab />}
      {route.tab === 'retention' && <RetentionTab />}
      {route.tab === 'metadata' && <LiveMetadataTab />}
    </AreaFrame>
  )
}

/** The roles a customer manages. Platform operations is the service provider's role and is not listed. */
const PEOPLE_ROLES: { name: string; purpose: string; claim: string; changeable: boolean }[] = [
  { name: 'Business user', purpose: 'Find records and read their documents', claim: 'BUSINESS_USER', changeable: true },
  { name: 'Document steward', purpose: 'Everything a business user does, plus download and permanent redaction', claim: 'DOCUMENT_STEWARD', changeable: true },
  { name: 'Business admin', purpose: 'Rules for roles, keeping documents and search fields', claim: 'BUSINESS_ADMIN', changeable: false },
  { name: 'Tenant IT', purpose: 'Storage, sign-in and the systems that send documents', claim: 'TENANT_IT_ADMIN', changeable: false },
]

function PeopleTab() {
  return <>
    <Panel flush>
      <table className="v-table"><thead><tr><th>Role</th><th>What it is for</th><th>Name in your sign-in system</th><th>Can change in Vero</th></tr></thead>
        <tbody>{PEOPLE_ROLES.map((r) => <tr key={r.claim}>
          <td className="lead" data-label="Role"><strong style={{ fontWeight: 500 }}>{r.name}</strong></td>
          <td data-label="What it is for">{r.purpose}</td>
          <td data-label="Name in your sign-in system"><span className="mono">{r.claim}</span></td>
          <td data-label="Can change in Vero" className={r.changeable ? undefined : 'faint'}>{r.changeable ? 'Yes, in Roles & permissions' : 'Fixed'}</td>
        </tr>)}</tbody>
      </table>
    </Panel>
    <Callout title="To give someone a role,">add them to the group with that name in your sign-in system (for example Okta). Vero never stores passwords and does not keep its own list of people, so there is nothing to add here.</Callout>
    <p className="faint" style={{ fontSize: 13 }}>Platform operations is your service provider’s role and is not listed here.</p>
  </>
}

/** Document actions in words. "Search records and open documents" is always on (search and view). */
const ROLE_ACTIONS: { label: string; capability?: Capability; permanent?: boolean }[] = [
  { label: 'Search records and open documents' },
  { label: 'Download the PDF', capability: 'DOWNLOAD_CURRENT_PDF' },
  { label: 'Print', capability: 'PRINT_DOCUMENT' },
  { label: 'Add tags', capability: 'ADD_DOCUMENT_TAG' },
  { label: 'Add comments', capability: 'ADD_DOCUMENT_COMMENT' },
  { label: 'Permanently redact', capability: 'PERMANENT_REDACTION', permanent: true },
]

function RolesTab() {
  const { can, toast } = useStore()
  const [policy,setPolicy] = useState<RolePolicy|null>(null)
  const [draft,setDraft] = useState<RolePolicy['roles']>({})
  const [loading,setLoading] = useState(true)
  const [saving,setSaving] = useState(false)
  const [loadFailure,setLoadFailure] = useState<Failure|null>(null)
  const [saveFailure,setSaveFailure] = useState<Failure|null>(null)
  const [reload,setReload] = useState(0)
  useEffect(()=>{let active=true;setLoading(true);setLoadFailure(null);getRolePolicy().then(p=>{if(active){setPolicy(p);setDraft(p.roles)}}).catch(e=>{if(active)setLoadFailure(toFailure('Roles & permissions did not load',e))}).finally(()=>{if(active)setLoading(false)});return()=>{active=false}},[reload])
  const cols = [{code:'BUSINESS_USER',name:'Business user'},{code:'DOCUMENT_STEWARD',name:'Document steward'}]
  const dirty=policy!==null && JSON.stringify(draft)!==JSON.stringify(policy.roles)
  const toggle=(code:string,c:Capability,on:boolean)=>setDraft(d=>({...d,[code]:{...d[code],[c]:on}}))
  const save=async()=>{
    if(saving||!can('CONFIGURE_ROLE_PERMISSIONS'))return
    setSaving(true);setSaveFailure(null)
    try{const result=await saveRolePolicy(draft);setPolicy(result);setDraft(result.roles);toast({tone:'ok',title:'Permissions saved'})}
    catch(e){setSaveFailure(toFailure('Saving roles & permissions failed',e))}
    finally{setSaving(false)}
  }
  return <>
    {loading && !policy && <Callout title="Loading roles & permissions" />}
    {loadFailure && <LoadErrorCard title="Roles & permissions didn't load" failure={loadFailure} onRetry={()=>setReload(n=>n+1)} />}
    {saveFailure && <ActionError title="The changes weren't saved" failure={saveFailure} />}
    {policy && <section className="v-panel flush">
      <div className="v-panel-body">
        <table className="v-table"><thead><tr><th>Action</th>{cols.map(c=><th key={c.code}>{c.name}</th>)}</tr></thead>
        <tbody>{ROLE_ACTIONS.map(a=><tr key={a.label}><td className="lead" data-label="Action"><span style={{fontWeight:500}}>{a.label}</span>{a.permanent&&<> <Badge tone="bad">Cannot be undone</Badge></>}</td>
        {cols.map(col=><td key={col.code} data-label={col.name}>{!a.capability?<span className="faint" style={{fontSize:13}}>Always on</span>:<Switch label={a.label+' for '+col.name} checked={!!draft[col.code]?.[a.capability]} onChange={on=>toggle(col.code,a.capability!,on)} disabled={!can('CONFIGURE_ROLE_PERMISSIONS') || saving || !policy.configurableCapabilities.includes(a.capability)}/>}</td>)}</tr>)}</tbody></table>
      </div>
      <footer className="roles-foot row wrap">
        <span className="faint grow" style={{fontSize:13}}>Changes apply the next time each person does something. Every change is recorded in the audit.</span>
        <button type="button" className="v-btn" onClick={()=>setDraft(policy.roles)} disabled={!dirty||saving}>Undo changes</button>
        <button type="button" className="v-btn primary" disabled={!dirty||saving||!can('CONFIGURE_ROLE_PERMISSIONS')} onClick={()=>void save()}>{saving?'Saving…':'Save'}</button>
      </footer>
    </section>}
  </>
}

function RetentionTab() {
  const {can,toast}=useStore()
  const [policy,setPolicy]=useState<RetentionPolicyOverview|null>(null)
  const [error,setError]=useState<Failure|null>(null)
  const [reload,setReload]=useState(0)
  const [stale,setStale]=useState(false)
  const [loading,setLoading]=useState(true)
  const [saving,setSaving]=useState(false)
  const [open,setOpen]=useState(false)
  const [days,setDays]=useState('')
  const [indefinite,setIndefinite]=useState(false)
  const [note,setNote]=useState('')
  const [ack,setAck]=useState(false)
  useEffect(()=>{let active=true;getRetentionPolicy().then(p=>{if(active)setPolicy(p)}).catch(e=>{if(active)setError(loadFailed('Keeping documents did not load',e))}).finally(()=>{if(active)setLoading(false)});return()=>{active=false}},[reload])
  const n=Number(days)
  const valid=indefinite||(days.trim().length>0&&Number.isInteger(n)&&n>=1&&n<=36500)
  const activate=async()=>{
    if(!policy||saving||!valid||!ack||!can('CONFIGURE_RETENTION'))return
    setSaving(true);setError(null);setStale(false)
    try{
      const current=await getRetentionPolicy()
      if(current.policyVersionId!==policy.policyVersionId){setStale(true);return}
      const updated=await activateRetentionPolicy('Retention policy version '+(policy.version+1),note,indefinite?null:n)
      setPolicy(updated);setOpen(false);toast({title:'Retention policy activated',detail:'Newly ingested documents use the new retention period.'})
    }catch(e){setError(actionFailed('Starting a new keeping rule failed',e))}
    finally{setSaving(false)}
  }
  return <>
    {loading&&<Callout title="Loading retention policy">Reading tenant retention configuration…</Callout>}
    {error&&<FailureNotice failure={error} loadTitle="Keeping documents didn't load" actionTitle="The new rule wasn't started" onRetry={()=>setReload(n=>n+1)} />}
    {stale&&<Callout tone="warn" title="The rule changed while you were editing">Someone else started a new rule. Reload the page, then make your change again.</Callout>}
    {policy&&<>
      <section className="v-panel"><div className="v-panel-body" style={{display:'flex',flexDirection:'column',gap:16}}>
        <div className="row between wrap" style={{gap:12}}>
          <div className="stack" style={{gap:4}}>
            <span className="row"><Status value="ACTIVE" label={'Version '+policy.version+' · active'}/></span>
            <span style={{fontSize:32,fontWeight:600,letterSpacing:'-0.02em',lineHeight:1.1}}>{policy.indefinite?'Indefinite':policy.defaultRetentionDays?.toLocaleString()+' days'}</span>
            <span className="muted">{policy.indefinite?'No expiration':policy.defaultRetentionDays!=null?'≈ '+(policy.defaultRetentionDays/365).toFixed(1)+' years after ingestion · ':''} Effective {fmtDate(policy.effectiveFrom)} · by {policy.createdBy}</span>
          </div>
          <button type="button" className="v-btn primary" disabled={!can('CONFIGURE_RETENTION')} onClick={()=>{setOpen(true);setAck(false);setNote('');setIndefinite(policy.indefinite);setDays(String(policy.defaultRetentionDays??365))}}><Plus size={16} aria-hidden/>New version</button>
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
        <label className="v-check"><input type="checkbox" checked={indefinite} onChange={e=>setIndefinite(e.target.checked)}/><span>Indefinite retention (no retention expiration)</span></label>
        {!indefinite&&<label className="v-field"><span>Retention period (days)</span><input className={'v-input'+(valid?'':' invalid')} inputMode="numeric" value={days} onChange={e=>setDays(e.target.value.replace(/\D/g,''))}/><small>{valid?'≈ '+(n/365).toFixed(1)+' years':'Enter a whole number between 1 and 36,500.'}</small></label>}
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
  const [error,setError]=useState<Failure|null>(null)
  const [reload,setReload]=useState(0)
  const [loading,setLoading]=useState(true)
  const [saving,setSaving]=useState(false)
  const [ordering,setOrdering]=useState(false)
  const [orderDraft,setOrderDraft]=useState<string[]>([])
  const [orderSaved,setOrderSaved]=useState<string[]>([])
  const [orderSaving,setOrderSaving]=useState(false)
  useEffect(()=>{let active=true;adminMetadataSources().then(rows=>{if(active){setSources(rows);setSourceId(rows[0]?.sourceId??'')}}).catch(e=>{if(active)setError(loadFailed('Search fields did not load',e))}).finally(()=>{if(active)setLoading(false)});return()=>{active=false}},[reload])
  useEffect(()=>{if(!sourceId){setFields([]);setEditing(null);setDraft(null);return}let active=true;setLoading(true);setError(null);setFields([]);setOrdering(false);setEditing(null);setDraft(null);Promise.all([adminMetadataFields(sourceId),getMetadataDisplayOrder(sourceId)]).then(([rows,order])=>{
    if(active){
      const keys=[...order.filter(k=>rows.some(f=>f.fieldKey===k)),...rows.map(f=>f.fieldKey).filter(k=>!order.includes(k))]
      setFields([...rows].sort((a,b)=>keys.indexOf(a.fieldKey)-keys.indexOf(b.fieldKey)))
      setOrderDraft(keys);setOrderSaved(keys)
    }
  }).catch(e=>{if(active)setError(loadFailed('Search fields did not load',e))}).finally(()=>{if(active)setLoading(false)});return()=>{active=false}},[sourceId,reload])
  const save=async()=>{
    if(!draft||!can('CONFIGURE_METADATA_SEARCH')||saving||!draft.displayLabel.trim())return
    setSaving(true);setError(null)
    try{
      const result=await updateBusinessMetadata(sourceId,{...draft,displayLabel:draft.displayLabel.trim()})
      setFields(rows=>rows.map(f=>f.fieldKey===result.fieldKey?result:f))
      setEditing(null);setDraft(null);toast({title:'Field updated'})
    }catch(e){setError(actionFailed('Saving a search field failed',e))}
    finally{setSaving(false)}
  }
  return <>
    {error&&<FailureNotice failure={error} loadTitle="Search fields didn't load" onRetry={()=>setReload(n=>n+1)} />}
    {ordering&&<Panel title="Business Search display order" description="Business Search uses this order for the selected source. Hidden fields retain their position when enabled again; incoming source contracts are unchanged.">
      <div className="stack" style={{gap:8}}>
        {orderDraft.map((key,i)=>{const f=fields.find(x=>x.fieldKey===key);return f&&<div key={key} className="row between" style={{gap:12,padding:8,border:'1px solid var(--line)',borderRadius:8}}>
          <span className="row" style={{gap:8}}><strong>{i+1}.</strong> {f.displayLabel}</span>
          <span className="row">
            <button type="button" className="v-btn sm" aria-label={'Move '+f.displayLabel+' up'} disabled={i===0||orderSaving} onClick={()=>setOrderDraft(old=>{const a=[...old];[a[i-1],a[i]]=[a[i],a[i-1]];return a})}>↑</button>
            <button type="button" className="v-btn sm" aria-label={'Move '+f.displayLabel+' down'} disabled={i===orderDraft.length-1||orderSaving} onClick={()=>setOrderDraft(old=>{const a=[...old];[a[i+1],a[i]]=[a[i],a[i+1]];return a})}>↓</button>
          </span>
        </div>})}
      </div>
      <div className="row" style={{justifyContent:'flex-end',marginTop:12}}>
        <button className="v-btn" type="button" disabled={orderSaving} onClick={()=>{setOrderDraft(orderSaved);setOrdering(false)}}>Cancel</button>
        <button className="v-btn primary" type="button" disabled={orderSaving||orderDraft.every((k,i)=>k===orderSaved[i])} onClick={async()=>{
          setOrderSaving(true);setError(null)
          try{
            const keys=await saveMetadataDisplayOrder(sourceId,orderDraft)
            // Read the persisted order back; never report success based only on the PUT response.
            const persisted=await getMetadataDisplayOrder(sourceId)
            if(keys.join('\u0000')!==persisted.join('\u0000')||orderDraft.join('\u0000')!==persisted.join('\u0000')) throw new Error('Saved display order did not match the persisted configuration.')
            const all=[...keys,...fields.map(f=>f.fieldKey).filter(k=>!keys.includes(k))]
            setOrderDraft(all);setOrderSaved(all)
            setFields(old=>[...old].sort((a,b)=>all.indexOf(a.fieldKey)-all.indexOf(b.fieldKey)))
            setOrdering(false);toast({title:'Display order saved'})
          }catch(e){setError(actionFailed('Saving the field order failed',e))}finally{setOrderSaving(false)}
        }}>Save order</button>
      </div>
    </Panel>}
    <Panel flush title="Searchable fields" meta={fields.length} description="Field keys come from Tenant IT's integration contract. Here you control labels, matching, business search visibility, and display order."
      actions={<span className="row" style={{gap:8}}>
        <select className="v-select" aria-label="Source" value={sourceId} onChange={e=>setSourceId(e.target.value)} style={{minWidth:180}}>
          {sources.map(s=><option key={s.sourceId} value={s.sourceId}>{s.displayName}</option>)}
        </select>
        <button type="button" className="v-btn sm" disabled={loading||fields.length<2||!can('CONFIGURE_METADATA_SEARCH')} onClick={()=>setOrdering(x=>!x)}>Reorder fields</button>
      </span>}>
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
    <Panel title="Business limits" description="Applied across business search and document collaboration. These limits are enforced by the backend."><dl className="v-kv cols">
      <div><dt>Maximum results per page</dt><dd>50</dd></div><div><dt>Results per page (current UI)</dt><dd>{BUSINESS_LIMITS.pageSize}</dd></div>
      <div><dt>Comment length</dt><dd>{BUSINESS_LIMITS.maxCommentLength.toLocaleString()} characters</dd></div><div><dt>Tag length</dt><dd>{BUSINESS_LIMITS.maxTagLength} characters</dd></div>
    </dl></Panel>
    {editing&&draft&&<Drawer eyebrow={sources.find(s=>s.sourceId===sourceId)?.displayName??''} title="Edit search behavior" onClose={()=>{if(!saving){setEditing(null);setDraft(null)}}}
      footer={<><button type="button" className="v-btn" disabled={saving} onClick={()=>{setEditing(null);setDraft(null)}}>Cancel</button><button type="button" className="v-btn primary" disabled={!draft.displayLabel.trim()||saving} onClick={()=>void save()}>{saving?'Saving…':'Save field'}</button></>}>
      <dl className="v-kv"><div><dt>Field key</dt><dd className="mono">{draft.fieldKey}</dd></div><div><dt>Type · normalization</dt><dd>{draft.fieldType} · {draft.normalization}</dd></div><div><dt>Identifier</dt><dd>{draft.businessIdentifier?'Yes — defined by the integration contract':'No'}</dd></div></dl>
      <label className="v-field"><span>Display label</span><input className="v-input" value={draft.displayLabel} onChange={e=>setDraft({...draft,displayLabel:e.target.value})}/></label>
      <div className="row between"><span className="v-label">Show in business search</span><Switch label="Searchable" checked={draft.searchable} disabled={draft.businessIdentifier} onChange={v=>setDraft({...draft,searchable:v})}/></div>
      {draft.businessIdentifier&&<small className="faint">Identifiers are always searchable.</small>}
      <label className="v-field"><span>Matching</span><select className="v-select" disabled={!draft.searchable} value={draft.searchMode} onChange={e=>setDraft({...draft,searchMode:e.target.value as AdminMetadataField['searchMode']})}>{Object.entries(MODE_LABEL).map(([mode,label])=><option key={mode} value={mode}>{label}</option>)}</select></label>
    </Drawer>}
  </>
}

const MODE_LABEL: Record<SearchMode, string> = { EXACT: 'Exact match', PREFIX: 'Starts with', PARTIAL: 'Contains' }
