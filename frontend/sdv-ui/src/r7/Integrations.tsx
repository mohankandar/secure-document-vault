import { useEffect, useMemo, useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { FailureNotice, actionFailed, loadFailed, type Failure } from './CardError'
import { Callout, Dialog, Drawer, TechDetails, fmtDate, fmtRel } from './ui'
import {
  identityOverview, itCreateSource, itFields, itSourceSummary, itSources, itStandardLimits, itUpdateSource, itUpsertField,
  type ItField, type ItSource, type ItSourceSummary,
} from './liveApi'

// Task 0010 Part A and A2 (finding 14): sending systems in words, and a three-step editor.

type Kind = { type: string; normalization: string; label: string }
/** Kinds of value IT can choose; each is a field type plus how Vero tidies the value. */
export const KINDS: Kind[] = [
  { type: 'TEXT', normalization: 'NONE', label: 'Text' },
  { type: 'TEXT', normalization: 'TRIM', label: 'Text, spaces trimmed' },
  { type: 'TEXT', normalization: 'DIGITS_ONLY', label: 'Text, digits only' },
  { type: 'TEXT', normalization: 'UPPERCASE', label: 'Text, capital letters' },
  { type: 'TEXT', normalization: 'LOWERCASE', label: 'Text, small letters' },
  { type: 'DATE', normalization: 'NONE', label: 'Date' },
  { type: 'DATETIME', normalization: 'NONE', label: 'Date and time' },
  { type: 'INTEGER', normalization: 'NONE', label: 'Whole number' },
  { type: 'DECIMAL', normalization: 'NONE', label: 'Number' },
  { type: 'BOOLEAN', normalization: 'NONE', label: 'Yes or no' },
  { type: 'EMAIL', normalization: 'NONE', label: 'Email address' },
  { type: 'UUID', normalization: 'NONE', label: 'ID code (UUID)' },
]
const NORM_WORDS: Record<string, string> = { NONE: '', TRIM: 'spaces trimmed', DIGITS_ONLY: 'digits only', UPPERCASE: 'capital letters', LOWERCASE: 'small letters' }
const kindKey = (type: string, normalization: string) => `${type}:${normalization}`
export function kindWords(type: string, normalization: string): string {
  const known = KINDS.find((k) => k.type === type && k.normalization === normalization)
  if (known) return known.label
  const base = KINDS.find((k) => k.type === type && k.normalization === 'NONE')?.label ?? type
  return NORM_WORDS[normalization] ? `${base}, ${NORM_WORDS[normalization]}` : base
}
const MATCH = { EXACT: 'exact match', PREFIX: 'starts with', PARTIAL: 'contains' } as const
function searchWords(f: ItField): string {
  if (f.businessIdentifier) return 'Yes · exact match'
  return f.searchable && f.searchMode ? `Yes · ${MATCH[f.searchMode]}` : 'No'
}

export function statusLine(s: ItSource, summary?: ItSourceSummary | null): { text: string; tone: 'ok' | 'warn' | '' } {
  if (s.status === 'DISABLED') return { text: `Paused since ${fmtDate(s.updatedAt)}`, tone: 'warn' }
  if (!summary) return { text: 'Working', tone: 'ok' }
  if (summary.lastActivityAt) return { text: `Working · last delivery ${fmtRel(summary.lastActivityAt)}`, tone: 'ok' }
  return { text: 'Waiting for the first delivery', tone: '' }
}

const AUTH: Record<ItSource['authType'], { title: string; sentence: (idp?: string) => string }> = {
  OAUTH2_CLIENT_CREDENTIALS: { title: 'Client ID and secret', sentence: (idp) => `A client ID and secret kept in your sign-in system${idp ? ` (${idp})` : ''}.` },
  JWT_BEARER: { title: 'Signed token', sentence: () => 'A signed token issued by your sign-in system.' },
  MTLS: { title: 'Certificate', sentence: () => 'A certificate the system presents each time it connects.' },
}
const NEVER_SECRET = 'Vero stores only where the secret is kept, never the secret.'

type Limits = NonNullable<ItSource['limits']>
const MB = 1024 * 1024
const LIMIT_ROWS: { key: keyof Limits; label: string; words: (n: number) => string }[] = [
  { key: 'maxPdfBytes', label: 'Largest PDF', words: (n) => mb(n) },
  { key: 'maxPagesPerPdf', label: 'Most pages in one PDF', words: (n) => `${n.toLocaleString()} pages` },
  { key: 'maxDocumentsPerSubmission', label: 'Most documents', words: (n) => `${n.toLocaleString()} documents` },
  { key: 'incompleteAttentionHours', label: 'Wait for missing documents', words: (n) => `${n.toLocaleString()} hours` },
]
const STANDARD = "Vero's standard limit"
const mb = (n: number) => `${(n / MB).toLocaleString(undefined, { maximumFractionDigits: 1 })} MB`
/**
 * The limit that applies (task 0019 follow-up): the system's own, never above the standard, because intake always
 * uses the smaller of the two. With no standard values (they did not load), the system's own value or the words.
 */
export function limitInUse(key: keyof Limits, own: Limits | undefined, standard: Limits | null): number | null {
  const mine = own?.[key] ?? null
  const std = standard?.[key] ?? null
  if (mine != null && std != null) return Math.min(mine, std)
  return mine ?? std
}

export function Integrations() {
  const [sources, setSources] = useState<ItSource[]>([])
  const [summaries, setSummaries] = useState<Record<string, ItSourceSummary | null>>({})
  const [selectedId, setSelectedId] = useState('')
  const [fields, setFields] = useState<ItField[]>([])
  const [idp, setIdp] = useState<string | undefined>()
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [loaded, setLoaded] = useState(false)
  const [pausing, setPausing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [editor, setEditor] = useState<'new' | 'edit' | null>(null)
  const [standard, setStandard] = useState<Limits | null>(null)

  useEffect(() => {
    let live = true
    itStandardLimits().then((l) => { if (live) setStandard(l) }, () => { if (live) setStandard(null) })
    itSources().then((rows) => {
      if (!live) return
      setSources(rows); setLoaded(true); setError(null)
      setSelectedId((id) => (rows.some((r) => r.sourceId === id) ? id : rows[0]?.sourceId ?? ''))
      rows.forEach((r) => itSourceSummary(r.sourceId).then((s) => { if (live) setSummaries((all) => ({ ...all, [r.sourceId]: s })) }, () => { if (live) setSummaries((all) => ({ ...all, [r.sourceId]: null })) }))
    }).catch((e) => { if (live) { setError(loadFailed('Integrations did not load', e)); setLoaded(true) } })
    identityOverview().then((o) => { if (live) setIdp(o.active?.providerId || undefined) }, () => undefined)
    return () => { live = false }
  }, [reload])
  useEffect(() => {
    if (!selectedId) { setFields([]); return }
    let live = true
    setFields([])
    itFields(selectedId).then((f) => { if (live) setFields(f) }).catch((e) => { if (live) setError(loadFailed('What a sending system sends did not load', e, "What it sends didn't load")) })
    return () => { live = false }
  }, [selectedId, reload])

  const source = sources.find((s) => s.sourceId === selectedId)
  const summary = source ? summaries[source.sourceId] : undefined

  const setStatus = async (status: ItSource['status']) => {
    if (!source || busy) return
    setBusy(true); setError(null)
    try {
      await itUpdateSource(source.sourceId, { displayName: source.displayName, status, authType: source.authType, credentialReference: source.credentialReference, limits: source.limits })
      setPausing(false); setReload((x) => x + 1)
    } catch (e) { setError(actionFailed(status === 'DISABLED' ? 'Pausing a sending system failed' : 'Starting a sending system failed', e)) }
    finally { setBusy(false) }
  }

  return <>
    {error && <FailureNotice failure={error} loadTitle="Integrations didn't load" onRetry={() => setReload((x) => x + 1)} />}
    <div className="int-layout">
      <section className="v-panel int-list" aria-labelledby="int-list-h">
        <header className="v-panel-head int-list-head"><h2 id="int-list-h">Sending systems{loaded ? ` · ${sources.length}` : ''}</h2>
          <button type="button" className="v-btn sm primary" onClick={() => setEditor('new')}><Plus size={14} aria-hidden />Add</button></header>
        <div className="int-items">
          {!loaded ? <p className="muted">Loading…</p> : sources.length === 0 ? <p className="muted">No sending systems yet. Add the first one.</p> : sources.map((s) => {
            const line = statusLine(s, summaries[s.sourceId])
            return <button key={s.sourceId} type="button" className="int-item" aria-pressed={s.sourceId === selectedId} onClick={() => setSelectedId(s.sourceId)}>
              <strong>{s.displayName}</strong><span className={`int-status ${line.tone}`}><span className={`v-dot ${line.tone === 'ok' ? 'g' : line.tone === 'warn' ? 'y' : ''}`} aria-hidden />{line.text}</span>
            </button>
          })}
        </div>
      </section>

      {/* Canvas: one card for the chosen system: name and sentence with Edit and Pause, what it sends, limits and
          how it proves who it is side by side, and the technical details as a strip at the foot. */}
      {source && <section className="v-panel int-detail int-card" aria-label={source.displayName}>
        <header className="int-card-head">
          <div className="stack" style={{ gap: 4, minWidth: 0 }}>
            <h2 className="int-name">{source.displayName}</h2>
            <p className="muted int-facts">
              Sends documents to Vero through the API.
              {summary && <> {summary.submissionCount.toLocaleString()} {summary.submissionCount === 1 ? 'delivery' : 'deliveries'}, {summary.documentCount.toLocaleString()} {summary.documentCount === 1 ? 'document' : 'documents'}.
                {summary.lastProcessingFailureAt ? ` Last problem: ${fmtDate(summary.lastProcessingFailureAt)}.` : ' No problems so far.'}</>}
            </p>
          </div>
          <div className="row" style={{ gap: 8, flex: 'none' }}>
            <button type="button" className="v-btn sm" onClick={() => setEditor('edit')}>Edit</button>
            {source.status === 'ACTIVE'
              ? <button type="button" className="v-btn sm int-pause" disabled={busy} onClick={() => setPausing(true)}>Pause receiving</button>
              : <button type="button" className="v-btn sm primary" disabled={busy} onClick={() => void setStatus('ACTIVE')}>Start receiving</button>}
          </div>
        </header>

        <div className="int-sec">
          <h3 className="v-sec">What it sends</h3>
          {fields.length === 0 ? <p className="muted">No fields yet. Edit the system to add what it sends.</p> :
          <div className="int-table"><table className="v-table" aria-label="What it sends"><thead><tr><th>Field</th><th>Kind of value</th><th>Required</th><th>Used in search</th></tr></thead>
            <tbody>{fields.map((f) => <tr key={f.fieldKey}>
              <td className="lead" data-label="Field">{f.displayLabel}</td>
              <td data-label="Kind of value">{kindWords(f.fieldType, f.normalization)}</td>
              <td data-label="Required">{f.required ? 'Yes' : 'No'}</td>
              <td data-label="Used in search">{searchWords(f)}</td>
            </tr>)}</tbody></table></div>}
        </div>

        <div className="int-sec int-two">
          <div className="stack" style={{ gap: 8 }}><h3 className="v-sec">Limits per delivery</h3>
            <dl className="int-kv">{LIMIT_ROWS.map((r) => { const n = limitInUse(r.key, source.limits, standard); return <div key={r.key}><dt>{r.label}</dt><dd>{n != null ? r.words(n) : STANDARD}</dd></div> })}</dl></div>
          <div className="stack" style={{ gap: 8 }}><h3 className="v-sec">How it proves who it is</h3>
            <p>{AUTH[source.authType].sentence(idp)}</p>
            <p className="faint">{NEVER_SECRET}</p></div>
        </div>

        <TechDetails flat hint="source key, credential reference, field keys and versions, authentication type">
          <dl className="v-kv cols">
            <div><dt>Source key</dt><dd className="mono">{source.sourceKey}</dd></div>
            <div><dt>Where the secret is kept</dt><dd className="mono">{source.credentialReference || '—'}</dd></div>
            <div><dt>Sign-in type</dt><dd className="mono">{source.authType}</dd></div>
            <div><dt>Total size of one delivery</dt><dd>{(() => { const n = limitInUse('maxAggregateSubmissionBytes', source.limits, standard); return n != null ? mb(n) : STANDARD })()}</dd></div>
          </dl>
          <ul className="int-keys">{fields.map((f) => <li key={f.fieldKey}><span className="mono">{f.fieldKey}</span> · version {f.version} · <span className="mono">{f.fieldType} · {f.normalization}</span></li>)}</ul>
        </TechDetails>
      </section>}
    </div>

    {pausing && source && <Dialog title={`Pause receiving from ${source.displayName}?`} onClose={() => { if (!busy) setPausing(false) }} footer={<>
      <button type="button" className="v-btn" disabled={busy} onClick={() => setPausing(false)}>Cancel</button>
      <button type="button" className="v-btn primary" disabled={busy} onClick={() => void setStatus('DISABLED')}>{busy ? 'Pausing…' : 'Pause receiving'}</button>
    </>}>
      <p>While paused, Vero refuses deliveries from this system. It will need to send them again.</p>
    </Dialog>}

    {editor && <SourceEditor mode={editor} source={editor === 'edit' ? source : undefined} fields={editor === 'edit' ? fields : []} others={sources} idp={idp} standard={standard}
      onClose={() => setEditor(null)} onSaved={(id) => { setEditor(null); setSelectedId(id); setReload((x) => x + 1) }} />}
  </>
}

type DraftField = { key: string; label: string; kind: string; required: boolean; original?: ItField }
const FIELD_KEY = /^[A-Za-z][A-Za-z0-9_.-]{0,99}$/
const SOURCE_KEY = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/
const STEPS = ['About it', 'What it sends', 'How it signs in'] as const
const LIMIT_INPUTS: { key: keyof Limits; label: string; unit: string; factor: number }[] = [
  { key: 'maxPdfBytes', label: 'Largest PDF', unit: 'MB', factor: MB },
  { key: 'maxPagesPerPdf', label: 'Most pages in one PDF', unit: 'pages', factor: 1 },
  { key: 'maxDocumentsPerSubmission', label: 'Most documents in one delivery', unit: 'documents', factor: 1 },
  { key: 'maxAggregateSubmissionBytes', label: 'Total size of one delivery', unit: 'MB', factor: MB },
  { key: 'incompleteAttentionHours', label: 'Wait for missing documents', unit: 'hours', factor: 1 },
]
/** Placeholder for an empty limit box: the standard value in the box's unit, for example "Standard: 250". */
const standardHint = (l: (typeof LIMIT_INPUTS)[number], standard: Limits | null) => {
  const n = standard?.[l.key]
  return n != null ? `Standard: ${(Math.round((n / l.factor) * 10) / 10).toLocaleString()}` : STANDARD
}

function SourceEditor({ mode, source, fields, others, idp, standard, onClose, onSaved }: { mode: 'new' | 'edit'; source?: ItSource; fields: ItField[]; others: ItSource[]; idp?: string; standard: Limits | null; onClose: () => void; onSaved: (sourceId: string) => void }) {
  const [step, setStep] = useState(0)
  const [name, setName] = useState(source?.displayName ?? '')
  const [key, setKey] = useState(source?.sourceKey ?? '')
  const [draft, setDraft] = useState<DraftField[]>(() => fields.map((f) => ({ key: f.fieldKey, label: f.displayLabel, kind: kindKey(f.fieldType, f.normalization), required: f.required, original: f })))
  const [authType, setAuthType] = useState<ItSource['authType']>(source?.authType ?? 'OAUTH2_CLIENT_CREDENTIALS')
  const [credential, setCredential] = useState(source?.credentialReference ?? '')
  const [limits, setLimits] = useState<Record<string, string>>(() => Object.fromEntries(LIMIT_INPUTS.map((l) => { const n = source?.limits?.[l.key]; return [l.key, n != null ? String(Math.round((n / l.factor) * 10) / 10) : ''] })))
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<Failure | null>(null)
  const kinds = useMemo(() => {
    const all = [...KINDS]
    for (const f of fields) if (!all.some((k) => kindKey(k.type, k.normalization) === kindKey(f.fieldType, f.normalization))) all.push({ type: f.fieldType, normalization: f.normalization, label: kindWords(f.fieldType, f.normalization) })
    return all
  }, [fields])

  const keyTaken = mode === 'new' && others.some((s) => s.sourceKey.toLowerCase() === key.trim().toLowerCase())
  const aboutValid = name.trim().length > 0 && name.trim().length <= 200 && (mode === 'edit' || (SOURCE_KEY.test(key.trim()) && !keyTaken))
  const fieldProblems = draft.map((f, i) => !FIELD_KEY.test(f.key) ? 'Use letters, numbers, dots, hyphens or underscores, starting with a letter.'
    : draft.some((g, j) => j !== i && g.key === f.key) ? 'Each field needs its own key.' : !f.label.trim() ? 'Give the field a label.' : null)
  const fieldsValid = fieldProblems.every((p) => p == null)
  const limitValue = (l: (typeof LIMIT_INPUTS)[number]) => { const t = limits[l.key].trim(); if (!t) return null; const n = Number(t); return Number.isFinite(n) && n > 0 ? Math.round(n * l.factor) : NaN }
  const limitsValid = LIMIT_INPUTS.every((l) => { const v = limitValue(l); return v == null || (Number.isInteger(v) && v > 0) })
  const stepValid = [aboutValid, fieldsValid, limitsValid][step]

  const save = async (paused: boolean) => {
    if (busy || !aboutValid || !fieldsValid || !limitsValid) return
    setBusy(true); setSaveError(null)
    const limitBody = Object.fromEntries(LIMIT_INPUTS.map((l) => [l.key, limitValue(l)])) as Limits
    try {
      let id = source?.sourceId ?? ''
      if (mode === 'new') {
        const created = await itCreateSource({ sourceKey: key.trim(), displayName: name.trim(), authType, credentialReference: credential.trim() })
        id = created.sourceId
        // Paused (and the limits) straight away, before any field is added.
        await itUpdateSource(id, { displayName: name.trim(), status: paused ? 'DISABLED' : 'ACTIVE', authType, credentialReference: credential.trim(), limits: limitBody })
      } else if (source) {
        await itUpdateSource(id, { displayName: name.trim(), status: source.status, authType, credentialReference: credential.trim(), limits: limitBody })
      }
      for (const f of draft) {
        const kind = kinds.find((k) => kindKey(k.type, k.normalization) === f.kind)!
        const o = f.original
        if (o && o.displayLabel === f.label.trim() && o.fieldType === kind.type && o.normalization === kind.normalization && o.required === f.required) continue
        await itUpsertField(id, f.key, { displayLabel: f.label.trim(), fieldType: kind.type, required: f.required, normalization: kind.normalization, validationRules: {} })
      }
      onSaved(id)
    } catch (e) { setSaveError(actionFailed('Saving the sending system failed', e)) }
    finally { setBusy(false) }
  }

  const last = step === STEPS.length - 1
  // Canvas: what is being done above, the step as the title.
  return <Drawer wide eyebrow={mode === 'new' ? 'Add a sending system' : `Edit ${source?.displayName ?? ''}`} title={`Step ${step + 1} of ${STEPS.length} · ${STEPS[step]}`} onClose={() => { if (!busy) onClose() }}
    footer={<>
      {step > 0 ? <button type="button" className="v-btn quiet int-back" disabled={busy} onClick={() => setStep((s) => s - 1)}>Back</button> : <button type="button" className="v-btn" disabled={busy} onClick={onClose}>Cancel</button>}
      {!last ? <button type="button" className="v-btn primary" disabled={!stepValid} onClick={() => setStep((s) => s + 1)}>Next</button> : <>
        {mode === 'new' && <button type="button" className="v-btn" disabled={busy || !stepValid} onClick={() => void save(true)}>Save as paused</button>}
        <button type="button" className="v-btn primary" disabled={busy || !stepValid} onClick={() => void save(false)}>{busy ? 'Saving…' : 'Save'}</button>
      </>}
    </>}>
    <ol className="int-steps" aria-label="Steps">{STEPS.map((s, i) => <li key={s} aria-current={i === step ? 'step' : undefined} className={i < step ? 'done' : undefined}>{i + 1} {s}{i < step && ' ✓'}</li>)}</ol>
    {saveError && <FailureNotice failure={saveError} loadTitle="" actionTitle="The sending system wasn't saved" onRetry={() => undefined} />}

    {step === 0 && <>
      <label className="v-field"><span>Name</span><input className="v-input" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} placeholder="For example: Loan Origination System" /></label>
      {mode === 'new'
        ? <label className="v-field"><span>Source key</span><input className={`v-input mono${key && !SOURCE_KEY.test(key.trim()) || keyTaken ? ' invalid' : ''}`} value={key} maxLength={100} onChange={(e) => setKey(e.target.value)} placeholder="loan-origination" />
          <small className={keyTaken ? 'bad' : 'faint'}>{keyTaken ? 'Another sending system already uses this key.' : 'The short name the system sends with each delivery. It can’t change later.'}</small></label>
        : <dl className="v-kv"><div><dt>Source key</dt><dd className="mono">{source?.sourceKey}</dd></div></dl>}
      <Callout title="Vero can't test the connection itself">The other system calls Vero. Until its first delivery arrives, it shows “Waiting for the first delivery”.</Callout>
    </>}

    {step === 1 && <>
      <p>The fields this system sends with every delivery. Business admin later decides how each is searched.</p>
      <div className="int-fields">
        <div className="int-field-head" aria-hidden><span>Field</span><span>Key it sends</span><span>Kind of value</span><span>Required</span></div>
        {draft.map((f, i) => <fieldset key={i} className="int-field">
          <legend className="sr-only">Field {i + 1}</legend>
          <div className="int-field-row">
            <label className="v-field"><span>Label</span><input className="v-input" value={f.label} maxLength={200} onChange={(e) => setDraft((d) => d.map((x, j) => (j === i ? { ...x, label: e.target.value } : x)))} /></label>
            <label className="v-field"><span>Key it sends</span><input className="v-input mono" value={f.key} readOnly={!!f.original} maxLength={100} onChange={(e) => setDraft((d) => d.map((x, j) => (j === i ? { ...x, key: e.target.value } : x)))} /></label>
            <label className="v-field"><span>Kind of value</span><select className="v-select" value={f.kind} onChange={(e) => setDraft((d) => d.map((x, j) => (j === i ? { ...x, kind: e.target.value } : x)))}>
              {kinds.map((k) => <option key={kindKey(k.type, k.normalization)} value={kindKey(k.type, k.normalization)}>{k.label}</option>)}</select></label>
          </div>
          <div className="row between wrap" style={{ gap: 8 }}>
            <label className="v-check"><input type="checkbox" checked={f.required} onChange={(e) => setDraft((d) => d.map((x, j) => (j === i ? { ...x, required: e.target.checked } : x)))} /><span>Required</span></label>
            {f.original ? <small className="faint">Already in use, so it can’t be removed. Untick Required instead.</small>
              : <button type="button" className="v-btn quiet sm" aria-label={`Remove field ${i + 1}`} onClick={() => setDraft((d) => d.filter((_, j) => j !== i))}><Trash2 size={14} aria-hidden />Remove</button>}
          </div>
          {fieldProblems[i] && (f.key || f.label) && <small className="bad">{fieldProblems[i]}</small>}
        </fieldset>)}
      </div>
      <button type="button" className="v-btn sm" style={{ alignSelf: 'flex-start' }} onClick={() => setDraft((d) => [...d, { key: '', label: '', kind: kindKey('TEXT', 'NONE'), required: false }])}><Plus size={14} aria-hidden />Add a field</button>
      <div className="v-banner warn">Changing a field later affects new deliveries only. Documents already stored keep the values they arrived with.</div>
      <small className="faint">Which field identifies a record is set by the Business admin under Search fields.</small>
    </>}

    {step === 2 && <>
      <fieldset className="int-auth"><legend>How it signs in</legend>
        {(Object.keys(AUTH) as ItSource['authType'][]).map((t) => <label key={t} className="int-auth-card">
          <input type="radio" name="int-auth" checked={authType === t} onChange={() => setAuthType(t)} />
          <span><strong>{AUTH[t].title}</strong><small className="muted">{AUTH[t].sentence(idp)}</small></span>
        </label>)}
      </fieldset>
      <label className="v-field"><span>Where the secret is kept</span><input className="v-input mono" value={credential} maxLength={300} onChange={(e) => setCredential(e.target.value)} placeholder="For example: okta/loan-origination-client" />
        <small className="faint">The name of the entry in your secrets store, never the secret itself. {NEVER_SECRET}</small></label>
      <details className="int-advanced"><summary>Advanced (usually leave as is)</summary>
        <p className="faint">Leave a box empty to use {standard ? "Vero's standard limit, shown in the box" : STANDARD}. A system's own limit counts only when it is lower than the standard.</p>
        {LIMIT_INPUTS.map((l) => <label key={l.key} className="v-field"><span>{l.label} ({l.unit})</span>
          <input className="v-input" inputMode="decimal" value={limits[l.key]} placeholder={standardHint(l, standard)} onChange={(e) => setLimits((x) => ({ ...x, [l.key]: e.target.value }))} /></label>)}
        {!limitsValid && <small className="bad">Use a number above zero, or leave the box empty.</small>}
      </details>
    </>}
  </Drawer>
}
