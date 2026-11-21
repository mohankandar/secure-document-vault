import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Lock, X } from 'lucide-react'
import type { SensitivityLevel } from '../api'
import { ApiRequestError } from '../api'
import { useStore } from './store'
import type { Doc, RecordItem } from './model'
import { askToLowerLevel, raiseLevel, requestPrintOrDownload, LEVEL_NAMES, type LevelChangeResult, type LevelScope, type MyRequest } from './liveApi'

/**
 * Task 0029 (S9a part 1): the sensitivity pieces the boards Sens-Results, Sens-Viewer and Sens-Level show, shared by
 * the record panel and the viewer. Every action is offered only to roles that have it (hidden, not greyed); the
 * server still decides, and its refusals come back with their code (docs/api/error-codes.md).
 */

const RANK: Record<SensitivityLevel, number> = { STANDARD: 0, CONFIDENTIAL: 1, HIGHLY_CONFIDENTIAL: 2 }
const LEVELS: SensitivityLevel[] = ['STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL']
const higherOf = (a: SensitivityLevel, b: SensitivityLevel | null | undefined) => (b && RANK[b] > RANK[a] ? b : a)
const LEVEL_CLASS: Record<SensitivityLevel, string> = { STANDARD: 's', CONFIDENTIAL: 'c', HIGHLY_CONFIDENTIAL: 'hc' }

/** What a person opening a locked document sees, and nothing more (task 0029, item 5). */
export const LOCKED_MESSAGE = "You can't open this document"

/** The level badge of the boards: grey Standard, light blue Confidential, dark Highly Confidential. */
export function LevelBadge({ level, lock }: { level: SensitivityLevel; lock?: boolean }) {
  return <span className={`v-lvl ${LEVEL_CLASS[level]}`}>{lock && <Lock size={12} strokeWidth={2.4} aria-hidden />}{LEVEL_NAMES[level]}</span>
}

/** "Contract number" -> "contract": what the record is called in this sending system's own words ("record" otherwise). */
export function recordWord(record: Pick<RecordItem, 'identifiers'>): string {
  const label = record.identifiers[0]?.label?.trim() ?? ''
  const m = /^(.+?)\s+(number|no\.?|id|reference|ref)$/i.exec(label)
  const word = (m ? m[1] : '').trim()
  return word && word.split(/\s+/).length <= 2 ? word.toLowerCase() : 'record'
}
const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)

/** A refusal in plain words, with its code as a reference ("… (SDV-SEN-013)"). */
export function refusalText(e: unknown): string {
  if (e instanceof ApiRequestError) return e.code ? `${e.message} (${e.code})` : e.message
  return e instanceof Error ? e.message : 'Something went wrong. Try again.'
}

/** "today 09:40", "yesterday 16:02", "tomorrow 10:12", else "14 Nov 10:12". */
export function whenWords(iso: string, withTime = true, now: Date = new Date()): string {
  const d = new Date(iso)
  const day = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diff = Math.round((day(d) - day(now)) / 86_400_000)
  const time = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit' }).format(d)
  const name = diff === 0 ? 'today' : diff === -1 ? 'yesterday' : diff === 1 ? 'tomorrow' : new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' }).format(d)
  return withTime ? `${name} ${time}` : name
}

const KIND_WORDS = (r: MyRequest, word: string) =>
  r.kind === 'PRINT' ? 'Print' : r.kind === 'DOWNLOAD' ? 'Download'
    : r.kind === 'LOWER_LEVEL' ? `Lower to ${r.requestedLevel ? LEVEL_NAMES[r.requestedLevel] : 'a lower level'}`
      : `Lower ${word} to ${r.requestedLevel ? LEVEL_NAMES[r.requestedLevel] : 'a lower level'}`

/** The state of a request in the board's words, and its pill colour. */
export function requestState(r: MyRequest): { text: string; tone: 'ok' | 'wait' | 'off' } {
  const grant = r.kind === 'PRINT' || r.kind === 'DOWNLOAD'
  if (r.status === 'PENDING') return { text: 'Waiting for approval', tone: 'wait' }
  if (r.status === 'APPROVED') return grant ? { text: `Approved · use by ${whenWords(r.expiresAt)}`, tone: 'ok' } : { text: 'Approved · changed', tone: 'ok' }
  if (r.status === 'USED') return { text: `Used ${r.usedAt ? whenWords(r.usedAt) : ''}`.trim(), tone: 'off' }
  if (r.status === 'DECLINED') return { text: 'Declined', tone: 'off' }
  return { text: 'Expired', tone: 'off' }
}

/** One request as the viewer's lists show it: what, when and why, and its state. */
export function RequestItem({ r, word }: { r: MyRequest; word: string }) {
  const state = requestState(r)
  const sent = whenWords(r.requestedAt, !isYesterdayOrOlder(r.requestedAt))
  return (
    <li className="v-req">
      <b>{KIND_WORDS(r, word)}{r.scope === 'RECORD' && <span className="v-req-whole">Whole {word}</span>}</b>
      <span className="faint">Sent {sent} · “{r.reason}”</span>
      <span className={`v-req-st ${state.tone}`}>{state.text}</span>
    </li>
  )
}
const isYesterdayOrOlder = (iso: string) => { const d = new Date(iso), n = new Date(); return new Date(d.getFullYear(), d.getMonth(), d.getDate()) < new Date(n.getFullYear(), n.getMonth(), n.getDate()) }

/** The approved, unused, unexpired grant of this kind for this document, if there is one. */
export function validGrant(requests: MyRequest[], documentId: string, kind: 'PRINT' | 'DOWNLOAD', now = Date.now()): MyRequest | null {
  return requests.find((r) => r.documentId === documentId && r.kind === kind && r.status === 'APPROVED' && new Date(r.expiresAt).getTime() > now) ?? null
}
/** A request of this kind for this document still waiting. */
export const pendingOf = (requests: MyRequest[], documentId: string, kind: 'PRINT' | 'DOWNLOAD') =>
  requests.find((r) => r.documentId === documentId && r.kind === kind && r.status === 'PENDING') ?? null

// ---------------------------------------------------------------------------------------------- level dialog

const LEVEL_TEXT: Record<SensitivityLevel, string> = {
  STANDARD: 'Everyone with access to records can open it.',
  CONFIDENTIAL: 'Document stewards and restricted stewards can open it. Others see it as restricted.',
  HIGHLY_CONFIDENTIAL: 'Only restricted stewards can open it.',
}

type Choice = { level: SensitivityLevel; relation: 'now' | 'higher' | 'lower'; blocked: string | null; note: string | null }

/**
 * Board Sens-Level: "Change sensitivity level". Apply to This document (preselected from the viewer) or the whole
 * record (preselected from the record panel). A raise applies at once with a reason; a lowering "Needs approval" and
 * becomes a request. "Now" is the document's effective level, or the record's level ("None set" counts as Standard).
 * inline: drawn over its positioned parent (the viewer's page area, as on the board) instead of the whole window.
 */
export function LevelDialog({ record, doc, initialScope, inline, onClose, onChanged }: {
  record: RecordItem; doc?: Doc; initialScope: LevelScope; inline?: boolean; onClose: () => void
  onChanged: (scope: LevelScope, result: LevelChangeResult) => void
}) {
  const { can } = useStore()
  const titleId = useId(), reasonId = useId()
  const [scope, setScope] = useState<LevelScope>(doc ? initialScope : 'record')
  const [level, setLevel] = useState<SensitivityLevel | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState('')
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => { box.current?.querySelector<HTMLElement>('input:checked, input')?.focus() }, [])
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); onClose() } }
    window.addEventListener('keydown', k, true)
    return () => window.removeEventListener('keydown', k, true)
  }, [onClose])

  const word = recordWord(record)
  const number = record.identifiers[0]?.value ?? ''
  const docCount = record.docs.length + (record.locked?.length ?? 0)
  const recordLevel = record.recordLevel
  const mayOpen = (l: SensitivityLevel) => l === 'STANDARD' || (l === 'CONFIDENTIAL' ? can('VIEW_CONFIDENTIAL') : can('VIEW_HIGHLY_CONFIDENTIAL'))
  // Fail closed: with a level not known yet, nothing can be chosen.
  const now: SensitivityLevel | null = scope === 'document' ? doc?.level ?? null : recordLevel === undefined ? null : recordLevel ?? 'STANDARD'
  const choices: Choice[] = LEVELS.map((l) => {
    if (!now) return { level: l, relation: 'now', blocked: 'Not known yet', note: null }
    const relation = l === now ? 'now' : RANK[l] > RANK[now] ? 'higher' : 'lower'
    let blocked: string | null = null, note: string | null = null
    if (relation === 'lower') {
      if (!can('VIEW_HIGHLY_CONFIDENTIAL') && now === 'HIGHLY_CONFIDENTIAL') blocked = 'Only a restricted steward can ask to lower a Highly Confidential level.'
      else if (scope === 'document') {
        const own = doc?.ownLevel ?? doc?.level ?? null
        if (!own || RANK[l] >= RANK[own]) blocked = `The ${word} keeps this document at ${LEVEL_NAMES[now]}. Change the whole ${word} instead.`
        else if (higherOf(l, recordLevel) === now) note = `The ${word} stays at ${LEVEL_NAMES[now]}, so who can open it won't change.`
      }
    }
    return { level: l, relation, blocked, note }
  })
  const chosen = choices.find((c) => c.level === level && c.relation !== 'now' && !c.blocked) ?? null
  const losesAccess = chosen?.relation === 'higher' && !mayOpen(chosen.level)
  const what = scope === 'document' ? 'document' : word
  const reasonOk = reason.trim().length >= 3 && reason.trim().length <= 500
  const action = !chosen ? 'Change level' : chosen.relation === 'higher' ? `Raise ${what} to ${LEVEL_NAMES[chosen.level]}` : `Ask to lower ${what} to ${LEVEL_NAMES[chosen.level]}`
  const targetId = scope === 'document' ? doc?.id : record.ref

  const submit = async () => {
    if (!chosen || !reasonOk || busy || !targetId) return
    setBusy(true); setFailure('')
    try {
      const result = chosen.relation === 'higher'
        ? await raiseLevel(scope, targetId, chosen.level, reason.trim())
        : await askToLowerLevel(scope, targetId, chosen.level, reason.trim())
      onChanged(scope, result)
    } catch (e) { setFailure(refusalText(e)) } finally { setBusy(false) }
  }

  const dialog = (
    <div ref={box} className="v-lvl-dlg" role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <div className="row between"><span id={titleId} className="v-lvl-dlg-title">Change sensitivity level</span>
        <button type="button" className="v-btn quiet" onClick={onClose} aria-label="Close"><X size={18} /></button></div>
      <div className="stack" style={{ gap: 6 }}>
        <span className="v-lvl-label" id={titleId + '-scope'}>Apply to</span>
        <div className="v-lvl-seg" role="radiogroup" aria-labelledby={titleId + '-scope'}>
          <label className={scope === 'document' ? 'on' : ''} title={doc ? undefined : 'Open a document to change only its level'}>
            <input type="radio" name={titleId + 'scope'} checked={scope === 'document'} disabled={!doc} onChange={() => { setScope('document'); setLevel(null); setFailure('') }} />This document</label>
          <label className={scope === 'record' ? 'on' : ''}>
            <input type="radio" name={titleId + 'scope'} checked={scope === 'record'} onChange={() => { setScope('record'); setLevel(null); setFailure('') }} />Whole {word} · {docCount} document{docCount === 1 ? '' : 's'}</label>
        </div>
        <span className="v-lvl-hint">{scope === 'record'
          ? `Whole ${word}: every document on ${number} becomes at least this level, including ones that arrive later.`
          : `This document: only this document changes. The ${word}'s own level still applies to it.`}</span>
      </div>
      <div className="stack" style={{ gap: 16 }} role="radiogroup" aria-label="Level">
        {choices.map((c) => (
          <label key={c.level} className={`v-lvl-opt${level === c.level ? ' on' : ''}${c.blocked && c.relation !== 'now' ? ' blocked' : ''}`}>
            <input type="radio" name={titleId + 'lvl'} checked={level === c.level} disabled={!!c.blocked || c.relation === 'now'}
              onChange={() => { setLevel(c.level); setFailure('') }} />
            <span className="t">
              <b><LevelBadge level={c.level} />{c.relation === 'now' && now && <span className="v-lvl-tag cur">Now</span>}{c.relation === 'lower' && !c.blocked && <span className="v-lvl-tag ap">Needs approval</span>}</b>
              <span>{LEVEL_TEXT[c.level]}{c.relation === 'higher' ? ' Changes straight away.' : c.relation === 'lower' && !c.blocked ? ' A Business admin approves before it changes.' : ''}</span>
              {c.relation !== 'now' && c.blocked && <span className="v-lvl-why">{c.blocked}</span>}
              {level === c.level && c.note && <span className="v-lvl-why">{c.note}</span>}
            </span>
          </label>
        ))}
      </div>
      {losesAccess && <div className="v-lvl-warn" role="note">{scope === 'record'
        ? `You won't be able to open the documents on this ${word} after this change. Only restricted stewards can lower it again.`
        : "You won't be able to open this document after this change. Only restricted stewards can lower it again."}</div>}
      <div className="v-lvl-field"><label htmlFor={reasonId}>Reason</label>
        <input id={reasonId} className="v-lvl-input" value={reason} maxLength={500} onChange={(e) => { setReason(e.target.value); setFailure('') }}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void submit() } }} aria-describedby={failure ? reasonId + '-err' : undefined} /></div>
      {failure && <p id={reasonId + '-err'} className="v-lvl-error" role="alert">{failure}</p>}
      <div className="row" style={{ gap: 10, justifyContent: 'flex-end' }}>
        <button type="button" className="v-btn" onClick={onClose}>Cancel</button>
        <button type="button" className="v-btn primary" disabled={!chosen || !reasonOk || busy || !targetId} onClick={() => void submit()}>{busy ? 'Saving…' : action}</button>
      </div>
    </div>
  )
  return <div className={`v-lvl-dim${inline ? ' inline' : ''}`} onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>{dialog}</div>
}

// ------------------------------------------------------------------------------------------ request popover

/**
 * Board Sens-Viewer: "Request a download" (or print) for a Highly Confidential document, beside the viewer's bar.
 * The hours come from the server's answer (the tenant's setting) once a request is approved; the text says 24 hours,
 * the default.
 */
export function RequestPopover({ kind, doc, onClose, onSent }: { kind: 'PRINT' | 'DOWNLOAD'; doc: Doc; onClose: () => void; onSent: (r: MyRequest) => void }) {
  const id = useId()
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState('')
  const area = useRef<HTMLTextAreaElement>(null)
  useEffect(() => { area.current?.focus() }, [])
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); onClose() } }
    window.addEventListener('keydown', k, true)
    return () => window.removeEventListener('keydown', k, true)
  }, [onClose])
  const word = kind === 'DOWNLOAD' ? 'download' : 'print'
  const ok = reason.trim().length >= 3 && reason.trim().length <= 500
  const send = async () => {
    if (!ok || busy) return
    setBusy(true); setFailure('')
    try { onSent(await requestPrintOrDownload(doc.id, kind, reason.trim())) } catch (e) { setFailure(refusalText(e)) } finally { setBusy(false) }
  }
  return (
    <div className="v-pop v-req-pop" role="dialog" aria-modal="false" aria-labelledby={id}>
      <div className="row between"><span id={id} className="v-pop-title">Request a {word}</span>
        <button type="button" className="v-btn quiet sm" onClick={onClose} aria-label="Close"><X size={18} /></button></div>
      <div className="v-lvl-field"><label htmlFor={id + '-why'}>Why do you need it?</label>
        <textarea ref={area} id={id + '-why'} className="v-pop-ta" value={reason} maxLength={500} onChange={(e) => { setReason(e.target.value); setFailure('') }} /></div>
      <div className="v-pop-info">A Business admin approves it. Once approved, you can {word} this document once, within 24 hours. Every step is recorded.</div>
      {failure && <p className="v-lvl-error" role="alert">{failure}</p>}
      <div className="row" style={{ gap: 10, justifyContent: 'flex-end' }}>
        <button type="button" className="v-btn sm" onClick={onClose}>Cancel</button>
        <button type="button" className="v-btn sm primary" disabled={!ok || busy} onClick={() => void send()}>{busy ? 'Sending…' : 'Send request'}</button>
      </div>
    </div>
  )
}

/** The Contract level box of board Sens-Results: the record's level, and Level for Stewards only. */
export function RecordLevelBox({ record, action }: { record: RecordItem; action?: ReactNode }) {
  const word = recordWord(record)
  const level = record.recordLevel
  return (
    <div className="ws-reclevel">
      <div className="grow stack" style={{ gap: 2 }}>
        <span className="ws-reclevel-label">{capital(word)} level</span>
        <span className="ws-reclevel-value">{level === undefined ? 'Loading…' : level ? <LevelBadge level={level} /> : 'None set: each document has its own level'}</span>
      </div>
      {action}
    </div>
  )
}
