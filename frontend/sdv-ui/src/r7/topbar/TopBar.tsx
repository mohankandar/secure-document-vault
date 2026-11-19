import type { ReactNode } from 'react'
import { Search } from 'lucide-react'
import './topbar.css'

// Product-neutral top bar: data in, events out. No SDV logic lives here, so LexaVault can reuse it.

export type TopBarTab = { id: string; label: string; /** e.g. items needing attention */ count?: number; countLabel?: string }

type Props = {
  productName: string
  tabs: TopBarTab[]
  currentTabId?: string
  onSelectTab: (id: string) => void
  /** "Go to…" command box; hidden when absent. */
  onOpenCommand?: () => void
  commandLabel?: string
  commandShortcut?: string
  /** Right-hand side, normally the AccountMenu. */
  account: ReactNode
  /** Development-only content placed before the account button. */
  extra?: ReactNode
}

export function TopBar({ productName, tabs, currentTabId, onSelectTab, onOpenCommand, commandLabel = 'Go to…', commandShortcut = 'Ctrl K', account, extra }: Props) {
  return (
    <header className="tb">
      <a className="tb-brand" href="#/" aria-label={`${productName} home`} onClick={(e) => { e.preventDefault(); if (tabs[0]) onSelectTab(tabs[0].id) }}>{productName}<i aria-hidden /></a>
      {tabs.length > 0 && (
        <nav className="tb-tabs" aria-label="Areas">
          {tabs.map((t) => (
            <button key={t.id} type="button" className="tb-tab" aria-current={t.id === currentTabId ? 'page' : undefined} onClick={() => onSelectTab(t.id)}>
              {t.label}
              {t.count != null && t.count > 0 && <span className="tb-count" aria-label={t.countLabel ?? `${t.count} need attention`}>{t.count}</span>}
            </button>
          ))}
        </nav>
      )}
      <div className="tb-grow" />
      {onOpenCommand && (
        <button type="button" className="tb-command" onClick={onOpenCommand} aria-label="Go to page or record" aria-keyshortcuts="Control+K Meta+K">
          <Search size={16} aria-hidden /><span>{commandLabel}</span><kbd>{commandShortcut}</kbd>
        </button>
      )}
      {extra}
      {account}
    </header>
  )
}
