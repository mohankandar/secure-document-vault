import { useState, type ReactNode } from 'react'
import { Lock, Pencil, Plus } from 'lucide-react'
import { AREA_TABS, useStore } from './store'
import { BASELINE, BUSINESS_LIMITS, CAPABILITY_LABELS, CONFIGURABLE, ROLES, SOURCES, type Capability, type Field, type SearchMode } from './data'
import { Badge, Callout, Drawer, PageHead, Panel, Scenario, Status, Switch, Tabs, fmtDate, fmtRel } from './ui'

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
      {route.tab === 'metadata' && <MetadataTab />}
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
  const { roleCaps, setRoleCaps, toast, recordAudit } = useStore()
  const [draft, setDraft] = useState(roleCaps)
  const dirty = JSON.stringify(draft) !== JSON.stringify(roleCaps)
  const cols = [{ code: 'BUSINESS_USER' as const, name: 'Business User' }, { code: 'DOCUMENT_STEWARD' as const, name: 'Document Steward' }]
  const toggle = (code: 'BUSINESS_USER' | 'DOCUMENT_STEWARD', c: Capability, on: boolean) => setDraft((d) => ({ ...d, [code]: on ? [...d[code], c] : d[code].filter((x) => x !== c) }))
  const caps: Capability[] = [...BASELINE, ...CONFIGURABLE]
  return (
    <>
      <Panel flush title="Document actions by role" description="Changes apply on each user's next request. Every change is audited.">
        <Scenario what="role permissions">
          <table className="v-table">
            <thead><tr><th>Action</th>{cols.map((c) => <th key={c.code}>{c.name}</th>)}</tr></thead>
            <tbody>
              {caps.map((c) => {
                const base = BASELINE.includes(c)
                return (
                  <tr key={c}>
                    <td className="lead" data-label="Action">
                      <div className="stack" style={{ gap: 0 }}><span style={{ fontWeight: 500 }}>{CAPABILITY_LABELS[c]}</span><span className="mono faint" style={{ fontSize: 12 }}>{c}</span></div>
                      {c === 'PERMANENT_REDACTION' && <Badge tone="bad">Irreversible</Badge>}
                    </td>
                    {cols.map((col) => (
                      <td key={col.code} data-label={col.name}>
                        {base ? <span className="row faint" style={{ fontSize: 13 }}><Lock size={13} aria-hidden />Baseline</span>
                          : <Switch label={`${CAPABILITY_LABELS[c]} for ${col.name}`} checked={draft[col.code].includes(c)} onChange={(on) => toggle(col.code, c, on)} />}
                      </td>
                    ))}
                  </tr>
                )
              })}
            </tbody>
          </table>
        </Scenario>
      </Panel>
      {dirty && (
        <div className="v-panel" style={{ position: 'sticky', bottom: 'calc(var(--bottom) + 12px)', zIndex: 5 }}>
          <div className="v-panel-body row between wrap">
            <span><strong>Unsaved changes.</strong> <span className="muted">Saving updates this prototype session only.</span></span>
            <span className="row">
              <button type="button" className="v-btn" onClick={() => setDraft(roleCaps)}>Discard</button>
              <button type="button" className="v-btn primary" onClick={() => {
                setRoleCaps(draft)
                recordAudit({ action: 'ROLE_CAPABILITY_UPDATED', targetType: 'ROLE', target: 'BUSINESS_ROLES', outcome: 'SUCCESS', details: { simulated: 'true' } })
                toast({ title: 'Permissions saved (simulated)', detail: 'Switch to a business role to see the effect in the viewer.' })
              }}>Save changes</button>
            </span>
          </div>
        </div>
      )}
    </>
  )
}

function RetentionTab() {
  const { retention, setRetention, toast, recordAudit, role } = useStore()
  const [open, setOpen] = useState(false)
  const [days, setDays] = useState(String(retention.active.days))
  const [note, setNote] = useState('')
  const [ack, setAck] = useState(false)
  const n = Number(days)
  const valid = Number.isInteger(n) && n >= 1 && n <= 36500
  const a = retention.active
  return (
    <>
      <Scenario what="retention policy" empty={<Panel title="No retention policy"><Callout tone="warn" title="Documents can't be ingested until a policy is active">Activate the first policy version to begin.</Callout></Panel>}>
        <section className="v-panel">
          <div className="v-panel-body" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div className="row between wrap" style={{ gap: 12 }}>
              <div className="stack" style={{ gap: 4 }}>
                <span className="row"><Status value="ACTIVE" label={`Version ${a.version} · active`} /></span>
                <span style={{ fontSize: 32, fontWeight: 600, letterSpacing: '-0.02em', lineHeight: 1.1 }}>{a.days.toLocaleString()} days</span>
                <span className="muted">≈ {(a.days / 365).toFixed(1)} years after ingestion · effective {fmtDate(a.effectiveAt)} · by {a.activatedBy}</span>
              </div>
              <button type="button" className="v-btn primary" onClick={() => { setOpen(true); setAck(false); setNote(''); setDays(String(a.days)) }}><Plus size={16} aria-hidden />New version</button>
            </div>
            <p className="muted" style={{ fontSize: 14 }}>{a.note}</p>
          </div>
        </section>
      </Scenario>
      <Panel flush title="Version history" meta={retention.history.length} description="Documents keep the retention they were ingested with. Older documents are never rewritten.">
        <Scenario what="policy history">
          <table className="v-table">
            <thead><tr><th>Version</th><th className="num">Retention</th><th>Effective</th><th>Activated by</th><th>Status</th></tr></thead>
            <tbody>{retention.history.map((h) => (
              <tr key={h.version}><td className="lead" data-label="Version"><strong style={{ fontWeight: 500 }}>v{h.version}</strong></td><td className="num" data-label="Retention">{h.days.toLocaleString()} days</td><td data-label="Effective">{fmtDate(h.effectiveAt)}</td><td data-label="Activated by">{h.activatedBy}</td><td data-label="Status"><Status value={h.status === 'ACTIVE' ? 'ACTIVE' : 'RETIRED'} label={h.status === 'ACTIVE' ? 'Active' : 'Superseded'} /></td></tr>
            ))}</tbody>
          </table>
        </Scenario>
      </Panel>
      {open && (
        <Drawer eyebrow="Retention policy" title={`Activate version ${a.version + 1}`} onClose={() => setOpen(false)}
          footer={<><button type="button" className="v-btn" onClick={() => setOpen(false)}>Cancel</button><button type="button" className="v-btn primary" disabled={!valid || !ack} onClick={() => {
            const v = a.version + 1
            setRetention({ active: { version: v, days: n, effectiveAt: new Date().toISOString(), activatedBy: role.person, note: note || 'No note provided.' }, history: [{ version: v, days: n, effectiveAt: new Date().toISOString(), activatedBy: role.person, status: 'ACTIVE' }, ...retention.history.map((h) => ({ ...h, status: 'SUPERSEDED' }))] })
            recordAudit({ action: 'RETENTION_POLICY_ACTIVATED', targetType: 'RETENTION_POLICY', target: `v${v}`, outcome: 'SUCCESS', details: { days: n, simulated: 'true' } })
            toast({ title: `Version ${v} activated (simulated)`, detail: 'Applies to documents ingested from now on.' }); setOpen(false)
          }}>Activate version</button></>}>
          <label className="v-field"><span>Retention period (days)</span><input className={`v-input${valid ? '' : ' invalid'}`} inputMode="numeric" value={days} onChange={(e) => setDays(e.target.value.replace(/\D/g, ''))} /><small>{valid ? `≈ ${(n / 365).toFixed(1)} years` : 'Enter a whole number between 1 and 36,500.'}</small></label>
          <label className="v-field"><span>Change note</span><textarea className="v-textarea" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Reference the records schedule or approval." /></label>
          <Callout tone="warn" title="Applies only to newly ingested documents">Existing documents keep their current retain-until date. This version becomes immutable once active.</Callout>
          <label className="v-check"><input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} /><span>I confirm this retention change has been approved.</span></label>
        </Drawer>
      )}
    </>
  )
}

const MODE_LABEL: Record<SearchMode, string> = { EXACT: 'Exact match', PREFIX: 'Starts with', PARTIAL: 'Contains' }

function MetadataTab() {
  const { sources, setSources, toast, recordAudit } = useStore()
  const [sourceId, setSourceId] = useState(SOURCES[0].id)
  const [editing, setEditing] = useState<Field | null>(null)
  const src = sources.find((s) => s.id === sourceId)!
  return (
    <>
      <Panel flush title="Searchable fields" meta={src.fields.length} description="Field keys come from Tenant IT's integration contract. Here you control labels and matching."
        actions={<select className="v-select" aria-label="Source" value={sourceId} onChange={(e) => setSourceId(e.target.value)} style={{ minWidth: 180 }}>{sources.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</select>}>
        <Scenario what="source fields" empty={<Callout title="No source fields defined">Tenant IT must define the integration metadata contract first.</Callout>}>
          <table className="v-table">
            <thead><tr><th>Label</th><th>Field key</th><th>Role</th><th>Search</th><th>Matching</th><th className="act"><span className="sr-only">Edit</span></th></tr></thead>
            <tbody>{src.fields.map((f) => (
              <tr key={f.key}>
                <td className="lead" data-label="Label"><strong style={{ fontWeight: 500 }}>{f.label}</strong></td>
                <td data-label="Field key"><span className="mono">{f.key}</span></td>
                <td data-label="Role">{f.identifier ? <Badge tone="ok">Identifier</Badge> : <span className="muted">Attribute</span>}</td>
                <td data-label="Search">{f.searchable ? 'Searchable' : <span className="faint">Hidden</span>}</td>
                <td data-label="Matching">{f.searchable ? MODE_LABEL[f.mode] : '—'}</td>
                <td className="act"><button type="button" className="v-btn sm" onClick={() => setEditing(f)}><Pencil size={14} aria-hidden />Edit</button></td>
              </tr>
            ))}</tbody>
          </table>
        </Scenario>
      </Panel>
      <Panel title="Business limits" description="Applied across business search and document collaboration.">
        <dl className="v-kv cols">
          <div><dt>Maximum search results</dt><dd>{BUSINESS_LIMITS.maxResults}</dd></div>
          <div><dt>Results per page</dt><dd>{BUSINESS_LIMITS.pageSize}</dd></div>
          <div><dt>Comment length</dt><dd>{BUSINESS_LIMITS.maxCommentLength.toLocaleString()} characters</dd></div>
          <div><dt>Tag length</dt><dd>{BUSINESS_LIMITS.maxTagLength} characters</dd></div>
        </dl>
      </Panel>
      {editing && <FieldDrawer field={editing} sourceName={src.name} onClose={() => setEditing(null)} onSave={(f) => {
        setSources((list) => list.map((s) => (s.id === sourceId ? { ...s, fields: s.fields.map((x) => (x.key === f.key ? f : x)) } : s)))
        recordAudit({ action: 'METADATA_FIELD_UPDATED', targetType: 'METADATA_FIELD', target: f.key, outcome: 'SUCCESS', details: { simulated: 'true' } })
        toast({ title: 'Field updated (simulated)', detail: `${f.label} · ${f.searchable ? MODE_LABEL[f.mode] : 'not searchable'}` }); setEditing(null)
      }} />}
    </>
  )
}

function FieldDrawer({ field, sourceName, onClose, onSave }: { field: Field; sourceName: string; onClose: () => void; onSave: (f: Field) => void }) {
  const [f, setF] = useState(field)
  return (
    <Drawer eyebrow={sourceName} title="Edit search behavior" onClose={onClose} footer={<><button type="button" className="v-btn" onClick={onClose}>Cancel</button><button type="button" className="v-btn primary" disabled={!f.label.trim()} onClick={() => onSave({ ...f, label: f.label.trim() })}>Save field</button></>}>
      <dl className="v-kv"><div><dt>Field key</dt><dd className="mono">{f.key}</dd></div><div><dt>Type · normalization</dt><dd>{f.type} · {f.normalization}</dd></div><div><dt>Identifier</dt><dd>{f.identifier ? 'Yes — defined by the integration contract' : 'No'}</dd></div></dl>
      <label className="v-field"><span>Display label</span><input className="v-input" value={f.label} onChange={(e) => setF({ ...f, label: e.target.value })} /></label>
      <div className="row between"><span className="v-label">Show in business search</span><Switch label="Searchable" checked={f.searchable} onChange={(v) => setF({ ...f, searchable: v })} disabled={f.identifier} /></div>
      {f.identifier && <small className="faint">Identifiers are always searchable.</small>}
      <label className="v-field"><span>Matching</span>
        <select className="v-select" value={f.mode} disabled={!f.searchable} onChange={(e) => setF({ ...f, mode: e.target.value as SearchMode })}>
          <option value="EXACT">Exact match</option><option value="PREFIX">Starts with</option><option value="PARTIAL">Contains</option>
        </select>
        <small>Contains matching is slower on large sources. Prefer exact for identifiers.</small>
      </label>
      <p className="faint" style={{ fontSize: 13 }}>Last contract change {fmtRel(new Date(Date.now() - 4 * 86400000).toISOString())}.</p>
    </Drawer>
  )
}
