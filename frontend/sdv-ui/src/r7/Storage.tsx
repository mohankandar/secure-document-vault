import { useEffect, useState } from 'react'
import { CheckCircle2, Plus, XCircle } from 'lucide-react'
import { FailureNotice, actionFailed, loadFailed, type Failure } from './CardError'
import { Callout, Drawer, TechDetails } from './ui'
import { checkStorageFolder, createStorageVolume, liveStorage, updateStorageVolume, type FolderCheck, type LiveStorageVolume } from './liveApi'

// Task 0010 Part B (finding 15): volumes by role in words, how full they are, and a three-step add with a write check.
// Vero puts new documents on the usable ACTIVE volume with the lowest priority number ("in use"); the other usable
// ACTIVE volumes are standby, in priority order.

export function fmtBytes(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return '—'
  const units = ['bytes', 'KB', 'MB', 'GB', 'TB', 'PB']
  let v = n, i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toLocaleString(undefined, { maximumFractionDigits: v < 10 && i > 0 ? 1 : 0 })} ${units[i]}`
}
const usable = (v: LiveStorageVolume) => v.volume.state === 'ACTIVE' && !!v.capacity?.writable && !v.stopWriteThresholdReached && !v.observationError
const byPriority = (a: LiveStorageVolume, b: LiveStorageVolume) => a.volume.priority - b.volume.priority || (a.capacity?.usedPercent ?? 100) - (b.capacity?.usedPercent ?? 100)

/** A state Tenant IT set on purpose, in the words both Platform operations and Tenant IT see (task 0020, item 4). */
export function onPurposeWord(state: string): string | null {
  if (state === 'OFFLINE') return 'Offline (set by Tenant IT)'
  if (state === 'READ_ONLY') return 'Read only (set by Tenant IT)'
  if (state === 'DRAINING') return 'Being emptied (set by Tenant IT)'
  return null
}

export function storageRoles(items: LiveStorageVolume[]) {
  const ready = items.filter(usable).sort(byPriority)
  const inUse = ready[0]
  const role = (v: LiveStorageVolume): string => {
    if (v.volume.state === 'READ_ONLY') return 'Read only, keeps its documents but takes no new ones'
    if (v.volume.state === 'OFFLINE') return 'Offline'
    if (v.volume.state === 'DRAINING') return 'Being emptied, takes no new documents'
    if (v === inUse) return 'In use, new documents go here'
    if (usable(v)) return `Standby, used when ${inUse?.volume.displayName ?? 'the volume in use'} fills up`
    return 'Full or unreachable, takes no new documents'
  }
  /** The volume Vero switches to when v stops accepting documents. */
  const next = (v: LiveStorageVolume) => ready.find((x) => x !== v)
  return { inUse, role, next, ready }
}

function banners(items: LiveStorageVolume[]): string[] {
  const { next } = storageRoles(items)
  return items.filter((v) => v.volume.state === 'ACTIVE').flatMap((v) => {
    const name = v.volume.displayName, pct = v.capacity?.usedPercent, stop = v.volume.stopWritePercent, then = next(v)?.volume.displayName
    if (v.observationError) return [`Vero can't read ${name} right now, so it takes no new documents.${then ? ` Vero uses ${then} instead.` : ''}`]
    if (v.stopWriteThresholdReached) return [`${name} is ${pct ?? 100}% full and takes no new documents.${then ? ` Vero now uses ${then}.` : ' Vero can’t store new documents until you add space or a new volume.'}`]
    if (v.warningThresholdReached) return [`${name} is ${pct}% full. ${then ? `At ${stop}% it stops accepting documents and Vero switches to ${then}.` : `At ${stop}% Vero stops accepting new documents.`} Add space or a new volume before then.`]
    return []
  })
}

export function Storage() {
  const [items, setItems] = useState<LiveStorageVolume[] | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [adding, setAdding] = useState(false)
  const [editing, setEditing] = useState<LiveStorageVolume | null>(null)
  useEffect(() => {
    let live = true
    liveStorage().then((x) => { if (live) { setItems(x); setError(null) } }).catch((e) => { if (live) setError(loadFailed('Storage did not load', e)) })
    return () => { live = false }
  }, [reload])
  const done = () => { setAdding(false); setEditing(null); setReload((x) => x + 1) }
  const roles = storageRoles(items ?? [])

  // Canvas: a banner per volume in trouble (first sentence bold), the volumes as a table in one card, a note on adding.
  const boldFirst = (b: string) => { const m = b.match(/^(.*?[.!?])\s+(.*)$/); return m ? <><b>{m[1]}</b> {m[2]}</> : <b>{b}</b> }
  return <>
    {error && <FailureNotice failure={error} loadTitle="Storage didn't load" onRetry={() => setReload((x) => x + 1)} />}
    {items && banners(items).map((b) => <div key={b} className="v-banner warn st-banner" role="alert">{boldFirst(b)}</div>)}
    <section className="v-panel" aria-labelledby="st-h">
      <header className="v-panel-head st-head"><h2 id="st-h">Storage volumes{items ? ` · ${items.length}` : ''}</h2>
        <button type="button" className="v-btn sm primary" onClick={() => setAdding(true)}><Plus size={14} aria-hidden />Add volume</button></header>
      {!items ? <p className="muted" style={{ padding: 16 }}>Loading…</p> : items.length === 0 ? <p className="muted" style={{ padding: 16 }}>No volumes yet. Add one so Vero can store documents.</p> :
      <table className="v-table st-table" aria-label="Storage volumes">
        <thead><tr><th>Volume</th><th>Role</th><th>How full</th><th className="act"><span className="sr-only">Change</span></th></tr></thead>
        <tbody>{items.map((v) => {
          const pct = v.capacity?.usedPercent
          const role = roles.role(v)
          // A volume Tenant IT set offline on purpose is grey, not red.
          const tone = v.volume.state !== 'ACTIVE' ? '' : v.observationError || v.stopWriteThresholdReached ? 'r' : v.warningThresholdReached ? 'y' : role.startsWith('In use') ? 'g' : ''
          return <tr key={v.volume.volumeId} aria-label={v.volume.displayName}>
            <td className="lead" data-label="Volume"><strong>{v.volume.displayName}</strong><div className="mono faint st-folder">{v.volume.rootPath}</div></td>
            <td data-label="Role"><span className={`v-dot ${tone}`} aria-hidden /><span className="st-role">{role}</span></td>
            <td data-label="How full">{pct != null ? <>
              <div className="row" style={{ gap: 10 }}><span className="v-bar2" role="img" aria-label={`${v.volume.displayName}: ${pct}% full`}><i className={v.stopWriteThresholdReached ? 'r' : v.warningThresholdReached ? 'y' : ''} style={{ width: `${Math.min(pct, 100)}%` }} /></span><span>{pct}%</span></div>
              <div className="faint st-numbers">Warns at {v.volume.warningPercent}% · stops at {v.volume.stopWritePercent}%</div>
            </> : <span className="faint">{onPurposeWord(v.volume.state) ?? 'How full it is can’t be read right now.'}</span>}</td>
            <td className="act"><button type="button" className="v-btn sm" onClick={() => setEditing(v)} aria-label={`Edit ${v.volume.displayName}`}>Edit</button></td>
          </tr>
        })}</tbody>
      </table>}
    </section>
    <p className="faint st-note">Adding a volume: name and folder, then Vero checks it can write there, then you choose In use or Standby. The folder cannot change later.</p>
    {items && items.length > 0 && <TechDetails hint="size, free space, order and state of each volume">
      <dl className="v-kv">{items.map((v) => <div key={v.volume.volumeId}><dt>{v.volume.displayName}</dt><dd>{fmtBytes(v.capacity?.totalBytes)} · {v.capacity?.usableBytes != null ? `${fmtBytes(v.capacity.usableBytes)} free` : 'free space not known'} · order {v.volume.priority} · <span className="mono">{v.volume.state}</span></dd></div>)}</dl>
    </TechDetails>}
    {adding && items && <AddVolume items={items} onClose={() => setAdding(false)} onSaved={done} />}
    {editing && <EditVolume volume={editing} onClose={() => setEditing(null)} onSaved={done} />}
  </>
}

const ADD_STEPS = ['Name and folder', 'Vero checks it can write there', 'Its role'] as const

function AddVolume({ items, onClose, onSaved }: { items: LiveStorageVolume[]; onClose: () => void; onSaved: () => void }) {
  const [step, setStep] = useState(0)
  const [name, setName] = useState('')
  const [folder, setFolder] = useState('')
  const [checking, setChecking] = useState(false)
  const [result, setResult] = useState<FolderCheck | null>(null)
  const [role, setRole] = useState<'inUse' | 'standby'>('standby')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<Failure | null>(null)
  const { inUse } = storageRoles(items)
  const activePriorities = items.filter((v) => v.volume.state === 'ACTIVE').map((v) => v.volume.priority)
  // In use needs a lower number than every ACTIVE volume; with one already at 0 that is not possible here.
  const canBeInUse = activePriorities.length === 0 || Math.min(...activePriorities) > 0
  const priority = role === 'inUse' ? (activePriorities.length ? Math.min(...activePriorities) - 1 : 0) : (activePriorities.length ? Math.max(...activePriorities) + 1 : 1)
  useEffect(() => { if (!inUse && canBeInUse) setRole('inUse') }, [inUse, canBeInUse])

  const runCheck = async () => {
    setChecking(true); setFailure(null); setResult(null)
    try { setResult(await checkStorageFolder(folder.trim())) } catch (e) { setFailure(actionFailed('Checking a storage folder failed', e)) }
    finally { setChecking(false) }
  }
  useEffect(() => { if (step === 1 && !result && !checking) void runCheck() }, [step]) // eslint-disable-line react-hooks/exhaustive-deps

  const save = async () => {
    if (busy || !result?.writable) return
    setBusy(true); setFailure(null)
    try { await createStorageVolume({ displayName: name.trim(), rootPath: folder.trim(), state: 'ACTIVE', priority }); onSaved() }
    catch (e) { setFailure(actionFailed('Adding a storage volume failed', e)) }
    finally { setBusy(false) }
  }
  const stepValid = [name.trim().length > 0 && folder.trim().length > 0, !!result?.writable, role === 'standby' || canBeInUse][step]
  const last = step === ADD_STEPS.length - 1
  return <Drawer eyebrow={`Step ${step + 1} of ${ADD_STEPS.length} · ${ADD_STEPS[step]}`} title="Add a volume" onClose={() => { if (!busy) onClose() }} footer={<>
    {step > 0 ? <button type="button" className="v-btn" disabled={busy} onClick={() => setStep((s) => s - 1)}>Back</button> : <button type="button" className="v-btn" onClick={onClose}>Cancel</button>}
    {!last ? <button type="button" className="v-btn primary" disabled={!stepValid || checking} onClick={() => setStep((s) => s + 1)}>Next</button>
      : <button type="button" className="v-btn primary" disabled={!stepValid || busy} onClick={() => void save()}>{busy ? 'Adding…' : 'Add volume'}</button>}
  </>}>
    <ol className="int-steps" aria-label="Steps">{ADD_STEPS.map((s, i) => <li key={s} aria-current={i === step ? 'step' : undefined} className={i < step ? 'done' : undefined}>{s}</li>)}</ol>
    {failure && <FailureNotice failure={failure} loadTitle="" actionTitle={step === 1 ? "The folder wasn't checked" : "The volume wasn't added"} onRetry={() => undefined} />}
    {step === 0 && <>
      <label className="v-field"><span>Name</span><input className="v-input" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} placeholder="For example: primary-b" /></label>
      <label className="v-field"><span>Folder</span><input className="v-input mono" value={folder} onChange={(e) => { setFolder(e.target.value); setResult(null) }} placeholder="D:\vero\primary-b" />
        <small className="faint">The folder cannot change later.</small></label>
    </>}
    {step === 1 && <>
      <p className="muted">Vero writes a small test file in <span className="mono">{folder.trim()}</span> and removes it again.</p>
      {checking ? <Callout title="Checking…">Vero is trying to write in this folder.</Callout>
        : result?.writable ? <div className="v-callout ok" role="status"><CheckCircle2 size={18} aria-hidden /><div><strong>Vero can write here</strong><p>{fmtBytes(result.usableBytes)} free{result.totalBytes ? ` of ${fmtBytes(result.totalBytes)}` : ''}.</p></div></div>
        : result ? <div className="v-callout bad" role="alert"><XCircle size={18} aria-hidden /><div><strong>Vero can’t use this folder</strong><p>{result.problem ?? 'Vero cannot write in this folder.'} Choose another folder, or ask for write access, then check again.</p></div></div>
        : null}
      <button type="button" className="v-btn" disabled={checking} onClick={() => void runCheck()}>Check again</button>
    </>}
    {step === 2 && <fieldset className="int-auth"><legend>Its role</legend>
      <label className={`int-auth-card${canBeInUse ? '' : ' disabled'}`}><input type="radio" name="st-role" disabled={!canBeInUse} checked={role === 'inUse'} onChange={() => setRole('inUse')} />
        <span><strong>In use</strong><small className="muted">{inUse ? `New documents go here from now on. ${inUse.volume.displayName} becomes standby.` : 'New documents go here.'}</small>
          {!canBeInUse && <small className="faint">Another volume already comes first. Add this one as standby, then change the priorities under Technical details.</small>}</span></label>
      <label className="int-auth-card"><input type="radio" name="st-role" checked={role === 'standby'} onChange={() => setRole('standby')} />
        <span><strong>Standby</strong><small className="muted">{inUse ? `Used when ${inUse.volume.displayName} fills up.` : 'Used when the volume in use fills up.'}</small></span></label>
      <small className="faint">Warning and stop levels start at your standard levels; change them later with Edit.</small>
    </fieldset>}
  </Drawer>
}

function EditVolume({ volume, onClose, onSaved }: { volume: LiveStorageVolume; onClose: () => void; onSaved: () => void }) {
  const v = volume.volume
  const [name, setName] = useState(v.displayName)
  const [state, setState] = useState(v.state)
  const [priority, setPriority] = useState(String(v.priority))
  const [warning, setWarning] = useState(String(v.warningPercent))
  const [stop, setStop] = useState(String(v.stopWritePercent))
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<Failure | null>(null)
  const n = (s: string) => Number(s)
  const valid = name.trim().length > 0 && Number.isInteger(n(priority)) && n(priority) >= 0 && Number.isInteger(n(warning)) && Number.isInteger(n(stop))
    && n(warning) >= 1 && n(warning) <= 99 && n(stop) <= 100 && n(stop) > n(warning)
  const save = async () => {
    if (!valid || busy) return
    setBusy(true); setFailure(null)
    try { await updateStorageVolume(v.volumeId, { displayName: name.trim(), state, priority: n(priority), warningPercent: n(warning), stopWritePercent: n(stop) }); onSaved() }
    catch (e) { setFailure(actionFailed('Saving a storage volume failed', e)) }
    finally { setBusy(false) }
  }
  return <Drawer eyebrow="Storage" title={`Edit ${v.displayName}`} onClose={() => { if (!busy) onClose() }} footer={<>
    <button type="button" className="v-btn" disabled={busy} onClick={onClose}>Cancel</button>
    <button type="button" className="v-btn primary" disabled={!valid || busy} onClick={() => void save()}>{busy ? 'Saving…' : 'Save'}</button></>}>
    {failure && <FailureNotice failure={failure} loadTitle="" actionTitle="The volume wasn't saved" onRetry={() => undefined} />}
    <label className="v-field"><span>Name</span><input className="v-input" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} /></label>
    <label className="v-field"><span>Takes new documents</span><select className="v-select" value={state} onChange={(e) => setState(e.target.value)}>
      <option value="ACTIVE">Yes (in use or standby, by priority)</option><option value="READ_ONLY">No, read only</option><option value="OFFLINE">No, offline</option>
      {v.state === 'DRAINING' && <option value="DRAINING">No, being emptied</option>}</select></label>
    <div className="row" style={{ gap: 12 }}>
      <label className="v-field grow"><span>Warns at (%)</span><input className="v-input" inputMode="numeric" value={warning} onChange={(e) => setWarning(e.target.value)} /></label>
      <label className="v-field grow"><span>Stops at (%)</span><input className="v-input" inputMode="numeric" value={stop} onChange={(e) => setStop(e.target.value)} /></label>
    </div>
    <Callout tone="warn" title="This can stop new documents">If no volume takes new documents, deliveries are refused until one does.</Callout>
    <TechDetails flat hint="folder, order">
      <label className="v-field"><span>Folder</span><input className="v-input mono" readOnly value={v.rootPath} /><small className="faint">The folder cannot change.</small></label>
      <label className="v-field"><span>Priority (lower comes first)</span><input className="v-input" inputMode="numeric" value={priority} onChange={(e) => setPriority(e.target.value)} /></label>
    </TechDetails>
  </Drawer>
}
