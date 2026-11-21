import { useEffect, useId, type ReactNode } from 'react'
import { AlertTriangle, CheckCircle2, ChevronRight, FlaskConical, Info, X, XCircle, Inbox, RefreshCw } from 'lucide-react'
import { useStore } from './store'

/** Fixed English short months: the browser's en-GB says "Sept", the canvas says "Sep". */
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
/** A yyyy-mm-dd value is a calendar day (no time zone); anything else is a moment. */
const toDate = (v: string) => (/^\d{4}-\d{2}-\d{2}$/.test(v) ? new Date(v + 'T00:00:00') : new Date(v))
/** "30 Sep 2026". */
export const fmtDate = (v?: string | null) => {
  if (!v) return '—'
  const d = toDate(v)
  return Number.isNaN(d.getTime()) ? '—' : `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`
}
export const fmtDateTime = (v?: string | null) => (v ? new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(v)) : '—')
/** Allowed browser clock lag behind the server before a future time is shown as a date instead of "just now". */
const CLOCK_SKEW_MS = 5 * 60 * 1000
export const fmtRel = (v: string, now: number = Date.now()) => {
  const t = new Date(v).getTime()
  if (Number.isNaN(t)) return '—'
  const diff = now - t
  // Slightly in the future (server clock ahead of the browser) reads as "just now"; further ahead shows the date.
  if (diff < -CLOCK_SKEW_MS) return fmtDate(v)
  // The canvas's wording (task 0018): "12 minutes ago", "2 hours ago", "Yesterday", "Mon 16 Nov", "2 Nov".
  const s = Math.max(0, diff / 1000)
  if (s < 60) return 'just now'
  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'} ago`
  if (s < 3600) return plural(Math.floor(s / 60), 'minute')
  const day = (x: number) => { const d = new Date(x); d.setHours(0, 0, 0, 0); return d.getTime() }
  const days = Math.round((day(now) - day(t)) / 86400000)
  if (days <= 0) return plural(Math.floor(s / 3600), 'hour')
  if (days === 1) return 'Yesterday'
  if (days < 7) { const d = new Date(t); return `${WEEKDAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]}` }
  return fmtShortDate(v)
}
/** "17 Oct" this year, "17 Oct 2025" another year (the canvas's dates). */
export const fmtShortDate = (v?: string | null, now: number = Date.now()) => {
  if (!v) return '—'
  const d = toDate(v)
  if (Number.isNaN(d.getTime())) return '—'
  const sameYear = d.getFullYear() === new Date(now).getFullYear()
  return `${d.getDate()} ${MONTHS[d.getMonth()]}${sameYear ? '' : ' ' + d.getFullYear()}`
}
export const humanize = (code: string) => code.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase())

/**
 * Plain-words label for an audit event. A page event says "loaded", not "viewed": a page fetched ahead of time may
 * not have been looked at (the action code stays DOCUMENT_PAGE_VIEWED so earlier records still match).
 */
export function auditEventLabel(action: string, safeDetailsJson?: string | null, filename?: string, stripDocumentPrefix = false): string {
  if (action === 'DOCUMENT_PAGE_VIEWED') {
    let page: unknown
    try { page = safeDetailsJson ? (JSON.parse(safeDetailsJson) as { page?: unknown }).page : undefined } catch { page = undefined }
    if (typeof page === 'number') return filename ? `Loaded page ${page} of ${filename}` : `Loaded page ${page}`
    return 'Page loaded'
  }
  return humanize(stripDocumentPrefix ? action.replace(/^DOCUMENT_/, '') : action)
}
export const toneFor = (v: string): 'ok' | 'warn' | 'bad' | '' => {
  if (['UP', 'ACTIVE', 'SUCCESS', 'COMPLETE', 'RECEIVED', 'PASSED'].includes(v)) return 'ok'
  if (['DEGRADED', 'READ_ONLY', 'RECEIVING', 'OPEN', 'DRAFT', 'INCOMPLETE', 'EXPECTED'].includes(v)) return 'warn'
  if (['DOWN', 'FAILED', 'DENIED', 'DISABLED'].includes(v)) return 'bad'
  return ''
}

export function Status({ value, label }: { value: string; label?: string }) {
  return <span className={`v-status ${toneFor(value)}`}>{label ?? humanize(value)}</span>
}
export function Badge({ tone = '', children }: { tone?: 'ok' | 'warn' | 'bad' | 'outline' | ''; children: ReactNode }) {
  return <span className={`v-badge ${tone}`}>{children}</span>
}

export function Panel({ title, description, meta, actions, children, flush, id }: { title?: ReactNode; description?: ReactNode; meta?: ReactNode; actions?: ReactNode; children: ReactNode; flush?: boolean; id?: string }) {
  return (
    <section className={`v-panel${flush ? ' flush' : ''}`} aria-labelledby={title ? id : undefined}>
      {title && (
        <header className="v-panel-head">
          <div className="grow">
            <div className="row"><h2 id={id}>{title}</h2>{meta != null && <span className="meta">{meta}</span>}</div>
            {description && <p>{description}</p>}
          </div>
          {actions && <div className="row">{actions}</div>}
        </header>
      )}
      <div className="v-panel-body">{children}</div>
    </section>
  )
}

export function Callout({ tone = '', title, children }: { tone?: 'warn' | 'bad' | 'ok' | ''; title: ReactNode; children?: ReactNode }) {
  const IconC = tone === 'bad' ? XCircle : tone === 'warn' ? AlertTriangle : tone === 'ok' ? CheckCircle2 : Info
  return (
    <div className={`v-callout ${tone}`} role={tone === 'bad' ? 'alert' : undefined}>
      <IconC size={18} aria-hidden />
      <div><strong>{title}</strong>{children && <p>{children}</p>}</div>
    </div>
  )
}

export function Empty({ title, children, icon, action }: { title: string; children?: ReactNode; icon?: ReactNode; action?: ReactNode }) {
  return (
    <div className="v-empty">
      <div className="glyph" aria-hidden>{icon ?? <Inbox size={20} />}</div>
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action && <div style={{ marginTop: 8 }}>{action}</div>}
    </div>
  )
}

export function Skeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div className="stack" style={{ padding: 16 }} aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => <span key={i} className="v-skel" style={{ width: `${92 - ((i * 17) % 40)}%` }} />)}
    </div>
  )
}

export function ErrorState({ what, onRetry }: { what: string; onRetry?: () => void }) {
  return (
    <Empty title={`Could not load ${what}`} icon={<XCircle size={20} />} action={<button type="button" className="v-btn sm" onClick={onRetry}><RefreshCw size={14} aria-hidden />Retry</button>}>
      Vero couldn&apos;t load this just now. Nothing was changed.
    </Empty>
  )
}

export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: string; disabled?: boolean }) {
  return <button type="button" role="switch" aria-checked={checked} aria-label={label} className="v-switch" disabled={disabled} onClick={() => onChange(!checked)} />
}

export function Seg<T extends string>({ value, options, onChange, label }: { value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; label: string }) {
  return (
    <div className="v-seg" role="group" aria-label={label}>
      {options.map((o) => <button key={o.value} type="button" aria-pressed={o.value === value} onClick={() => onChange(o.value)}>{o.label}</button>)}
    </div>
  )
}

/** count (task 0030): an amber pill after the label, e.g. the requests waiting ("Approvals 48"); countLabel is its spoken form. */
export function Tabs({ tabs, value, onChange, label }: { tabs: { id: string; label: string; attention?: boolean; count?: number; countLabel?: string }[]; value: string; onChange: (id: string) => void; label: string }) {
  return (
    <div className="v-tabs" role="tablist" aria-label={label}>
      {tabs.map((t) => (
        <button key={t.id} type="button" role="tab" className="v-tab" aria-selected={t.id === value} onClick={() => onChange(t.id)}>
          {t.label}{t.attention && <span className="dot" aria-label="needs attention" />}
          {t.count != null && t.count > 0 && <span className="v-tab-n" aria-label={t.countLabel ?? String(t.count)}>{t.count}</span>}
        </button>
      ))}
    </div>
  )
}

function useEscape(onClose: () => void) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', k)
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { window.removeEventListener('keydown', k); document.body.style.overflow = prev }
  }, [onClose])
}

/**
 * A side panel. On wide screens it sits beside the page, which moves aside for it (canvas: the page stays readable);
 * on phones it is a sheet from the bottom. wide: the canvas's wider panel (Add a sending system).
 */
export function Drawer({ title, eyebrow, onClose, children, footer, wide }: { title: string; eyebrow?: string; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  useEscape(onClose)
  const id = useId()
  // A narrow panel moves the page aside; the wide one lies over it without reflowing it (task 0020, item 3).
  useEffect(() => {
    if (wide) return
    document.documentElement.classList.add('r7-side-open')
    return () => document.documentElement.classList.remove('r7-side-open')
  }, [wide])
  return (
    <>
      <div className="v-scrim" onClick={onClose} aria-hidden />
      <aside className={`v-drawer${wide ? ' wide' : ''}`} role="dialog" aria-modal="true" aria-labelledby={id}>
        <header className="v-drawer-head">
          <div className="grow">{eyebrow && <div className="eyebrow">{eyebrow}</div>}<h2 id={id}>{title}</h2></div>
          <button type="button" className="v-btn quiet icon sm" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </header>
        <div className="v-drawer-body">{children}</div>
        {footer && <footer className="v-drawer-foot">{footer}</footer>}
      </aside>
    </>
  )
}

export function Dialog({ title, onClose, children, footer }: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEscape(onClose)
  const id = useId()
  return (
    <>
      <div className="v-scrim v-dialog-scrim" onClick={onClose} aria-hidden />
      <div className="v-dialog" role="alertdialog" aria-modal="true" aria-labelledby={id}>
        <header className="v-drawer-head">
          <h2 id={id} className="grow">{title}</h2>
          <button type="button" className="v-btn quiet icon sm" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </header>
        <div className="v-drawer-body">{children}</div>
        {footer && <footer className="v-drawer-foot">{footer}</footer>}
      </div>
    </>
  )
}

export function Toasts() {
  const { toasts, dismissToast } = useStore()
  return (
    <div className="v-toasts" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className="v-toast" role="status">
          {t.tone === 'bad' ? <XCircle size={18} aria-hidden /> : t.tone === 'ok' ? <CheckCircle2 size={18} aria-hidden /> : <FlaskConical size={18} aria-hidden />}
          <div className="grow"><strong>{t.title}</strong>{t.detail && <p>{t.detail}</p>}</div>
          <button type="button" className="v-btn quiet icon sm" style={{ color: 'inherit' }} onClick={() => dismissToast(t.id)} aria-label="Dismiss"><X size={16} /></button>
        </div>
      ))}
    </div>
  )
}

export function Meter({ pct, warn, stop, label }: { pct: number; warn: number; stop: number; label: string }) {
  const tone = pct >= stop ? 'bad' : pct >= warn ? 'warn' : ''
  return (
    <div className={`v-meter ${tone}`} role="meter" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label={label}>
      <i style={{ width: `${pct}%` }} />
      <b style={{ left: `${warn}%` }} title={`Warning ${warn}%`} />
      <b style={{ left: `${stop}%` }} title={`Stop-write ${stop}%`} />
    </div>
  )
}

/** sub (task 0030): a short line beside the title instead of below it ("48 waiting · oldest 2 days"). */
export function PageHead({ crumbs, title, lede, actions, sub }: { crumbs: string[]; title: string; lede?: ReactNode; actions?: ReactNode; sub?: ReactNode }) {
  return (
    <header className="v-pagehead">
      <div className="titles">
        <div className="crumbs">{crumbs.map((c, i) => <span key={c} className="row" style={{ gap: 6 }}>{i > 0 && <span aria-hidden>/</span>}{c}</span>)}</div>
        {sub ? <div className="v-pagehead-line"><h1>{title}</h1><span className="v-pagehead-sub">{sub}</span></div> : <h1>{title}</h1>}
        {lede && <p className="lede">{lede}</p>}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </header>
  )
}

/** Who did something, in words, from an audit actor type: the technical sign-in ID is never shown (task 0018). */
export const actorWords = (actorType: string) => (actorType === 'SOURCE' ? 'Sending system' : actorType === 'SYSTEM' ? 'Vero' : 'A person')

/**
 * "Technical details" (canvas): a folded row that names what is inside ("folder paths, thresholds, …").
 * On a page it is its own card; inside a panel or card (flat) it is a strip with a line above it.
 */
export function TechDetails({ hint, flat, children }: { hint?: string; flat?: boolean; children: ReactNode }) {
  return (
    <details className={`v-tech${flat ? ' flat' : ''}`}>
      <summary><ChevronRight size={16} aria-hidden className="v-tech-chev" /><b>Technical details</b>{hint && <span className="faint">{hint}</span>}</summary>
      <div className="v-tech-body">{children}</div>
    </details>
  )
}

/** A canvas banner: bold words first, then the sentence, on one line where it fits ("**What to do:** …"). */
export function Banner({ tone = 'warn', lead, children }: { tone?: 'warn' | 'bad' | 'ok' | 'info'; lead: ReactNode; children?: ReactNode }) {
  return <div className={`v-banner ${tone}`} role={tone === 'bad' ? 'alert' : undefined}><b>{lead}</b>{children && <> {children}</>}</div>
}

/** Task 0027 (0.4): a sensitivity level in words, for admin views that describe a document without its file name. */
export function levelWords(level: string | null | undefined): string {
  if (level === 'STANDARD') return 'Standard'
  if (level === 'CONFIDENTIAL') return 'Confidential'
  if (level === 'HIGHLY_CONFIDENTIAL') return 'Highly Confidential'
  return 'Not known'
}
