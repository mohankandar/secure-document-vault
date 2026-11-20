import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { GripVertical } from 'lucide-react'
import { useStore } from './store'
import { BUSINESS_LIMITS, type SearchMode } from './model'
import { FailureNotice, actionFailed, loadFailed, type Failure } from './CardError'
import { Callout, Drawer, Switch, TechDetails } from './ui'
import {
  adminMetadataFields, adminMetadataSources, getMetadataDisplayOrder, getRecordTitleFields, saveMetadataDisplayOrder, saveRecordTitleFields,
  updateBusinessMetadata, type AdminMetadataField, type AdminMetadataSource,
} from './liveApi'

// Task 0009 Part B (finding 22) and Part C: search fields in words, drag to reorder, and the Record title setting.

/** How a field matches, in words. Starts with and Contains ignore case on the server; Exact follows the stored form. */
export const MATCH_WORDS: Record<SearchMode, string> = { EXACT: 'Exact', PREFIX: 'Starts with, any case', PARTIAL: 'Contains, any case' }
const MATCH_CHOICES: { mode: SearchMode; label: string; example: string }[] = [
  { mode: 'EXACT', label: 'Exact', example: 'Must match all of it' },
  { mode: 'PREFIX', label: 'Starts with', example: '“Pat” finds Patel' },
  { mode: 'PARTIAL', label: 'Contains', example: '“tel” finds Patel' },
]
/** The server's largest page of search results. */
const MAX_PAGE_SIZE = 50
const sameList = (a: string[], b: string[]) => a.length === b.length && a.every((x, i) => x === b[i])

function caseNote(f: AdminMetadataField): string {
  if (f.searchMode !== 'EXACT') return 'Upper and lower case never matter for Starts with and Contains.'
  // Exact compares the value as stored; a field stored in one case matches any case typed.
  return (f.normalization as string) === 'UPPERCASE' || (f.normalization as string) === 'LOWERCASE'
    ? 'Upper and lower case never matter for this field.'
    : 'Exact compares the value as it was sent, so upper and lower case count. Starts with and Contains ignore case.'
}

export function SearchFields() {
  const { can, toast } = useStore()
  const allowed = can('CONFIGURE_METADATA_SEARCH')
  const [sources, setSources] = useState<AdminMetadataSource[]>([])
  const [sourceId, setSourceId] = useState('')
  const [fields, setFields] = useState<AdminMetadataField[]>([])
  const [savedOrder, setSavedOrder] = useState<string[]>([])
  const [titleSaved, setTitleSaved] = useState<string[]>([])
  const [titleDraft, setTitleDraft] = useState<string[]>([])
  const [draft, setDraft] = useState<AdminMetadataField | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [dragKey, setDragKey] = useState<string | null>(null)
  const rows = useRef(new Map<string, HTMLTableRowElement>())
  const dragStart = useRef<string[]>([])

  useEffect(() => {
    let active = true
    adminMetadataSources().then((s) => { if (active) { setSources(s); setSourceId((id) => (s.some((x) => x.sourceId === id) ? id : s[0]?.sourceId ?? '')) } })
      .catch((e) => { if (active) { setError(loadFailed('Search fields did not load', e)); setLoading(false) } })
    return () => { active = false }
  }, [reload])
  useEffect(() => {
    if (!sourceId) { setFields([]); if (sources.length === 0) setLoading(false); return }
    let active = true
    setLoading(true); setError(null); setDraft(null)
    Promise.all([adminMetadataFields(sourceId), getMetadataDisplayOrder(sourceId), getRecordTitleFields(sourceId)]).then(([rows, order, title]) => {
      if (!active) return
      const keys = [...order.filter((k) => rows.some((f) => f.fieldKey === k)), ...rows.map((f) => f.fieldKey).filter((k) => !order.includes(k))]
      setFields([...rows].sort((a, b) => keys.indexOf(a.fieldKey) - keys.indexOf(b.fieldKey)))
      setSavedOrder(keys); setTitleSaved(title); setTitleDraft(title)
    }).catch((e) => { if (active) setError(loadFailed('Search fields did not load', e)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [sourceId, reload, sources.length])

  const label = (key: string) => fields.find((f) => f.fieldKey === key)?.displayLabel ?? key
  const titleChoices = fields.filter((f) => !f.businessIdentifier)

  const saveField = async (next: AdminMetadataField, done?: () => void) => {
    if (!allowed || busy) return
    setBusy(true); setError(null)
    // The server keeps Exact as the stored match of a field nobody can search by.
    const body = { ...next, displayLabel: next.displayLabel.trim(), searchMode: next.searchable ? next.searchMode : 'EXACT' as SearchMode }
    try {
      const result = await updateBusinessMetadata(sourceId, body)
      setFields((all) => all.map((f) => (f.fieldKey === result.fieldKey ? result : f)))
      done?.(); toast({ title: 'Field saved', detail: result.displayLabel })
    } catch (e) { setError(actionFailed('Saving a search field failed', e)) }
    finally { setBusy(false) }
  }

  const saveOrder = async (keys: string[]) => {
    if (!allowed || sameList(keys, savedOrder)) return
    setBusy(true); setError(null)
    try {
      await saveMetadataDisplayOrder(sourceId, keys)
      // Read the stored order back; success is only reported for what was really stored.
      const stored = await getMetadataDisplayOrder(sourceId)
      if (!sameList(stored, keys)) throw new Error('The stored field order did not match the order sent.')
      setSavedOrder(stored); toast({ title: 'Field order saved', detail: 'Filters and results use the new order.' })
    } catch (e) {
      setFields((all) => [...all].sort((a, b) => savedOrder.indexOf(a.fieldKey) - savedOrder.indexOf(b.fieldKey)))
      setError(actionFailed('Saving the field order failed', e))
    } finally { setBusy(false) }
  }
  const reorder = (keys: string[]) => setFields((all) => keys.map((k) => all.find((f) => f.fieldKey === k)!))

  // Drag: the handle captures the pointer (mouse, touch or pen); the row moves to where the pointer is.
  const onDragStart = (e: PointerEvent<HTMLButtonElement>, key: string) => {
    if (!allowed || busy) return
    e.preventDefault(); e.currentTarget.setPointerCapture(e.pointerId)
    dragStart.current = fields.map((f) => f.fieldKey); setDragKey(key)
  }
  const onDragMove = (e: PointerEvent<HTMLButtonElement>) => {
    if (!dragKey) return
    const others = fields.map((f) => f.fieldKey).filter((k) => k !== dragKey)
    const at = others.filter((k) => { const r = rows.current.get(k)?.getBoundingClientRect(); return r ? r.top + r.height / 2 < e.clientY : false }).length
    const next = [...others.slice(0, at), dragKey, ...others.slice(at)]
    if (!sameList(next, fields.map((f) => f.fieldKey))) reorder(next)
  }
  const onDragEnd = () => {
    if (!dragKey) return
    setDragKey(null)
    const keys = fields.map((f) => f.fieldKey)
    if (!sameList(keys, dragStart.current)) void saveOrder(keys)
  }
  const onHandleKey = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const to = e.key === 'ArrowUp' ? index - 1 : e.key === 'ArrowDown' ? index + 1 : -1
    if (to < 0 || to >= fields.length || busy) return
    e.preventDefault()
    const keys = fields.map((f) => f.fieldKey); [keys[index], keys[to]] = [keys[to], keys[index]]
    reorder(keys); void saveOrder(keys)
    requestAnimationFrame(() => rows.current.get(keys[to])?.querySelector<HTMLButtonElement>('.sf-handle')?.focus())
  }

  const saveTitle = async () => {
    if (!allowed || busy) return
    setBusy(true); setError(null)
    try {
      const stored = await saveRecordTitleFields(sourceId, titleDraft)
      setTitleSaved(stored); setTitleDraft(stored)
      toast({ title: 'Record title saved', detail: stored.length ? `Records are called by ${stored.map(label).join(' + ')}.` : 'Records use the default title.' })
    } catch (e) { setError(actionFailed('Saving the record title failed', e)) }
    finally { setBusy(false) }
  }
  const setTitlePart = (i: number, key: string) => setTitleDraft((d) => {
    const next = [d[0] ?? '', d[1] ?? '']; next[i] = key
    if (i === 0 && (!key || next[1] === key)) next[1] = ''
    return next.filter(Boolean)
  })

  return <>
    {error && <FailureNotice failure={error} loadTitle="Search fields didn't load" onRetry={() => setReload((x) => x + 1)} />}
    {sources.length > 1 && <label className="v-field sf-source"><span>Sending system</span>
      <select className="v-select" value={sourceId} onChange={(e) => setSourceId(e.target.value)}>{sources.map((s) => <option key={s.sourceId} value={s.sourceId}>{s.displayName}</option>)}</select>
    </label>}
    {loading ? <Callout title="Loading">Reading the search fields…</Callout> : !error && fields.length === 0 ? <Callout title="No fields yet">Tenant IT adds the fields a sending system sends. They appear here once it has.</Callout> : fields.length > 0 && <>
      <section className="v-panel sf-title" aria-labelledby="sf-title-h"><div className="v-panel-body">
        <div className="stack grow" style={{ gap: 3 }}>
          <h2 id="sf-title-h" className="sf-title-h">Record title</h2>
          <span className="muted">What each record is called in results, the record panel and the viewer.</span>
        </div>
        <div className="row wrap" style={{ gap: 8, alignItems: 'center' }}>
          <select className="v-select" aria-label="Record title, first field" disabled={!allowed || busy} value={titleDraft[0] ?? ''} onChange={(e) => setTitlePart(0, e.target.value)}>
            <option value="">Not set (default)</option>
            {titleChoices.map((f) => <option key={f.fieldKey} value={f.fieldKey}>{f.displayLabel}</option>)}
          </select>
          <span aria-hidden className="faint">+</span>
          <select className="v-select" aria-label="Record title, second field" disabled={!allowed || busy || !titleDraft[0]} value={titleDraft[1] ?? ''} onChange={(e) => setTitlePart(1, e.target.value)}>
            <option value="">Nothing more</option>
            {titleChoices.filter((f) => f.fieldKey !== titleDraft[0]).map((f) => <option key={f.fieldKey} value={f.fieldKey}>{f.displayLabel}</option>)}
          </select>
          <button type="button" className="v-btn primary sm" disabled={!allowed || busy || sameList(titleDraft, titleSaved)} onClick={() => void saveTitle()}>Save</button>
        </div>
        <p className="faint sf-title-note">{titleDraft.length
          ? `Shown as ${titleDraft.map(label).join(' ')}. A record missing one of these uses the default.`
          : 'Default: the first field after the identifiers, A to Z. An identifier is never the title; it shows beside it.'}</p>
      </div></section>

      <section className="v-panel" aria-label="Fields">
        <table className="v-table sf-table" aria-label="Search fields">
          <thead><tr><th className="sf-grip"><span className="sr-only">Order</span></th><th>Field</th><th>Searchable</th><th>How it matches</th><th className="act"><span className="sr-only">Edit</span></th></tr></thead>
          <tbody>{fields.map((f, i) => (
            <tr key={f.fieldKey} ref={(el) => { if (el) rows.current.set(f.fieldKey, el); else rows.current.delete(f.fieldKey) }} className={dragKey === f.fieldKey ? 'sf-dragging' : draft?.fieldKey === f.fieldKey ? 'sf-editing' : undefined}>
              <td className="sf-grip">
                <button type="button" className="v-btn quiet icon sm sf-handle" aria-label={`Move ${f.displayLabel}. Use the arrow keys or drag.`} disabled={!allowed || busy}
                  onPointerDown={(e) => onDragStart(e, f.fieldKey)} onPointerMove={onDragMove} onPointerUp={onDragEnd} onPointerCancel={onDragEnd}
                  onKeyDown={(e) => onHandleKey(e, i)}><GripVertical size={16} aria-hidden /></button>
              </td>
              <td className="lead" data-label="Field"><strong>{f.displayLabel}</strong>{f.businessIdentifier && <small className="faint sf-sub">Identifier · always searchable</small>}</td>
              <td data-label="Searchable">{f.businessIdentifier ? <span className="muted">Always</span>
                : <Switch label={`${f.displayLabel} searchable`} checked={f.searchable} disabled={!allowed || busy} onChange={(v) => void saveField({ ...f, searchable: v })} />}</td>
              <td data-label="How it matches">{f.searchable ? MATCH_WORDS[f.searchMode] : <span className="faint">—</span>}</td>
              <td className="act"><button type="button" className="v-btn sm" aria-label={`Edit ${f.displayLabel}`} disabled={!allowed || busy} onClick={() => setDraft({ ...f })}>Edit</button></td>
            </tr>
          ))}</tbody>
        </table>
        <p className="faint sf-hint">Drag <GripVertical size={13} aria-hidden style={{ verticalAlign: '-2px' }} /> to set the order fields appear in Filters and in results.</p>
      </section>
    </>}

    {/* Canvas: the limits on one line in a small card. */}
    <section className="v-panel sf-limits-card" aria-label="Limits"><span className="v-sec">Limits</span><ul className="sf-limits">
      <li>Records per page: <b>{BUSINESS_LIMITS.pageSize} (most {MAX_PAGE_SIZE})</b></li>
      <li>Comment length: <b>{BUSINESS_LIMITS.maxCommentLength.toLocaleString()} characters</b></li>
      <li>Tag length: <b>{BUSINESS_LIMITS.maxTagLength} characters</b></li>
    </ul></section>

    {draft && <Drawer eyebrow="Search field" title={fields.find((f) => f.fieldKey === draft.fieldKey)?.displayLabel ?? draft.displayLabel} onClose={() => { if (!busy) setDraft(null) }}
      footer={<><button type="button" className="v-btn" disabled={busy} onClick={() => setDraft(null)}>Cancel</button>
        <button type="button" className="v-btn primary" disabled={!draft.displayLabel.trim() || busy} onClick={() => void saveField(draft, () => setDraft(null))}>{busy ? 'Saving…' : 'Save'}</button></>}>
      <label className="v-field"><span>Label people see</span><input className="v-input" value={draft.displayLabel} maxLength={200} onChange={(e) => setDraft({ ...draft, displayLabel: e.target.value })} /></label>
      <div className="row between"><span className="v-label">People can search by it</span>
        <Switch label="People can search by it" checked={draft.businessIdentifier || draft.searchable} disabled={draft.businessIdentifier} onChange={(v) => setDraft({ ...draft, searchable: v })} /></div>
      {draft.businessIdentifier && <small className="faint">An identifier can always be searched.</small>}
      <fieldset className="v-field sf-match" disabled={!draft.searchable}>
        <legend>How it matches</legend>
        {MATCH_CHOICES.map((c) => (
          <label key={c.mode} className="sf-choice">
            <input type="radio" name="sf-match" checked={draft.searchMode === c.mode} onChange={() => setDraft({ ...draft, searchMode: c.mode })} />
            <span><strong>{c.label}</strong><small className="faint">{c.example}</small></span>
          </label>
        ))}
        <small className="muted">{caseNote(draft)}</small>
      </fieldset>
      <TechDetails flat hint="field key, type, normalization, version">
        <dl className="v-kv"><div><dt>Field key</dt><dd className="mono">{draft.fieldKey}</dd></div><div><dt>Type</dt><dd className="mono">{draft.fieldType}</dd></div>
          <div><dt>Normalization</dt><dd className="mono">{draft.normalization}</dd></div><div><dt>Version</dt><dd>{draft.version}</dd></div></dl>
      </TechDetails>
    </Drawer>}
  </>
}
