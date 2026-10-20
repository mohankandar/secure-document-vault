import { ChevronRight } from 'lucide-react'
type Item<T extends string> = { id: T; label: string; attention?: boolean }
const descriptions: Record<string, string> = {
  'Business administration sections': 'Manage access, document governance, retention and searchable business metadata.',
  'Tenant IT administration sections': 'Manage storage, enterprise authentication and source integrations.',
  'Platform operations sections': 'Observe processing, storage health, incidents and safe audit evidence.',
}
/** R7 navigation for live SDV sections. The parent owns all real state, capability
 * enforcement, form values and API mutations; this component only renders navigation. */
export function LiveSectionTabs<T extends string>({ label, value, onChange, tabs }: {
  label: string
  value: T
  onChange: (value: T) => void
  tabs: Item<T>[]
}) {
  const current = tabs.find(t => t.id === value)
  return <section className="v7-live-sections" aria-label={label}>
    <div className="v7-live-sections-intro">
      <div className="v7-live-sections-eyebrow">{label.replace(' sections', '')}</div>
      <div className="v7-live-sections-title">{current?.label ?? 'Overview'}</div>
      <p>{descriptions[label] ?? 'Select a section to continue.'}</p>
    </div>
    <nav className="v7-live-sectionnav" aria-label={label}>
      {tabs.map(t => <button type="button" key={t.id} className={t.id === value ? 'active' : ''}
        aria-current={t.id === value ? 'page' : undefined} onClick={() => onChange(t.id)}>
        <span>{t.label}</span>{t.attention && <span className="v7-live-flag" aria-label="Needs attention" />}
        {t.id === value && <ChevronRight size={14} aria-hidden />}
      </button>)}
    </nav>
  </section>
}
