
import { useState } from 'react'
import { Lock, Pencil, Plus, Power, Search } from 'lucide-react'
import type { ItSource, ItField, ItSourceSummary } from './liveApi'
import { fmtDateTime } from './ui'
import './integrations.css'

type Section = 'configuration' | 'monitoring' | 'contract'
const authLabels: Record<ItSource['authType'],string> = { OAUTH2_CLIENT_CREDENTIALS:'OAuth 2.0 client credentials', JWT_BEARER:'Signed JWT bearer', MTLS:'Mutual TLS' }
const limits = [
 {key:'maxPdfBytes',label:'Maximum PDF size',unit:'bytes'},
 {key:'maxPagesPerPdf',label:'Maximum pages per PDF',unit:'pages'},
 {key:'maxDocumentsPerSubmission',label:'Documents per submission',unit:'documents'},
 {key:'maxAggregateSubmissionBytes',label:'Total submission size',unit:'bytes'},
 {key:'incompleteAttentionHours',label:'Incomplete-submission alert',unit:'hours'}
] as const
const showLimit=(value:number|null|undefined,unit:string)=>value==null?'Platform default':unit==='bytes'?(value/(1024*1024)).toLocaleString(undefined,{maximumFractionDigits:2})+' MB':value.toLocaleString()+' '+unit
function StatusPill({status}:{status:ItSource['status']}){return status==='ACTIVE'?<span className="ig-pill on"><span className="dot" aria-hidden/>Active</span>:<span className="ig-pill off"><Power size={11} aria-hidden/>Disabled</span>}
export function ConnectedIntegrationsView({sources,selected,fields,summary,section,onSelect,onSection,onCreate,onEdit,onToggleStatus,onAddField,onRevise}:{
 sources:ItSource[]; selected:ItSource|undefined;fields:ItField[];summary:ItSourceSummary|null;section:Section;
 onSelect:(id:string)=>void;onSection:(s:Section)=>void;onCreate:()=>void;onEdit:()=>void;onToggleStatus:()=>void;onAddField:()=>void;onRevise:(f:ItField)=>void
}){
 const [query,setQuery]=useState('')
 const filtered=sources.filter(i=>(i.displayName+' '+i.sourceKey).toLowerCase().includes(query.toLowerCase().trim()))
 const tabs:[Section,string][]=[['configuration','Configuration'],['monitoring','Monitoring'],['contract','Metadata contract']]
 return <div className="ig">
  <div className="ig-split" data-detail={selected?'true':'false'}>
   <nav className="ig-list" aria-label="Integrations">
    <div className="ig-list-head"><h2>Integrations <span className="faint">{sources.length}</span></h2><button type="button" className="v-btn sm primary" onClick={onCreate}><Plus size={15} aria-hidden/>Add integration</button></div>
    {sources.length>3&&<label className="ig-filter"><Search size={15} aria-hidden/><span className="sr-only">Filter integrations</span><input className="v-input" placeholder="Filter by name or key" value={query} onChange={e=>setQuery(e.target.value)}/></label>}
    {sources.length===0?<div className="ig-empty"><p><strong>No integrations yet</strong></p><p className="muted">Add an external producer and define its metadata contract.</p></div>:
    <ul className="ig-items">{filtered.map(i=><li key={i.sourceId}><button type="button" className="ig-item" aria-current={selected?.sourceId===i.sourceId?'true':undefined} onClick={()=>onSelect(i.sourceId)}>
     <span className="ig-item-top"><strong>{i.displayName}</strong><StatusPill status={i.status}/></span>
     <span className="mono ig-item-key">{i.sourceKey}</span>
     <span className="ig-item-meta">{authLabels[i.authType]}{selected?.sourceId===i.sourceId?' · '+fields.length+' fields':''}</span>
    </button></li>)}{filtered.length===0&&<li className="faint ig-nomatch">No matching integrations.</li>}</ul>}
   </nav>
   <section className="ig-detail" aria-label="Selected integration">
   {selected?<div className="ig-detail-inner">
    <header className="ig-head"><div className="ig-head-id"><h2>{selected.displayName}</h2><div className="ig-head-meta"><StatusPill status={selected.status}/><span className="mono">{selected.sourceKey}</span><span className="faint">Updated {selected.updatedAt?fmtDateTime(selected.updatedAt):'Unknown'}</span></div></div>
     <div className="ig-head-actions"><button className="v-btn sm" type="button" onClick={onEdit}><Pencil size={14} aria-hidden/>Edit configuration</button><button className={selected.status==='ACTIVE'?'v-btn sm danger-quiet':'v-btn sm'} type="button" onClick={onToggleStatus}><Power size={14} aria-hidden/>{selected.status==='ACTIVE'?'Disable':'Enable'}</button></div>
    </header>
    {selected.status==='DISABLED'&&<div className="ig-notice warn"><Power size={16} aria-hidden/><div>Disabled — the producer cannot send new submissions until re-enabled. Previously stored documents are unaffected.</div></div>}
    <div className="ig-tabs" role="tablist" aria-label="Integration sections">{tabs.map(([value,label])=><button key={value} type="button" className="ig-tab" role="tab" aria-selected={section===value} onClick={()=>onSection(value)}>{label}{value==='contract'&&<span className="ig-tab-count">{fields.length}</span>}</button>)}</div>
    <div className="ig-panel" role="tabpanel">
    {section==='configuration'&&<div className="ig-groups">
     <section className="ig-group"><h3>Identity</h3><dl className="v-kv cols">
      <div><dt>Display name</dt><dd>{selected.displayName}</dd></div>
      <div><dt>Source key</dt><dd><span className="mono">{selected.sourceKey}</span> <span className="ig-lock"><Lock size={11} aria-hidden/>Cannot be changed</span></dd></div>
      <div><dt>Created</dt><dd>{selected.createdAt?fmtDateTime(selected.createdAt):'Unknown'}</dd></div>
      <div><dt>Last updated</dt><dd>{selected.updatedAt?fmtDateTime(selected.updatedAt):'Unknown'}</dd></div>
     </dl></section>
     <section className="ig-group"><h3>Producer authentication</h3><dl className="v-kv cols"><div><dt>Method</dt><dd>{authLabels[selected.authType]}</dd></div><div><dt>Credential reference</dt><dd className="mono">{selected.credentialReference||'Not configured'}</dd></div></dl>
      <p className="ig-help">Only a credential reference is stored here, not a secret.</p></section>
     <section className="ig-group"><h3>Intake limits</h3><p className="ig-help">Unset values inherit platform settings. Effective defaults aren't exposed by the current API.</p>
      <table className="v-table ig-limits"><thead><tr><th>Limit</th><th>This integration</th><th>Effective</th></tr></thead><tbody>
      {limits.map(m=><tr key={m.key}><td className="lead">{m.label}</td><td>{showLimit(selected.limits?.[m.key],m.unit)}</td><td>{selected.limits?.[m.key]!=null?showLimit(selected.limits?.[m.key],m.unit)+' (configured; platform cap unknown)':'Not exposed by backend'}</td></tr>)}
      </tbody></table>
     </section></div>}
    {section==='monitoring'&&<div className="ig-groups">{summary?<><div className="ig-stats"><div className="ig-stat"><span>Submissions</span><strong>{summary.submissionCount.toLocaleString()}</strong></div><div className="ig-stat"><span>Documents</span><strong>{summary.documentCount.toLocaleString()}</strong></div></div>
      <dl className="v-kv"><div><dt>Last activity</dt><dd>{summary.lastActivityAt?fmtDateTime(summary.lastActivityAt):'None recorded'}</dd></div><div><dt>Last document available</dt><dd>{summary.lastDocumentAvailableAt?fmtDateTime(summary.lastDocumentAvailableAt):'None recorded'}</dd></div><div><dt>Last processing failure</dt><dd>{summary.lastProcessingFailureAt?fmtDateTime(summary.lastProcessingFailureAt):'No failures recorded'}</dd></div></dl>
      <p className="ig-help faint">Read-only monitoring. Processing incidents and retries are handled by Platform Operations.</p>
    </>:<p className="muted">Monitoring information is loading or unavailable.</p>}</div>}
    {section==='contract'&&<div className="ig-groups">
     <div className="ig-contract-head"><p className="ig-help">Fields the producer must send. Tenant IT owns types and normalization; Business Administration separately defines business search.</p><button type="button" className="v-btn sm primary" onClick={onAddField}><Plus size={15} aria-hidden/>Add field</button></div>
     {fields.length===0?<div className="ig-empty"><p><strong>No metadata fields configured</strong></p><p className="muted">Add fields to define the incoming metadata contract.</p></div>:
      <table className="v-table ig-fields"><thead><tr><th>Field</th><th>Type</th><th>Normalization</th><th>Validation</th><th>Business search</th><th className="num">Version</th><th className="act">Action</th></tr></thead><tbody>{fields.map(f=><tr key={f.fieldKey}>
       <td className="lead"><div className="ig-fcell"><span className="ig-flabel">{f.displayLabel}{f.required&&<span className="ig-req">Required</span>}</span><span className="mono faint">{f.fieldKey}</span></div></td>
       <td>{f.fieldType}</td><td>{f.normalization==='NONE'?'None':f.normalization}</td><td className="faint">Not exposed by API</td><td className="faint">Configured in Business Administration</td><td className="num">v{f.version}</td>
       <td className="act"><button type="button" className="v-btn sm" onClick={()=>onRevise(f)}><Pencil size={14} aria-hidden/>Revise</button></td></tr>)}</tbody></table>}
     <p className="ig-help faint">Field revisions affect future ingestions; historical values aren't automatically revalidated.</p>
    </div>}
    </div>
   </div>:<div className="ig-empty"><p className="muted">Select or add an integration to review its configuration.</p></div>}
   </section>
  </div>
 </div>
}
