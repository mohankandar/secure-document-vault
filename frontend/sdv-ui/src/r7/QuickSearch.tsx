import { useId, useMemo, useState, type KeyboardEvent, type ReactNode } from 'react'
import { CornerDownLeft, Search } from 'lucide-react'
import type { Field } from './model'
import type { RecentRecord } from './liveApi'
import { MODE_WORD } from './searchRouting'
import { ANYWHERE_MIN, fieldsFor, markParts, recentMatches } from './quickSearchRules'
import { fmtRel } from './ui'

// Task 0022 (canvas "Quick search 1 · Typing"): after 2 characters a list opens under the search box with
//  - Search everywhere for "…" (highlighted; Enter runs it),
//  - Or only in one field: one row per field that can match, worded with its match ("Borrower contains …"),
//  - Records you opened recently that match (the person's own Recently viewed list; no new server call).
// Arrow keys move, Enter picks, Escape closes. The box keeps the focus (combobox pattern).

export type QuickPick = { kind: 'everywhere' } | { kind: 'field'; field: Field } | { kind: 'recent'; recent: RecentRecord }

/** The matching text, marked. */
export function Mark({ text, needle }: { text: string; needle?: string }) {
  return <>{markParts(text, needle).map((p, i) => (p.mark ? <mark key={i} className="qs-mark">{p.text}</mark> : <span key={i}>{p.text}</span>))}</>
}

export function useQuickSearch({ text, fields, recent, onPick }: { text: string; fields: Field[]; recent: RecentRecord[] | null; onPick: (pick: QuickPick) => void }) {
  const listId = useId()
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const typed = text.trim()
  const ready = typed.length >= ANYWHERE_MIN
  const items = useMemo<QuickPick[]>(() => ready ? [
    { kind: 'everywhere' },
    ...fieldsFor(typed, fields).map((field) => ({ kind: 'field' as const, field })),
    ...recentMatches(typed, recent).map((r) => ({ kind: 'recent' as const, recent: r })),
  ] : [], [ready, typed, fields, recent])
  const shown = open && ready && items.length > 0
  const at = Math.min(active, Math.max(items.length - 1, 0))
  const optionId = (i: number) => `${listId}-o${i}`

  const pick = (i: number) => { const item = items[i]; if (!item) return; setOpen(false); onPick(item) }
  /** Call from the box's onChange: the list opens (again) with the first row, Search everywhere, highlighted. */
  const typing = (next: string) => { setOpen(next.trim().length >= ANYWHERE_MIN); setActive(0) }
  /** Returns true when the key was used by the list (the caller then does nothing else with it). */
  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>): boolean => {
    if (e.nativeEvent.isComposing || e.keyCode === 229) return false
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      if (!ready) return false
      e.preventDefault()
      if (!shown) { setOpen(true); setActive(e.key === 'ArrowDown' ? 0 : items.length - 1); return true }
      setActive((a) => (Math.min(a, items.length - 1) + (e.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length)
      return true
    }
    if (e.key === 'Enter' && shown) { e.preventDefault(); pick(at); return true }
    if (e.key === 'Escape' && shown) { e.preventDefault(); setOpen(false); return true }
    if (e.key === 'Tab') setOpen(false)
    return false
  }

  const inputProps = {
    role: 'combobox' as const,
    'aria-expanded': shown,
    'aria-controls': listId,
    'aria-autocomplete': 'list' as const,
    'aria-activedescendant': shown ? optionId(at) : undefined,
    onBlur: () => setOpen(false),
    onFocus: () => { if (ready) setOpen(true) },
  }

  const fieldRows = items.map((item, i) => ({ item, i })).filter((x) => x.item.kind === 'field')
  const recentRows = items.map((item, i) => ({ item, i })).filter((x) => x.item.kind === 'recent')
  const row = (i: number, body: ReactNode, className = '') => (
    <li key={i} id={optionId(i)} role="option" aria-selected={i === at} className={`qs-item ${className}`}
      // Keep the focus in the box: a click must not blur it before the pick.
      onMouseDown={(e) => e.preventDefault()} onMouseEnter={() => setActive(i)} onClick={() => pick(i)}>
      {body}
      {i === at && <span className="qs-enter" aria-hidden><CornerDownLeft size={13} />Enter</span>}
    </li>
  )
  const quoted = `“${typed}”`
  const list = shown ? (
    <div className="qs-list">
      <ul id={listId} role="listbox" aria-label="Search suggestions">
        {row(0, <><Search size={16} aria-hidden className="qs-icon" /><span><strong>Search everywhere</strong> for {quoted}</span></>, 'qs-everywhere')}
        {fieldRows.length > 0 && <li role="presentation" className="qs-head">Or only in one field</li>}
        {fieldRows.map(({ item, i }) => item.kind === 'field' && row(i, <span>{item.field.label} <span className="qs-word">{MODE_WORD[item.field.mode]}</span> {quoted}</span>))}
        {recentRows.length > 0 && <li role="presentation" className="qs-head">Records you opened recently</li>}
        {recentRows.map(({ item, i }) => item.kind === 'recent' && row(i, <>
          <span className="mono qs-num"><Mark text={item.recent.record.identifiers[0]?.value ?? '—'} needle={typed} /></span>
          <span className="qs-title truncate"><Mark text={item.recent.record.title || ''} needle={typed} /></span>
          <span className="faint qs-when">{fmtRel(item.recent.lastOpenedAt)}</span>
        </>, 'qs-recent'))}
      </ul>
    </div>
  ) : null

  return { inputProps, onKeyDown, typing, list, open: shown, close: () => setOpen(false) }
}
