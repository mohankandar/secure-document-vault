import { useEffect, useId, type ReactNode } from 'react'
import { AlertTriangle, CheckCircle2, FlaskConical, Info, X, XCircle, Inbox, RefreshCw } from 'lucide-react'
import { useStore } from './store'
import { NOW } from './data'

export const fmtDate = (v?: string | null) => (v ? new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(v)) : '—')
export const fmtDateTime = (v?: string | null) => (v ? new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(v)) : '—')
export const fmtRel = (v: string) => {
  const s = Math.max(0, (NOW - new Date(v).getTime()) / 1000)
  if (s < 90) return 'just now'
  if (s < 3600) return `${Math.round(s / 60)} min ago`
  if (s < 86400) return `${Math.round(s / 3600)} h ago`
  if (s < 86400 * 45) return `${Math.round(s / 86400)} d ago`
  return fmtDate(v)
}
export const humanize = (code: string) => code.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase())
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

export function Callout({ tone = '', title, children }: { tone?: 'warn' | 'bad' | 'ok' | 'sim' | ''; title: ReactNode; children?: ReactNode }) {
  const IconC = tone === 'bad' ? XCircle : tone === 'warn' ? AlertTriangle : tone === 'ok' ? CheckCircle2 : tone === 'sim' ? FlaskConical : Info
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

export function ErrorState({ what }: { what: string }) {
  const { setScenario } = useStore()
  return (
    <Empty title={`Could not load ${what}`} icon={<XCircle size={20} />} action={<button type="button" className="v-btn sm" onClick={() => setScenario('live')}><RefreshCw size={14} aria-hidden />Retry</button>}>
      The service returned an error. Nothing was changed. Correlation ID <span className="mono">sim-err-4f10</span>.
    </Empty>
  )
}

/** Renders scenario-driven loading / empty / error states around live content. */
export function Scenario({ what, children, empty, rows }: { what: string; children: ReactNode; empty?: ReactNode; rows?: number }) {
  const { scenario } = useStore()
  if (scenario === 'loading') return <Skeleton rows={rows} />
  if (scenario === 'error') return <ErrorState what={what} />
  if (scenario === 'empty') return <>{empty ?? <Empty title={`No ${what} yet`} />}</>
  return <>{children}</>
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

export function Tabs({ tabs, value, onChange, label }: { tabs: { id: string; label: string; attention?: boolean }[]; value: string; onChange: (id: string) => void; label: string }) {
  return (
    <div className="v-tabs" role="tablist" aria-label={label}>
      {tabs.map((t) => (
        <button key={t.id} type="button" role="tab" className="v-tab" aria-selected={t.id === value} onClick={() => onChange(t.id)}>
          {t.label}{t.attention && <span className="dot" aria-label="needs attention" />}
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

export function Drawer({ title, eyebrow, onClose, children, footer }: { title: string; eyebrow?: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEscape(onClose)
  const id = useId()
  return (
    <>
      <div className="v-scrim" onClick={onClose} aria-hidden />
      <aside className="v-drawer" role="dialog" aria-modal="true" aria-labelledby={id}>
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

export function PageHead({ crumbs, title, lede, actions }: { crumbs: string[]; title: string; lede?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="v-pagehead">
      <div className="titles">
        <div className="crumbs">{crumbs.map((c, i) => <span key={c} className="row" style={{ gap: 6 }}>{i > 0 && <span aria-hidden>/</span>}{c}</span>)}</div>
        <h1>{title}</h1>
        {lede && <p className="lede">{lede}</p>}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </header>
  )
}
