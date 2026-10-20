import { Fragment, useEffect, useMemo, useRef, useState, type PointerEvent as RPointerEvent, type ReactNode } from 'react'
import { ArrowLeft, ChevronLeft, ChevronRight, Download, Eraser, Info, Lock, Maximize2, Minus, PanelRight, Plus, Printer, Search, ShieldAlert, Trash2, X } from 'lucide-react'
import { useStore } from './store'
import { BUSINESS_LIMITS, CAPABILITY_LABELS, PAGE_TEXT, RETENTION, type Capability, type Doc, type RecordItem, type Redaction } from './data'
import { Badge, Callout, Dialog, Drawer, Tabs, fmtDate, fmtDateTime, fmtRel, humanize } from './ui'

type Zoom = 'width' | 'page' | number
type InfoTab = 'details' | 'comments' | 'tags' | 'activity'

const SSN = /\d{3}-\d{2}-\d{4}/

function pageParagraphs(doc: Doc, page: number) {
  const base = PAGE_TEXT[doc.kind] ?? PAGE_TEXT.default
  if (page === 1) return base
  return [...base.slice(page % base.length), ...base.slice(0, page % base.length)].slice(0, 4)
}

function highlight(text: string, q: string): ReactNode {
  if (!q) return text
  const parts = text.split(new RegExp(`(${q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})`, 'ig'))
  return parts.map((p, i) => (i % 2 === 1 ? <mark key={i}>{p}</mark> : <Fragment key={i}>{p}</Fragment>))
}

export function Viewer({ record, results, docId, onClose }: { record: RecordItem; results: RecordItem[]; docId: string; onClose: () => void }) {
  const { can, role, setOpenDocId, setSelectedRef, updateDoc, addDocActivity, toast } = useStore()
  const doc = record.docs.find((d) => d.id === docId) ?? record.docs[0]
  const docIndex = record.docs.indexOf(doc)
  const recIndex = results.findIndex((r) => r.ref === record.ref)

  const [zoom, setZoom] = useState<Zoom>('width')
  const [page, setPage] = useState(1)
  const [findOpen, setFindOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [infoOpen, setInfoOpen] = useState(false)
  const [docsOpen, setDocsOpen] = useState(false)
  const [infoTab, setInfoTab] = useState<InfoTab>('details')
  const [redacting, setRedacting] = useState(false)
  const [marks, setMarks] = useState<Redaction[]>([])
  const [review, setReview] = useState(false)
  const [canvasSize, setCanvasSize] = useState({ w: 800, h: 700 })
  const canvasRef = useRef<HTMLDivElement>(null)
  const sheetRefs = useRef<(HTMLDivElement | null)[]>([])
  const drawRef = useRef<{ page: number; x0: number; y0: number; el: HTMLDivElement } | null>(null)
  const [drawing, setDrawing] = useState<Redaction | null>(null)

  useEffect(() => { setPage(1); setMarks([]); setRedacting(false); setQuery(''); canvasRef.current?.scrollTo({ top: 0 }) }, [doc.id])

  useEffect(() => {
    const el = canvasRef.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => setCanvasSize({ w: e.contentRect.width, h: e.contentRect.height }))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    const root = canvasRef.current
    if (!root) return
    const io = new IntersectionObserver((entries) => {
      const best = entries.filter((e) => e.isIntersecting).sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0]
      if (best) setPage(Number((best.target as HTMLElement).dataset.page))
    }, { root, threshold: [0.25, 0.5, 0.75] })
    sheetRefs.current.forEach((s) => s && io.observe(s))
    return () => io.disconnect()
  }, [doc.id, zoom])

  useEffect(() => {
    const k = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !review && !infoOpen && !docsOpen) { if (redacting) setRedacting(false); else onClose() }
    }
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [onClose, redacting, review, infoOpen, docsOpen])

  const sheetWidth = zoom === 'width' ? Math.min(canvasSize.w - 24, 980) : zoom === 'page' ? Math.min((canvasSize.h - 96) * (8.5 / 11), canvasSize.w - 24) : 816 * zoom
  const zoomPct = Math.round((sheetWidth / 816) * 100)
  const stepZoom = (dir: 1 | -1) => setZoom(Math.min(3, Math.max(0.5, Math.round((sheetWidth / 816 + dir * 0.25) * 4) / 4)))
  const goPage = (p: number) => { const n = Math.min(doc.pages, Math.max(1, p)); sheetRefs.current[n - 1]?.scrollIntoView({ behavior: 'smooth', block: 'start' }); setPage(n) }

  const matchCount = useMemo(() => {
    if (!query.trim()) return 0
    const re = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'ig')
    return Array.from({ length: doc.pages }, (_, i) => pageParagraphs(doc, i + 1).join(' ')).reduce((n, t) => n + (t.match(re)?.length ?? 0), 0)
  }, [query, doc])

  const deny = (c: Capability) => toast({ tone: 'bad', title: 'Not permitted for your role', detail: `${role.name} doesn't include "${CAPABILITY_LABELS[c]}". In production the server enforces this and records a DENIED audit event.` })
  const gated = (c: Capability, run: () => void) => () => (can(c) ? run() : deny(c))

  const download = gated('DOWNLOAD_CURRENT_PDF', () => { addDocActivity(doc.id, 'DOCUMENT_DOWNLOADED'); toast({ title: 'Simulated download', detail: `${doc.filename} — no file was produced. Production streams the current decrypted PDF and audits the download.` }) })
  const print = gated('PRINT_DOCUMENT', () => { addDocActivity(doc.id, 'DOCUMENT_PRINTED'); toast({ title: 'Simulated print', detail: 'A print event was recorded locally. Production records the print audit event before opening the print dialog.' }) })
  const startRedact = gated('PERMANENT_REDACTION', () => { setRedacting(true); setInfoOpen(false); toast({ title: 'Redaction mode', detail: 'Drag across the page to mark regions. Nothing changes until you review and confirm.' }) })

  const toPct = (e: RPointerEvent, el: HTMLDivElement) => { const r = el.getBoundingClientRect(); return { x: ((e.clientX - r.left) / r.width) * 100, y: ((e.clientY - r.top) / r.height) * 100 } }
  const onDown = (p: number) => (e: RPointerEvent<HTMLDivElement>) => {
    if (!redacting || (e.target as HTMLElement).closest('button')) return
    e.currentTarget.setPointerCapture(e.pointerId)
    const { x, y } = toPct(e, e.currentTarget)
    drawRef.current = { page: p, x0: x, y0: y, el: e.currentTarget }
    setDrawing({ page: p, x, y, w: 0, h: 0 })
  }
  const onMove = (e: RPointerEvent<HTMLDivElement>) => {
    const d = drawRef.current
    if (!d) return
    const { x, y } = toPct(e, d.el)
    setDrawing({ page: d.page, x: Math.max(0, Math.min(x, d.x0)), y: Math.max(0, Math.min(y, d.y0)), w: Math.min(100, Math.abs(x - d.x0)), h: Math.min(100, Math.abs(y - d.y0)) })
  }
  const onUp = () => {
    if (drawing && drawing.w > 1.5 && drawing.h > 0.8) setMarks((m) => [...m, drawing])
    drawRef.current = null
    setDrawing(null)
  }

  const applyRedaction = (reason: string) => {
    updateDoc(doc.id, (d) => ({ ...d, redactions: [...d.redactions, ...marks] }))
    addDocActivity(doc.id, 'DOCUMENT_REDACTED')
    toast({ title: 'Simulated permanent redaction', detail: `${marks.length} region${marks.length === 1 ? '' : 's'} blacked out in this preview only. Reason recorded: "${reason.slice(0, 60)}". No PDF was rewritten.` })
    setMarks([]); setRedacting(false); setReview(false)
  }

  const switchRecord = (dir: 1 | -1) => {
    const next = results[recIndex + dir]
    if (!next) return
    setSelectedRef(next.ref)
    setOpenDocId(next.docs[0].id)
    addDocActivity(next.docs[0].id, 'DOCUMENT_VIEWED')
  }
  const switchDoc = (id: string) => { setOpenDocId(id); addDocActivity(id, 'DOCUMENT_VIEWED'); setDocsOpen(false) }

  const docsNav = (
    <>
      <div className="stack" style={{ gap: 8 }}>
        <div className="row between">
          <span className="eyebrow">Record {recIndex >= 0 ? `${recIndex + 1} of ${results.length}` : ''}</span>
          <span className="row" style={{ gap: 2 }}>
            <button type="button" className="v-btn quiet icon sm" disabled={recIndex <= 0} onClick={() => switchRecord(-1)} aria-label="Previous record"><ChevronLeft size={16} /></button>
            <button type="button" className="v-btn quiet icon sm" disabled={recIndex < 0 || recIndex >= results.length - 1} onClick={() => switchRecord(1)} aria-label="Next record"><ChevronRight size={16} /></button>
          </span>
        </div>
        <strong style={{ fontSize: 14 }}>{record.title}</strong>
        <div className="ws-ids">{record.identifiers.map((i) => <div key={i.label}><span>{i.label}</span><strong style={{ fontSize: 13 }}>{i.value}</strong></div>)}</div>
      </div>
      <div className="stack" style={{ gap: 6 }}>
        <span className="eyebrow">Documents · {record.docs.length}</span>
        {record.docs.map((d) => (
          <button key={d.id} type="button" className="vw-thumb" aria-current={d.id === doc.id} onClick={() => switchDoc(d.id)}>
            <i aria-hidden /><span className="grow" style={{ minWidth: 0 }}><strong className="truncate" style={{ display: 'block', fontWeight: 500, fontSize: 13 }}>{d.kind}</strong>{d.pages} pp · {fmtDate(d.ingestedAt)}</span>
          </button>
        ))}
      </div>
      {doc.pages > 1 && (
        <div className="stack" style={{ gap: 6 }}>
          <span className="eyebrow">Pages</span>
          <div className="vw-thumbs">
            {Array.from({ length: doc.pages }, (_, i) => (
              <button key={i} type="button" className="vw-thumb" aria-current={page === i + 1} onClick={() => { goPage(i + 1); setDocsOpen(false) }}><i aria-hidden />Page {i + 1}</button>
            ))}
          </div>
        </div>
      )}
    </>
  )

  return (
    <div className="vw" role="dialog" aria-modal="true" aria-label={`${doc.kind}, ${record.identifiers[0].value}`}>
      <div className="vw-bar">
        <button type="button" className="v-btn quiet icon" onClick={onClose} aria-label="Back to results"><ArrowLeft size={20} /></button>
        <button type="button" className="vw-title grow" style={{ border: 0, background: 'none', textAlign: 'left', cursor: 'pointer' }} onClick={() => setDocsOpen(true)} aria-label="Switch document">
          <strong className="truncate">{doc.kind}</strong>
          <span className="truncate">{record.identifiers[0].value} · Doc {docIndex + 1}/{record.docs.length} · {doc.pages} pp</span>
        </button>
        <div className="vw-group">
          <button type="button" className="v-btn quiet icon" aria-pressed={findOpen} onClick={() => setFindOpen((f) => !f)} aria-label="Find in document"><Search size={18} /></button>
          <span className="vw-sep vw-hide-sm" aria-hidden />
          <ActionButton label="Download" icon={<Download size={18} />} allowed={can('DOWNLOAD_CURRENT_PDF')} onClick={download} className="vw-hide-sm" />
          <ActionButton label="Print" icon={<Printer size={18} />} allowed={can('PRINT_DOCUMENT')} onClick={print} className="vw-hide-sm" />
          <ActionButton label="Redact" icon={<Eraser size={18} />} allowed={can('PERMANENT_REDACTION')} onClick={startRedact} pressed={redacting} />
          <button type="button" className="v-btn quiet icon vw-panel-toggle" aria-pressed={infoOpen} onClick={() => setInfoOpen(true)} aria-label="Document information"><PanelRight size={18} /></button>
        </div>
      </div>
      {findOpen && (
        <div className="vw-bar" style={{ minHeight: 52 }}>
          <Search size={16} aria-hidden className="faint" style={{ marginLeft: 8 }} />
          <input className="v-input grow" autoFocus placeholder="Find text in this document" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Find in document" style={{ border: 0, boxShadow: 'none', background: 'transparent' }} />
          <span className="faint" style={{ fontSize: 13, whiteSpace: 'nowrap' }} aria-live="polite">{query ? `${matchCount} match${matchCount === 1 ? '' : 'es'}` : ''}</span>
          <button type="button" className="v-btn quiet icon sm" onClick={() => { setFindOpen(false); setQuery('') }} aria-label="Close find"><X size={16} /></button>
        </div>
      )}
      {redacting && (
        <div className="vw-bar" style={{ minHeight: 48, background: 'var(--danger-soft)', borderColor: 'color-mix(in oklab, var(--danger) 30%, transparent)' }}>
          <ShieldAlert size={18} aria-hidden style={{ color: 'var(--danger)', marginLeft: 8, flex: 'none' }} />
          <span className="grow" style={{ fontSize: 13 }}><strong>Redaction mode.</strong> Drag to mark regions. Marks are drafts until confirmed.</span>
        </div>
      )}

      <div className="vw-body">
        <nav className="vw-docs" aria-label="Record documents">{docsNav}</nav>
        <div className="vw-canvas" ref={canvasRef} style={{ position: 'relative' }}>
          {Array.from({ length: doc.pages }, (_, i) => {
            const p = i + 1
            return (
              <div key={p} data-page={p} ref={(el) => { sheetRefs.current[i] = el }} className={`vw-sheet${redacting ? ' drawing' : ''}`} style={{ width: sheetWidth, maxWidth: 'none' }}
                onPointerDown={onDown(p)} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onUp} aria-label={`Page ${p} of ${doc.pages}`}>
                <div className="vw-sheet-inner">
                  <h4>{doc.kind.toUpperCase()}</h4>
                  <div className="sub"><span>{record.identifiers.map((x) => `${x.label} ${x.value}`).join('  ·  ')}</span><span>Northwind Health</span></div>
                  {pageParagraphs(doc, p).map((t, k) => <p key={k}>{highlight(t, query.trim())}</p>)}
                  {p === doc.pages && <div className="sig"><div>Member signature</div><div>Date</div></div>}
                  <div className="foot"><span>{doc.filename}</span><span>Page {p} of {doc.pages}</span></div>
                </div>
                {doc.redactions.filter((r) => r.page === p).map((r, k) => <div key={`r${k}`} className="vw-redacted" style={{ left: `${r.x}%`, top: `${r.y}%`, width: `${r.w}%`, height: `${r.h}%` }}><span>REDACTED · SIMULATED</span></div>)}
                {marks.map((m, k) => m.page === p && (
                  <div key={`m${k}`} className="vw-mark" style={{ left: `${m.x}%`, top: `${m.y}%`, width: `${m.w}%`, height: `${m.h}%` }}>
                    <button type="button" onClick={() => setMarks((all) => all.filter((_, j) => j !== k))} aria-label={`Remove mark ${k + 1}`}><X size={14} /></button>
                  </div>
                ))}
                {drawing && drawing.page === p && <div className="vw-mark" style={{ left: `${drawing.x}%`, top: `${drawing.y}%`, width: `${drawing.w}%`, height: `${drawing.h}%` }} />}
              </div>
            )
          })}
          {redacting ? (
            <div className="vw-redact-bar" style={{ position: 'sticky', bottom: 12, transform: 'none', left: 'auto', right: 'auto', width: 'min(620px, 100%)' }}>
              <span className="grow" style={{ fontSize: 14 }}><strong>{marks.length}</strong> region{marks.length === 1 ? '' : 's'} marked</span>
              <button type="button" className="v-btn sm" onClick={() => { setRedacting(false); setMarks([]) }}>Cancel</button>
              <button type="button" className="v-btn danger sm" disabled={marks.length === 0} onClick={() => setReview(true)}>Review</button>
            </div>
          ) : (
            <div className="vw-redact-bar" style={{ position: 'sticky', bottom: 12, transform: 'none', left: 'auto', right: 'auto', width: 'auto', padding: 6, gap: 2, background: 'var(--surface)', color: 'var(--ink)', border: '1px solid var(--line)' }} role="toolbar" aria-label="Page and zoom">
              <button type="button" className="v-btn quiet icon sm" onClick={() => goPage(page - 1)} disabled={page <= 1} aria-label="Previous page"><ChevronLeft size={16} /></button>
              <span className="vw-page"><input value={page} inputMode="numeric" aria-label="Page number" onChange={(e) => { const n = Number(e.target.value); if (n) goPage(n) }} />/ {doc.pages}</span>
              <button type="button" className="v-btn quiet icon sm" onClick={() => goPage(page + 1)} disabled={page >= doc.pages} aria-label="Next page"><ChevronRight size={16} /></button>
              <span className="vw-sep" aria-hidden />
              <button type="button" className="v-btn quiet icon sm" onClick={() => stepZoom(-1)} aria-label="Zoom out"><Minus size={16} /></button>
              <span style={{ fontSize: 13, minWidth: 44, textAlign: 'center', fontVariantNumeric: 'tabular-nums' }}>{zoomPct}%</span>
              <button type="button" className="v-btn quiet icon sm" onClick={() => stepZoom(1)} aria-label="Zoom in"><Plus size={16} /></button>
              <button type="button" className="v-btn quiet sm" aria-pressed={zoom === 'width'} onClick={() => setZoom(zoom === 'width' ? 'page' : 'width')} aria-label={zoom === 'width' ? 'Fit page' : 'Fit width'}><Maximize2 size={14} aria-hidden /><span className="vw-hide-sm">{zoom === 'width' ? 'Fit width' : zoom === 'page' ? 'Fit page' : 'Fit'}</span></button>
            </div>
          )}
        </div>
        <aside className="vw-panel" aria-label="Document information"><DocInfo doc={doc} tab={infoTab} setTab={setInfoTab} /></aside>
      </div>

      {infoOpen && (
        <Drawer eyebrow={doc.kind} title="Document information" onClose={() => setInfoOpen(false)}>
          <div className="row wrap vw-actions-sm" style={{ gap: 8 }}>
            <ActionButton label="Download" icon={<Download size={16} />} allowed={can('DOWNLOAD_CURRENT_PDF')} onClick={download} text />
            <ActionButton label="Print" icon={<Printer size={16} />} allowed={can('PRINT_DOCUMENT')} onClick={print} text />
          </div>
          <DocInfo doc={doc} tab={infoTab} setTab={setInfoTab} embedded />
        </Drawer>
      )}
      {docsOpen && <Drawer eyebrow="Record documents" title={record.identifiers[0].value} onClose={() => setDocsOpen(false)}>{docsNav}</Drawer>}
      {review && <RedactionReview doc={doc} marks={marks} onCancel={() => setReview(false)} onApply={applyRedaction} />}
    </div>
  )
}

function ActionButton({ label, icon, allowed, onClick, pressed, className = '', text }: { label: string; icon: ReactNode; allowed: boolean; onClick: () => void; pressed?: boolean; className?: string; text?: boolean }) {
  return (
    <button type="button" className={`v-btn ${text ? 'sm' : 'quiet icon'} ${className}`} aria-disabled={!allowed} aria-pressed={pressed} onClick={onClick}
      aria-label={allowed ? label : `${label} (not permitted for your role)`} title={allowed ? label : `${label} — not permitted for your role`} style={{ position: 'relative' }}>
      {icon}{text && label}
      {!allowed && <Lock size={10} aria-hidden style={{ position: text ? 'static' : 'absolute', right: 6, bottom: 6 }} />}
    </button>
  )
}

function RedactionReview({ doc, marks, onCancel, onApply }: { doc: Doc; marks: Redaction[]; onCancel: () => void; onApply: (reason: string) => void }) {
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const pages = [...new Set(marks.map((m) => m.page))].sort((a, b) => a - b)
  const ready = reason.trim().length >= 10 && confirmed
  return (
    <Dialog title="Apply permanent redaction" onClose={onCancel}
      footer={<><button type="button" className="v-btn" onClick={onCancel}>Back to marking</button><button type="button" className="v-btn danger" disabled={!ready} onClick={() => onApply(reason.trim())}>Apply redaction (simulated)</button></>}>
      <Callout tone="bad" title="This permanently replaces the current PDF">
        In production, marked regions are irreversibly removed for every user and the original is not retained. Use only for corrective removal of content that should never have been stored.
      </Callout>
      <dl className="v-kv">
        <div><dt>Document</dt><dd>{doc.kind} · <span className="mono">{doc.id}</span></dd></div>
        <div><dt>Regions</dt><dd>{marks.length} on page{pages.length === 1 ? '' : 's'} {pages.join(', ')}</dd></div>
      </dl>
      <label className="v-field">
        <span>Corrective reason<span className="v-hint">Required</span></span>
        <textarea className="v-textarea" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Member SSN captured in error on page 1, section 4." />
        <small>{reason.trim().length < 10 ? 'At least 10 characters. Stored with the audit event.' : `${reason.trim().length}/500`}</small>
      </label>
      <label className="v-check">
        <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
        <span>I understand this cannot be undone and will be attributed to me in the audit trail.</span>
      </label>
      <Callout tone="sim" title="Prototype safeguard">No document is modified. The preview overlays black boxes in this tab only.</Callout>
    </Dialog>
  )
}

function DocInfo({ doc, tab, setTab, embedded }: { doc: Doc; tab: InfoTab; setTab: (t: InfoTab) => void; embedded?: boolean }) {
  const { can, role, updateDoc, addDocActivity, toast } = useStore()
  const [comment, setComment] = useState('')
  const [tag, setTag] = useState('')
  const suggestsSsn = (PAGE_TEXT[doc.kind] ?? []).some((t) => SSN.test(t)) && doc.redactions.length === 0
  const body = (
    <>
      {tab === 'details' && (
        <>
          {suggestsSsn && can('PERMANENT_REDACTION') && <Callout tone="warn" title="Possible sensitive identifier on page 1">Sample content includes a full SSN. Stewards can correct this with permanent redaction.</Callout>}
          <dl className="v-kv">
            <div><dt>File name</dt><dd className="mono">{doc.filename}</dd></div>
            <div><dt>Document ID</dt><dd className="mono">{doc.id}</dd></div>
            <div><dt>Submission</dt><dd className="mono">{doc.submissionId}</dd></div>
            <div><dt>Pages · size</dt><dd>{doc.pages} · {doc.sizeKb} KB</dd></div>
            <div><dt>Ingested</dt><dd>{fmtDateTime(doc.ingestedAt)}</dd></div>
            <div><dt>Retained until</dt><dd>{fmtDate(doc.expiresAt)} <span className="faint">· policy v{RETENTION.active.version} at ingestion</span></dd></div>
            <div><dt>Protection</dt><dd>Encrypted at rest · served page-by-page to authorized sessions</dd></div>
            {doc.redactions.length > 0 && <div><dt>Corrections</dt><dd><Badge tone="warn">{doc.redactions.length} simulated redaction{doc.redactions.length === 1 ? '' : 's'}</Badge></dd></div>}
          </dl>
        </>
      )}
      {tab === 'comments' && (
        <>
          {doc.comments.length === 0 && <p className="faint">No comments yet.</p>}
          {doc.comments.map((c) => <article key={c.id} className="v-comment"><header><strong>{c.by}</strong><span>{fmtRel(c.at)}</span></header><p>{c.text}</p></article>)}
          {can('ADD_DOCUMENT_COMMENT') ? (
            <form className="stack" style={{ gap: 8 }} onSubmit={(e) => {
              e.preventDefault(); if (!comment.trim()) return
              updateDoc(doc.id, (d) => ({ ...d, comments: [...d.comments, { id: `sim-c${Date.now()}`, text: comment.trim(), by: role.person, at: new Date().toISOString() }] }))
              addDocActivity(doc.id, 'DOCUMENT_COMMENT_ADDED'); setComment(''); toast({ title: 'Comment added (simulated)', detail: 'Visible in this tab only.' })
            }}>
              <textarea className="v-textarea" aria-label="New comment" value={comment} maxLength={BUSINESS_LIMITS.maxCommentLength} onChange={(e) => setComment(e.target.value)} placeholder="Add a comment for other authorized users" />
              <button type="submit" className="v-btn sm" style={{ alignSelf: 'flex-end' }} disabled={!comment.trim()}>Add comment</button>
            </form>
          ) : <Callout title="Commenting isn't assigned to your role" />}
        </>
      )}
      {tab === 'tags' && (
        <>
          <div className="v-tagrow">{doc.tags.length === 0 ? <span className="faint">No tags yet.</span> : doc.tags.map((t) => <Badge key={t.id} tone="outline">{t.tag}</Badge>)}</div>
          {can('ADD_DOCUMENT_TAG') ? (
            <form className="row" onSubmit={(e) => {
              e.preventDefault(); const v = tag.trim(); if (!v) return
              if (doc.tags.some((t) => t.tag.toLowerCase() === v.toLowerCase())) { toast({ tone: 'bad', title: 'Tag already applied' }); return }
              updateDoc(doc.id, (d) => ({ ...d, tags: [...d.tags, { id: `sim-t${Date.now()}`, tag: v, by: role.person, at: new Date().toISOString() }] }))
              addDocActivity(doc.id, 'DOCUMENT_TAG_ADDED'); setTag('')
            }}>
              <input className="v-input grow" aria-label="New tag" value={tag} maxLength={BUSINESS_LIMITS.maxTagLength} onChange={(e) => setTag(e.target.value)} placeholder="Add tag" />
              <button type="submit" className="v-btn" disabled={!tag.trim()}>Add</button>
            </form>
          ) : <Callout title="Tagging isn't assigned to your role" />}
        </>
      )}
      {tab === 'activity' && (
        <ol className="v-timeline">
          {doc.activity.map((a) => (
            <li key={a.id}><span className={`pt ${a.simulated ? 'sim' : a.outcome === 'SUCCESS' ? 'ok' : 'bad'}`} aria-hidden />
              <div className="stack" style={{ gap: 0 }}><strong>{humanize(a.action.replace('DOCUMENT_', ''))}</strong><span>{a.actor} · {fmtDateTime(a.at)}{a.simulated ? ' · simulated' : ''}</span></div>
            </li>
          ))}
        </ol>
      )}
    </>
  )
  return (
    <>
      <Tabs label="Document information" value={tab} onChange={(t) => setTab(t as InfoTab)} tabs={[{ id: 'details', label: 'Details' }, { id: 'comments', label: `Comments${doc.comments.length ? ` ${doc.comments.length}` : ''}` }, { id: 'tags', label: 'Tags' }, { id: 'activity', label: 'Activity' }]} />
      {embedded ? <div className="stack-lg">{body}</div> : <div className="vw-panel-body">{body}</div>}
      {!embedded && <div style={{ padding: 12, borderTop: '1px solid var(--line)' }} className="faint"><Info size={13} aria-hidden style={{ verticalAlign: '-2px', marginRight: 6 }} /><small>Every view, download, print and correction is audited.</small></div>}
    </>
  )
}
