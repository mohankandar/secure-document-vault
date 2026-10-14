import type { ReactNode } from 'react'

export function humanize(value: string | null | undefined): string {
  if (!value) return '—'
  if (!/^[A-Z0-9_]+$/.test(value)) return value
  const words = value.toLowerCase().split('_').filter(Boolean).join(' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

export type StatusTone = 'ok' | 'warn' | 'danger' | 'neutral'

export function statusTone(value: string | null | undefined): StatusTone {
  const key = (value ?? '').toUpperCase()
  if (['UP', 'HEALTHY', 'SUCCESS', 'ACTIVE', 'PASSED', 'VALIDATED', 'COMPLETE', 'COMPLETED'].includes(key)) return 'ok'
  if (['DEGRADED', 'WARNING', 'READ_ONLY', 'INCOMPLETE', 'PROCESSING', 'PARTIAL', 'STAGED', 'DRAFT'].includes(key)) return 'warn'
  if (['DOWN', 'FAILED', 'FAILURE', 'CRITICAL', 'OFFLINE', 'ERROR', 'DENIED', 'UNHEALTHY', 'UNAVAILABLE'].includes(key)) return 'danger'
  return 'neutral'
}

export function StatusPill({ value, label, tone }: { value: string | null | undefined; label?: string; tone?: StatusTone }) {
  return <span className={`status-pill status-pill-${tone ?? statusTone(value)}`}><span className="status-pill-dot" aria-hidden="true" />{label ?? humanize(value)}</span>
}

export type AdminTab<T extends string> = { id: T; label: string; attention?: boolean }

export function AdminTabs<T extends string>({ label, tabs, value, onChange }: { label: string; tabs: AdminTab<T>[]; value: T; onChange: (next: T) => void }) {
  return (
    <nav className="ax-tabs" aria-label={label}>
      {tabs.map((tab) => (
        <button key={tab.id} type="button" className={value === tab.id ? 'active' : ''} aria-current={value === tab.id ? 'page' : undefined} onClick={() => onChange(tab.id)}>
          {tab.label}
          {tab.attention && <span className="ax-tab-flag" aria-hidden="true" />}
        </button>
      ))}
    </nav>
  )
}

export function AdminSection({ title, description, meta, actions, children, className = '', flush = false }: {
  title: ReactNode
  description?: ReactNode
  meta?: ReactNode
  actions?: ReactNode
  children?: ReactNode
  className?: string
  flush?: boolean
}) {
  return (
    <section className={`ax-section ${flush ? 'ax-section-flush' : ''} ${className}`}>
      <header className="ax-section-head">
        <div className="ax-section-title">
          <h2>{title}{meta != null && <span className="ax-count">{meta}</span>}</h2>
          {description && <p>{description}</p>}
        </div>
        {actions && <div className="ax-section-actions">{actions}</div>}
      </header>
      {children != null && <div className="ax-section-body">{children}</div>}
    </section>
  )
}

export type SheetItem = { label: string; value: ReactNode; mono?: boolean; wide?: boolean }

export function StateSheet({ items, columns = 3 }: { items: SheetItem[]; columns?: 2 | 3 | 4 }) {
  return (
    <dl className={`ax-sheet ax-sheet-${columns}`}>
      {items.map((item) => (
        <div key={item.label} className={item.wide ? 'ax-sheet-wide' : ''}>
          <dt>{item.label}</dt>
          <dd className={item.mono ? 'mono' : ''}>{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return <div className="ax-empty"><strong>{title}</strong>{children && <span>{children}</span>}</div>
}

export function CapacityMeter({ usedPercent, warningPercent, stopWritePercent }: { usedPercent: number | null; warningPercent: number; stopWritePercent: number }) {
  if (usedPercent == null) return <span className="ax-muted">Unavailable</span>
  const tone = usedPercent >= stopWritePercent ? 'danger' : usedPercent >= warningPercent ? 'warn' : 'ok'
  return (
    <div className={`ax-meter ax-meter-${tone}`}>
      <div className="ax-meter-track" role="img" aria-label={`${usedPercent}% used. Warning at ${warningPercent}%, writes stop at ${stopWritePercent}%.`}>
        <span className="ax-meter-fill" style={{ width: `${Math.min(100, Math.max(0, usedPercent))}%` }} />
        <span className="ax-meter-mark" style={{ left: `${warningPercent}%` }} />
        <span className="ax-meter-mark ax-meter-mark-stop" style={{ left: `${stopWritePercent}%` }} />
      </div>
      <span className="ax-meter-value">{usedPercent}%</span>
    </div>
  )
}

export function AttentionRow({ tone, title, detail, count, onOpen, openLabel }: {
  tone: StatusTone
  title: string
  detail: ReactNode
  count: ReactNode
  onOpen?: () => void
  openLabel?: string
}) {
  return (
    <div className={`ax-attention ax-attention-${tone}`}>
      <span className="ax-attention-count">{count}</span>
      <span className="ax-attention-copy"><strong>{title}</strong><small>{detail}</small></span>
      {onOpen && <button type="button" className="ax-link-button" onClick={onOpen}>{openLabel ?? 'Open'}</button>}
    </div>
  )
}
