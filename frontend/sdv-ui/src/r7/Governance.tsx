import { useEffect, useState, type ReactNode } from 'react'
import { KeepingDocuments } from './KeepingDocuments'
import { AREA_TABS, useStore } from './store'
import { type Capability } from './data'
import { ActionError, LoadErrorCard, toFailure, type Failure } from './CardError'
import { Badge, Callout, PageHead, Panel, Switch, Tabs } from './ui'
import { getRolePolicy, saveRolePolicy, type RolePolicy } from './liveApi'
import { SearchFields } from './SearchFields'

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
  retention: 'How long Vero keeps documents before deleting them for good.',
  metadata: 'Which fields people can search, how they match, and what a record is called.',
}

export function Governance() {
  const { route } = useStore()
  const tab = AREA_TABS.governance.find((t) => t.id === route.tab)!
  return (
    <AreaFrame crumbs={['Business administration', tab.label]} title={tab.label} lede={LEDE[route.tab]}>
      {route.tab === 'access' && <PeopleTab />}
      {route.tab === 'roles' && <RolesTab />}
      {route.tab === 'retention' && <KeepingDocuments />}
      {route.tab === 'metadata' && <SearchFields />}
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
