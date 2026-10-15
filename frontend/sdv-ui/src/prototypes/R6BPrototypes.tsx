import { useEffect, useRef, useState, type ReactNode } from 'react'
import Icon from '../Icon'
import './r6b-prototypes.css'

/*
 * R6-B review prototypes. Static sample data only: this screen makes no API calls,
 * writes no audit events and is not reachable from the production workflow.
 * Open with ?prototype=r6b.
 */

type Concept = 'workspace' | 'expanded' | 'actions'
type FitMode = 'page' | 'width' | 'manual'
type InfoTab = 'details' | 'comments' | 'tags'
type Permissions = 'steward' | 'user'

// R5 PdfViewer opens in fit-to-width (fitScale is derived from canvas width only, capped at 1280px).
const DEFAULT_FIT: FitMode = 'width'
const PAGE_WIDTH = 816
const PAGE_HEIGHT = 1056
const FIT_WIDTH_CAP = 1280

const records = [
  { contract: '100245', member: '88123401', entity: 'Northwind Health Plan', last: 'Alvarez', region: 'West', updated: 'Oct 13, 2026' },
  { contract: '100246', member: '88123402', entity: 'Northwind Health Plan', last: 'Brennan', region: 'West', updated: 'Oct 9, 2026' },
  { contract: '100251', member: '88123477', entity: 'Contoso Benefits', last: 'Chen', region: 'Central', updated: 'Oct 4, 2026' },
  { contract: '100262', member: '88123519', entity: 'Contoso Benefits', last: 'Daniels', region: 'East', updated: 'Sep 28, 2026' },
]
const documents = [
  { name: 'Member Agreement 2026.pdf', pages: 6, type: 'Agreement', received: 'Oct 13, 2026', size: '412 KB' },
  { name: 'Enrollment Form.pdf', pages: 1, type: 'Form', received: 'Oct 2, 2026', size: '96 KB' },
  { name: 'Rider Amendment.pdf', pages: 3, type: 'Amendment', received: 'Sep 30, 2026', size: '188 KB' },
]

function useElementSize<T extends HTMLElement>() {
  const ref = useRef<T | null>(null)
  const [size, setSize] = useState({ width: 0, height: 0 })
  useEffect(() => {
    const node = ref.current
    if (!node) return
    const observer = new ResizeObserver(([entry]) => setSize({ width: entry.contentRect.width, height: entry.contentRect.height }))
    observer.observe(node)
    return () => observer.disconnect()
  }, [])
  return [ref, size] as const
}

function PageSheet({ page, title, width, compact = false, redacting = false }: { page: number; title: string; width: number; compact?: boolean; redacting?: boolean }) {
  return (
    <div className={`r6b-sheet ${compact ? 'is-compact' : ''}`} style={{ width, height: (width * PAGE_HEIGHT) / PAGE_WIDTH, padding: `${width * (compact ? 0.1 : 0.09)}px ${width * (compact ? 0.1 : 0.08)}px` }} aria-hidden={compact || undefined}>
      <div className="r6b-sheet-title">{page === 1 ? title.replace('.pdf', '').toUpperCase() : `Section ${page}`}</div>
      <div className="r6b-sheet-sub">Contract 100245 · Page {page}</div>
      {Array.from({ length: compact ? 7 : 18 }, (_, i) => <span key={i} className="r6b-line" style={{ width: `${58 + ((i * 17 + page * 7) % 38)}%` }} />)}
      {redacting && !compact && <span className="r6b-redaction-box" style={{ top: '27%', left: '7%', width: '52%', height: '4%' }}><span>Draft redaction</span></span>}
    </div>
  )
}

function InfoPanel({ tab, onTab, onClose, permissions, docIndex }: { tab: InfoTab; onTab: (tab: InfoTab) => void; onClose: () => void; permissions: Permissions; docIndex: number }) {
  const canTag = permissions === 'steward'
  const doc = documents[docIndex]
  const tabs: { id: InfoTab; label: string; count?: number }[] = [
    { id: 'details', label: 'Details' },
    { id: 'comments', label: 'Comments', count: 1 },
    ...(canTag ? [{ id: 'tags' as InfoTab, label: 'Tags', count: 1 }] : []),
  ]
  return (
    <aside className="r6b-info" aria-label="Document information">
      <header className="r6b-info-head">
        <div className="r6b-info-tabs" role="tablist" aria-label="Document information">
          {tabs.map((t) => (
            <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} onClick={() => onTab(t.id)}>
              {t.label}{t.count ? <span className="r6b-count">{t.count}</span> : null}
            </button>
          ))}
        </div>
        <button type="button" className="r6b-icon-btn" aria-label="Close document information" onClick={onClose}><Icon name="close" /></button>
      </header>
      <div className="r6b-info-body" role="tabpanel">
        {tab === 'details' && (
          <dl className="r6b-dl">
            <div><dt>File</dt><dd>{doc.name}</dd></div>
            <div><dt>Document type</dt><dd>{doc.type}</dd></div>
            <div><dt>Pages</dt><dd className="r6b-tabular">{doc.pages}</dd></div>
            <div><dt>Received</dt><dd>{doc.received}</dd></div>
            <div><dt>Size</dt><dd className="r6b-tabular">{doc.size}</dd></div>
          </dl>
        )}
        {tab === 'comments' && (
          <div className="r6b-comments">
            <article className="r6b-comment">
              <header><strong>j.ortiz</strong><span>Oct 13, 2026</span></header>
              <p>Signature page confirmed against the enrollment form.</p>
            </article>
            <label className="r6b-field">
              <span>Add a comment</span>
              <textarea rows={3} placeholder="Visible to users who can view this document" />
            </label>
            <button type="button" className="r6b-btn" disabled>Add comment</button>
          </div>
        )}
        {tab === 'tags' && canTag && (
          <div className="r6b-comments">
            <ul className="r6b-tags"><li>Verified</li></ul>
            <label className="r6b-field"><span>Add a tag</span><input placeholder="Tag name" /></label>
            <button type="button" className="r6b-btn" disabled>Add tag</button>
          </div>
        )}
      </div>
    </aside>
  )
}

function ToolButton({ label, icon, pressed, onClick, disabled, children, showLabel = false }: { label: string; icon: Parameters<typeof Icon>[0]['name']; pressed?: boolean; onClick?: () => void; disabled?: boolean; children?: ReactNode; showLabel?: boolean }) {
  return (
    <button type="button" className={`r6b-tool ${showLabel ? 'has-label' : ''}`} aria-label={showLabel ? undefined : label} title={label} aria-pressed={pressed} onClick={onClick} disabled={disabled}>
      <Icon name={icon} />
      {showLabel && <span>{label}</span>}
      {children}
    </button>
  )
}

function Viewer({ permissions, expanded, onExpand, initialInfo = null, initialThumbs = false }: { permissions: Permissions; expanded: boolean; onExpand: (expanded: boolean) => void; initialInfo?: InfoTab | null; initialThumbs?: boolean }) {
  const [docIndex, setDocIndex] = useState(0)
  const [page, setPage] = useState(1)
  const [pageInput, setPageInput] = useState('1')
  const [fit, setFit] = useState<FitMode>(DEFAULT_FIT)
  const [zoom, setZoom] = useState(1)
  const [thumbs, setThumbs] = useState(initialThumbs)
  const [info, setInfo] = useState<InfoTab | null>(initialInfo)
  const [redacting, setRedacting] = useState(false)
  const [findOpen, setFindOpen] = useState(false)
  const [canvasRef, canvas] = useElementSize<HTMLDivElement>()
  const doc = documents[docIndex]
  const multipage = doc.pages > 1
  const canDownload = permissions === 'steward'
  const canRedact = permissions === 'steward'

  const fitWidthPx = Math.max(240, Math.min(canvas.width - 48, FIT_WIDTH_CAP))
  const fitPagePx = Math.max(240, Math.min(((canvas.height - 32) * PAGE_WIDTH) / PAGE_HEIGHT, canvas.width - 48))
  const sheetWidth = fit === 'page' ? fitPagePx : fit === 'width' ? fitWidthPx : PAGE_WIDTH * zoom
  const percent = Math.round((sheetWidth / PAGE_WIDTH) * 100)

  const goTo = (next: number) => { const clamped = Math.min(Math.max(next, 1), doc.pages); setPage(clamped); setPageInput(String(clamped)) }
  const stepZoom = (delta: number) => { setFit('manual'); setZoom(Math.min(2.5, Math.max(0.5, Math.round((sheetWidth / PAGE_WIDTH + delta) * 4) / 4))) }
  const selectDoc = (index: number) => { setDocIndex(index); setPage(1); setPageInput('1') }
  const startRedaction = () => { setRedacting(true); setInfo(null); setFit('page'); onExpand(true) }

  useEffect(() => {
    if (!expanded) return
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape' && !redacting) onExpand(false) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [expanded, redacting, onExpand])

  return (
    <section className={`r6b-viewer ${expanded ? 'is-expanded' : ''}`} aria-label="Document viewer">
      {redacting ? (
        <div className="r6b-toolbar r6b-redaction-bar" role="toolbar" aria-label="Redaction controls">
          <span className="r6b-redaction-flag"><Icon name="redaction" />Redaction mode</span>
          <span className="r6b-redaction-hint">Draw a box over the content to remove. Redaction is permanent and audited.</span>
          <label className="r6b-inline-select"><span className="sr-only">Reason</span>
            <select defaultValue=""><option value="" disabled>Reason</option><option>Personal data</option><option>Legal hold correction</option></select>
          </label>
          <div className="r6b-toolbar-end">
            <button type="button" className="r6b-btn" onClick={() => setRedacting(false)}>Cancel</button>
            <button type="button" className="r6b-btn is-danger" disabled>Apply permanent redaction</button>
          </div>
        </div>
      ) : (
        <div className="r6b-toolbar" role="toolbar" aria-label="Document controls">
          <label className="r6b-doc-select">
            <Icon name="document" />
            <span className="sr-only">Document</span>
            <select value={docIndex} onChange={(e) => selectDoc(Number(e.target.value))}>
              {documents.map((d, i) => <option key={d.name} value={i}>{d.name}</option>)}
            </select>
            <span className="r6b-doc-index r6b-tabular" aria-hidden="true">{docIndex + 1}/{documents.length}</span>
          </label>
          <span className="r6b-divider" aria-hidden="true" />
          <form className="r6b-pager" onSubmit={(e) => { e.preventDefault(); goTo(Number(pageInput) || page) }}>
            <button type="button" className="r6b-icon-btn" aria-label="Previous page" disabled={page <= 1} onClick={() => goTo(page - 1)}><Icon name="chevronLeft" /></button>
            <input className="r6b-tabular" inputMode="numeric" aria-label="Page number" value={pageInput} onChange={(e) => setPageInput(e.target.value)} />
            <span className="r6b-tabular r6b-pager-total">of {doc.pages}</span>
            <button type="button" className="r6b-icon-btn" aria-label="Next page" disabled={page >= doc.pages} onClick={() => goTo(page + 1)}><Icon name="chevronRight" /></button>
          </form>
          <div className="r6b-zoom" role="group" aria-label="Zoom">
            <button type="button" className="r6b-icon-btn" aria-label="Zoom out" disabled={sheetWidth / PAGE_WIDTH <= 0.5} onClick={() => stepZoom(-0.25)}><span aria-hidden="true">−</span></button>
            <span className="r6b-tabular r6b-zoom-value" aria-live="polite">{percent}%</span>
            <button type="button" className="r6b-icon-btn" aria-label="Zoom in" disabled={sheetWidth / PAGE_WIDTH >= 2.5} onClick={() => stepZoom(0.25)}><span aria-hidden="true">+</span></button>
          </div>
          <div className="r6b-segmented" role="group" aria-label="Fit">
            <button type="button" aria-pressed={fit === 'page'} aria-label="Fit page" onClick={() => setFit('page')}><span className="r6b-fit-long">Fit page</span><span className="r6b-fit-short" aria-hidden="true">Page</span></button>
            <button type="button" aria-pressed={fit === 'width'} aria-label="Fit width" onClick={() => setFit('width')}><span className="r6b-fit-long">Fit width</span><span className="r6b-fit-short" aria-hidden="true">Width</span></button>
          </div>
          <div className={`r6b-find ${findOpen ? 'is-open' : ''}`}>
            <button type="button" className="r6b-icon-btn r6b-find-toggle" aria-label="Find in document" aria-expanded={findOpen} onClick={() => setFindOpen((open) => !open)}><Icon name="search" /></button>
            <label><Icon name="search" /><span className="sr-only">Find in document</span><input placeholder="Find in document" /></label>
          </div>
          <div className="r6b-toolbar-end">
            {multipage && <ToolButton label="Page thumbnails" icon="pages" pressed={thumbs} onClick={() => setThumbs((open) => !open)} />}
            <ToolButton label="Document info" icon="panel" pressed={info !== null} onClick={() => setInfo((tab) => (tab ? null : 'details'))}><span className="r6b-count">1</span></ToolButton>
            <span className="r6b-divider" aria-hidden="true" />
            {canRedact && <ToolButton label="Redact" icon="redaction" onClick={startRedaction} showLabel />}
            {canDownload && <ToolButton label="Download" icon="download" />}
            <ToolButton label="Print" icon="print" />
            <ToolButton label={expanded ? 'Exit expanded view' : 'Expand viewer'} icon={expanded ? 'collapse' : 'expand'} onClick={() => onExpand(!expanded)} />
          </div>
        </div>
      )}
      <div className={`r6b-body ${info ? 'has-info' : ''}`}>
        {thumbs && multipage && !redacting && (
          <nav className="r6b-thumbs" aria-label="Pages">
            {Array.from({ length: doc.pages }, (_, i) => i + 1).map((p) => (
              <button key={p} type="button" className={`r6b-thumb ${p === page ? 'active' : ''}`} aria-current={p === page ? 'page' : undefined} aria-label={`Page ${p}`} onClick={() => goTo(p)}>
                <PageSheet page={p} title={doc.name} width={72} compact />
                <span className="r6b-tabular">{p}</span>
              </button>
            ))}
          </nav>
        )}
        <div ref={canvasRef} className={`r6b-canvas ${redacting ? 'is-redacting' : ''}`}>
          {canvas.width > 0 && <PageSheet page={page} title={doc.name} width={sheetWidth} redacting={redacting} />}
        </div>
        {info && !redacting && <InfoPanel tab={info} onTab={setInfo} onClose={() => setInfo(null)} permissions={permissions} docIndex={docIndex} />}
      </div>
      <footer className="r6b-secure">
        <span className="r6b-seal"><Icon name="eye" />Secure view</span>
        <span className="r6b-tabular">Page {page} of {doc.pages}</span>
        <span className="r6b-secure-note">Views, downloads, prints and redactions are recorded</span>
      </footer>
    </section>
  )
}

function RecordInspector({ index }: { index: number }) {
  const r = records[index]
  return (
    <div className="r6b-inspector">
      <dl className="r6b-dl">
        <div><dt>Contract number</dt><dd className="r6b-tabular">{r.contract}</dd></div>
        <div><dt>Member number</dt><dd className="r6b-tabular">{r.member}</dd></div>
        <div><dt>Member last name</dt><dd>{r.last}</dd></div>
        <div><dt>Service entity</dt><dd>{r.entity}</dd></div>
        <div><dt>Region</dt><dd>{r.region}</dd></div>
        <div><dt>Last updated</dt><dd>{r.updated}</dd></div>
      </dl>
      <h3>Documents</h3>
      <ul className="r6b-doclist">
        {documents.map((d, i) => <li key={d.name} className={i === 0 ? 'active' : ''}><Icon name="document" /><span>{d.name}</span><span className="r6b-tabular">{d.pages}p</span></li>)}
      </ul>
    </div>
  )
}

function Workspace({ permissions, startExpanded = false, initialInfo = null }: { permissions: Permissions; startExpanded?: boolean; initialInfo?: InfoTab | null }) {
  const [selected, setSelected] = useState(0)
  const [pane, setPane] = useState<'results' | 'record'>('results')
  const [expanded, setExpanded] = useState(startExpanded)
  const [mobileView, setMobileView] = useState<'list' | 'viewer'>('list')
  const open = (index: number) => { setSelected(index); setMobileView('viewer') }

  return (
    <div className={`r6b-workspace ${expanded ? 'is-expanded' : ''} show-${mobileView}`}>
      <section className="r6b-results" aria-label="Results">
        <header className="r6b-results-head">
          <div className="r6b-pane-switch" role="tablist" aria-label="Left pane">
            <button type="button" role="tab" aria-selected={pane === 'results'} onClick={() => setPane('results')}>Results <span className="r6b-count">{records.length}</span></button>
            <button type="button" role="tab" aria-selected={pane === 'record'} onClick={() => setPane('record')}>Record</button>
          </div>
          <button type="button" className="r6b-link">Edit search</button>
        </header>
        {pane === 'results' ? (
          <ul className="r6b-list" role="listbox" aria-label="Matching records">
            {records.map((r, i) => (
              <li key={r.contract} role="option" aria-selected={i === selected} tabIndex={0} className={i === selected ? 'active' : ''}
                onClick={() => open(i)}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(i) } }}>
                <div className="r6b-row-top"><span className="r6b-tabular">{r.contract}</span><span>{r.updated}</span></div>
                <div className="r6b-row-main">{r.last} · {r.entity}</div>
                <div className="r6b-row-meta"><span>{r.region}</span><span className="r6b-tabular">Member {r.member}</span></div>
              </li>
            ))}
          </ul>
        ) : <RecordInspector index={selected} />}
      </section>
      <div className="r6b-viewer-slot">
        <button type="button" className="r6b-back" onClick={() => setMobileView('list')}><Icon name="chevronLeft" />Results</button>
        <Viewer key={`${selected}-${startExpanded}`} permissions={permissions} expanded={expanded} onExpand={setExpanded} initialInfo={initialInfo} initialThumbs={startExpanded} />
      </div>
    </div>
  )
}

const actionRows: { action: string; r5: string; r6: string; gate: string }[] = [
  { action: 'Document picker', r5: 'Row 1, with “n of N”', r6: 'Toolbar start, with n/N', gate: 'Shown when the record has several documents' },
  { action: 'Previous / next / page number', r5: 'Row 2', r6: 'Toolbar, page entry kept', gate: 'Always' },
  { action: 'Zoom out / percentage / zoom in', r5: 'Row 2', r6: 'Toolbar', gate: 'Disabled during redaction' },
  { action: 'Fit', r5: 'Single “Fit” = fit to width', r6: 'Fit page and Fit width', gate: 'Default remains fit to width (R5)' },
  { action: 'Search in document + match strip', r5: 'Row 2 field + Search button', r6: 'Toolbar field (icon when narrow); match strip unchanged', gate: 'Always' },
  { action: 'Details / Comments / Tags', r5: 'Three toggle buttons', r6: 'One “Document info” panel with three tabs', gate: 'Tags tab needs ADD_DOCUMENT_TAG; adding comments needs ADD_DOCUMENT_COMMENT' },
  { action: 'Redact', r5: 'Row 1 button', r6: 'Toolbar button; opens expanded view in redaction mode', gate: 'PERMANENT_REDACTION' },
  { action: 'Download', r5: 'Row 1 icon', r6: 'Toolbar icon', gate: 'DOWNLOAD_CURRENT_PDF · audited' },
  { action: 'Print', r5: 'Row 1 icon', r6: 'Toolbar icon', gate: 'PRINT_DOCUMENT · audited' },
  { action: 'Page thumbnails', r5: '—', r6: 'New, collapsible; multipage only', gate: 'Presentation only' },
  { action: 'Expand viewer', r5: '—', r6: 'New; Esc or the same button exits', gate: 'Presentation only' },
]

function ActionsConcept() {
  return (
    <div className="r6b-actions">
      <figure className="r6b-figure">
        <figcaption><strong>Before (R5):</strong> two dark rows, plus a separate footer.</figcaption>
        <div className="r6b-before" aria-hidden="true">
          <div className="r6b-before-row"><span className="r6b-pill">Member Agreement 2026.pdf · 1 of 3</span><span className="r6b-pill">Download</span><span className="r6b-pill">Print</span><span className="r6b-pill">Redact</span><span className="r6b-pill">Details · Comments 1 · Tags 1</span></div>
          <div className="r6b-before-row"><span className="r6b-pill">‹ 1 of 6 ›</span><span className="r6b-pill">− 169% + Fit</span><span className="r6b-pill r6b-grow">Search in document</span><span className="r6b-pill">Search</span></div>
        </div>
      </figure>
      <div className="r6b-table-wrap">
        <table className="r6b-table">
          <caption>Every R5 viewer action and where it lives in R6-B</caption>
          <thead><tr><th scope="col">Action</th><th scope="col">R5</th><th scope="col">R6-B</th><th scope="col">Permission / behaviour</th></tr></thead>
          <tbody>{actionRows.map((row) => <tr key={row.action}><th scope="row">{row.action}</th><td>{row.r5}</td><td>{row.r6}</td><td>{row.gate}</td></tr>)}</tbody>
        </table>
      </div>
    </div>
  )
}

const concepts: { id: Concept; label: string; summary: string }[] = [
  { id: 'workspace', label: 'Two-pane workspace', summary: 'Results and Viewer side by side. Switch the left pane to Record for the optional inspector; the PDF never shares its width with it.' },
  { id: 'expanded', label: 'Expanded viewer', summary: 'Full-width reading with page thumbnails and the Document info panel docked. Redact always opens here.' },
  { id: 'actions', label: 'Toolbar actions', summary: 'One light toolbar row. Every R5 action is retained and keeps its permission gate.' },
]

export default function R6BPrototypes() {
  const [concept, setConcept] = useState<Concept>('workspace')
  const [permissions, setPermissions] = useState<Permissions>('steward')
  const active = concepts.find((c) => c.id === concept)!
  return (
    <main className="r6b">
      <header className="r6b-header">
        <div className="r6b-header-copy">
          <p className="r6b-flag">Prototype for review · sample data · not connected to the workflow</p>
          <h1>R6-B · Business workspace</h1>
          <p className="r6b-summary">{active.summary}</p>
        </div>
        <div className="r6b-header-controls">
          <div className="r6b-concepts" role="tablist" aria-label="Concepts">
            {concepts.map((c) => <button key={c.id} type="button" role="tab" aria-selected={c.id === concept} onClick={() => setConcept(c.id)}>{c.label}</button>)}
          </div>
          {concept !== 'actions' && (
            <label className="r6b-inline-select">
              <span>Sample permissions</span>
              <select value={permissions} onChange={(e) => setPermissions(e.target.value as Permissions)}>
                <option value="steward">Document Steward (all actions)</option>
                <option value="user">Business User (view, print, comment)</option>
              </select>
            </label>
          )}
        </div>
      </header>
      <div role="tabpanel" aria-label={active.label} className="r6b-stage">
        {concept === 'workspace' && <Workspace key={`w-${permissions}`} permissions={permissions} />}
        {concept === 'expanded' && <Workspace key={`e-${permissions}`} permissions={permissions} startExpanded initialInfo="comments" />}
        {concept === 'actions' && <ActionsConcept />}
      </div>
    </main>
  )
}
