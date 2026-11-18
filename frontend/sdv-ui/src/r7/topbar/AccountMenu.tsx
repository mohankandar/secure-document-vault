import { useEffect, useId, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react'
import { ChevronDown, ChevronUp, LogOut, UserRound } from 'lucide-react'
import './topbar.css'

// Product-neutral account menu: data in, events out. No SDV logic lives here, so LexaVault can reuse it.

export type ThemeChoice = 'light' | 'dark' | 'system'
export type AccountPerson = {
  /** Readable name. Callers supply a fallback; never pass a technical login ID. */
  name: string
  /** Up to two letters for the avatar; an icon is shown when absent. */
  initials?: string
  email?: string
  /** The role in plain words, e.g. "Business user". */
  roleLabel: string
  organization: string
  /** Shown after the organization ("Organization · Entity") when the product has entities. */
  entity?: string
}
export type AccountEntity = { id: string; label: string }

type Props = {
  person: AccountPerson
  theme: ThemeChoice
  onThemeChange: (theme: ThemeChoice) => void
  /** Entity slot. Two or more entities show a switch; one shows as plain text; none hides the section. */
  entities?: AccountEntity[]
  currentEntityId?: string
  onEntityChange?: (id: string) => void
  /** Hidden when absent (e.g. local test sign-in). */
  onSignOut?: () => void
  /** Extra section (e.g. development-only tools). Receives a function that closes the menu. */
  extraSection?: { title: string; render: (close: () => void) => ReactNode }
  /** Optional controlled open state, so another control (e.g. a phone bottom bar) can open the menu. */
  open?: boolean
  onOpenChange?: (open: boolean) => void
}

const THEMES: { value: ThemeChoice; label: string }[] = [{ value: 'light', label: 'Light' }, { value: 'dark', label: 'Dark' }, { value: 'system', label: 'System' }]
const STOP = '[data-menu-stop]:not([disabled])'

function Avatar({ initials, size }: { initials?: string; size: 'sm' | 'lg' }) {
  return <span className={`am-avatar am-avatar-${size}`} aria-hidden>{initials ? initials : <UserRound size={size === 'lg' ? 20 : 16} />}</span>
}

export function AccountMenu({ person, theme, onThemeChange, entities, currentEntityId, onEntityChange, onSignOut, extraSection, open: controlledOpen, onOpenChange }: Props) {
  const [uncontrolledOpen, setUncontrolledOpen] = useState(false)
  const open = controlledOpen ?? uncontrolledOpen
  const setOpen = (next: boolean) => { if (controlledOpen === undefined) setUncontrolledOpen(next); onOpenChange?.(next) }
  const rootRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const panelId = useId()
  const subtitle = person.entity ? `${person.organization} · ${person.entity}` : person.organization

  const close = (restoreFocus = true) => { setOpen(false); if (restoreFocus) buttonRef.current?.focus() }
  const stops = () => [...(panelRef.current?.querySelectorAll<HTMLElement>(STOP) ?? [])]

  // Focus the first item when the menu opens.
  useEffect(() => { if (open) stops()[0]?.focus() }, [open])

  // Close on a click outside.
  useEffect(() => {
    if (!open) return
    const onPointer = (e: PointerEvent) => { if (!rootRef.current?.contains(e.target as Node)) close(false) }
    document.addEventListener('pointerdown', onPointer)
    return () => document.removeEventListener('pointerdown', onPointer)
  })

  // Arrow keys move between items (Left/Right also work inside the theme switch); Enter or Space chooses.
  const onPanelKey = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    const target = e.target as HTMLElement
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); return }
    if (target.tagName === 'SELECT') return // a select uses the arrow keys itself
    const all = stops()
    const i = all.indexOf(target)
    const move = (to: number) => { e.preventDefault(); all[(to + all.length) % all.length]?.focus() }
    if (e.key === 'ArrowDown' || (e.key === 'ArrowRight' && target.dataset.themeChoice)) move(i + 1)
    else if (e.key === 'ArrowUp' || (e.key === 'ArrowLeft' && target.dataset.themeChoice)) move(i - 1)
    else if (e.key === 'Home') move(0)
    else if (e.key === 'End') move(all.length - 1)
  }

  return (
    <div className="am" ref={rootRef}
      onBlur={(e) => { if (open && e.relatedTarget && !rootRef.current?.contains(e.relatedTarget as Node)) setOpen(false) }}>
      <button ref={buttonRef} type="button" className="am-button" aria-haspopup="dialog" aria-expanded={open} aria-controls={open ? panelId : undefined}
        aria-label={`Account: ${person.name}, ${person.roleLabel}`}
        onClick={() => setOpen(!open)}
        onKeyDown={(e) => { if (e.key === 'ArrowDown' && !open) { e.preventDefault(); setOpen(true) } if (e.key === 'Escape' && open) { e.preventDefault(); close() } }}>
        <Avatar initials={person.initials} size="sm" />
        {/* Long values end in "…"; the full text shows on hover (title) and in the open menu. */}
        <span className="am-who"><strong title={person.name}>{person.name}</strong><span title={subtitle}>{subtitle}</span></span>
        {open ? <ChevronUp size={16} aria-hidden className="am-chev" /> : <ChevronDown size={16} aria-hidden className="am-chev" />}
      </button>

      {open && (
        <div ref={panelRef} id={panelId} className="am-panel" role="dialog" aria-modal="false" aria-label="Account menu" onKeyDown={onPanelKey}>
          <div className="am-person">
            <Avatar initials={person.initials} size="lg" />
            <div className="am-person-text">
              <strong>{person.name}</strong>
              {person.email && person.email !== person.name && <span>{person.email}</span>}
              <span>{person.roleLabel}</span>
              <span>{subtitle}</span>
            </div>
          </div>

          {entities && entities.length > 0 && (
            <section className="am-section" aria-label="Entity">
              <div className="am-eyebrow">{person.organization} · Entity</div>
              {entities.length === 1 ? <p className="am-plain">{entities[0].label}</p> : (
                <>
                  <div role="radiogroup" aria-label="Entity">
                    {entities.map((ent) => (
                      <button key={ent.id} type="button" role="radio" aria-checked={ent.id === currentEntityId} className="am-item am-radio" data-menu-stop=""
                        onClick={() => { onEntityChange?.(ent.id); close() }}>
                        <span className="am-dot" aria-hidden />{ent.label}
                      </button>
                    ))}
                  </div>
                  <p className="am-note">Switching shows only that entity&apos;s records.</p>
                </>
              )}
            </section>
          )}

          <section className="am-section" aria-labelledby={`${panelId}-theme`}>
            <div className="am-eyebrow" id={`${panelId}-theme`}>Theme</div>
            <div className="am-seg" role="group" aria-labelledby={`${panelId}-theme`}>
              {THEMES.map((t) => (
                <button key={t.value} type="button" aria-pressed={t.value === theme} data-theme-choice={t.value} data-menu-stop=""
                  onClick={() => { onThemeChange(t.value); close() }}>{t.label}</button>
              ))}
            </div>
          </section>

          {extraSection && (
            <section className="am-section" aria-label={extraSection.title}>
              <div className="am-eyebrow">{extraSection.title}</div>
              {extraSection.render(() => close())}
            </section>
          )}

          {onSignOut && (
            <section className="am-section am-last">
              <button type="button" className="am-item" data-menu-stop="" onClick={() => { close(false); onSignOut() }}><LogOut size={16} aria-hidden />Sign out</button>
            </section>
          )}
        </div>
      )}
    </div>
  )
}
