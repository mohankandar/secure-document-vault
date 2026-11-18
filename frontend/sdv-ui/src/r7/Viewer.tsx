import { useEffect, useRef, useState, type PointerEvent as RPointerEvent, type ReactNode } from 'react'
import { ArrowLeft, ChevronLeft, ChevronRight, Download, Eraser, Info, Maximize2, Minus, PanelLeftClose, PanelLeftOpen, PanelRight, Plus, Printer, Search, ShieldAlert, Sparkles, Trash2, X } from 'lucide-react'
import { useStore } from './store'
import { BUSINESS_LIMITS, PAGE_TEXT, RETENTION, type Doc, type RecordItem, type Redaction } from './data'
import { ApiRequestError } from '../api'
import { Badge, Callout, Dialog, Drawer, Tabs, auditEventLabel, fmtDate, fmtDateTime, fmtRel, humanize } from './ui'
import { viewerMetadata, viewerPage, viewerSearch, record as fetchRecord, toRecord, documentActivity, type LiveDocumentActivity, type ViewerSearch, documentTags, documentComments, createDocumentTag, createDocumentComment, type LiveTag, type LiveComment, secureDownload, recordPrintEvent, permanentRedaction, viewerPreviews } from './liveApi'

type Zoom = 'width' | 'page' | number
type InfoTab = 'ask' | 'details' | 'comments' | 'tags' | 'activity'

const showDevelopmentUtilities = import.meta.env.DEV

/** The server refused an action (e.g. permissions changed mid-session). Plain words, no codes. */
export const REFUSED = { title: "You can't do this", detail: "Your role doesn't allow it. Ask your Business admin if you need it." }
export const isRefused = (e: unknown) => e instanceof ApiRequestError && e.status === 403
const errorText = (e: unknown) => (isRefused(e) ? `${REFUSED.title}. ${REFUSED.detail}` : e instanceof Error ? e.message : String(e))

/** A readable document kind from its file name: "loan-agreement.pdf" -> "Loan agreement". */
export const docKind = (filename: string) => {
  const base = filename.replace(/\.[a-z0-9]{1,5}$/i, '').replace(/[-_]+/g, ' ').replace(/\s+/g, ' ').trim()
  return base ? base.charAt(0).toUpperCase() + base.slice(1) : filename
}

const DOCS_FOLDED_KEY = 'sdv.r7.viewer.docsFolded'
const readFolded = () => { try { return window.localStorage.getItem(DOCS_FOLDED_KEY) === 'true' } catch { return false } }
const PREVIEW_CHUNK = 12

const SSN = /\d{3}-\d{2}-\d{4}/

export function Viewer({ record, results, docId, onClose, recordTitle, backLabel = 'Results' }: { record: RecordItem; results: RecordItem[]; docId: string; onClose: () => void; /** Same title as the record panel. */ recordTitle?: string; backLabel?: string }) {
  const { can, setOpenDocId, setSelectedRef, setRecords, updateDoc, addDocActivity, toast, liveSession } = useStore()
  const doc = record.docs.find((d) => d.id === docId) ?? record.docs[0]
  const [pageCount, setPageCount] = useState(0)
  // This opening's ID from the server; page requests send it so each page is recorded once per viewing (task 0007).
  const [viewingId, setViewingId] = useState('')
  const [viewerFailure, setViewerFailure] = useState('')
  const [viewerLoading, setViewerLoading] = useState(true)
  const [searchResults, setSearchResults] = useState<ViewerSearch | null>(null)
  const [searchError, setSearchError] = useState('')
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
  const pageTotal = pageCount || doc.pages
  const canvasRef = useRef<HTMLDivElement>(null)
  const sheetRefs = useRef<(HTMLDivElement | null)[]>([])
  const drawRef = useRef<{ page: number; x0: number; y0: number; el: HTMLDivElement } | null>(null)
  const [drawing, setDrawing] = useState<Redaction | null>(null)

  useEffect(() => {
    let active = true
    setPage(1); setPageCount(0); setSearchResults(null); setQuery(''); setViewerFailure(''); setViewerLoading(true)
    canvasRef.current?.scrollTo({ top: 0 })
    setViewingId('')
    viewerMetadata(doc.id).then(m => { if(active){setViewingId(m.viewingId ?? '');setPageCount(m.pageCount)} })
      .catch(e => {if(active)setViewerFailure(e instanceof Error ? e.message : 'Document unavailable')})
      .finally(() => {if(active)setViewerLoading(false)})
    return () => {active=false}
  }, [doc.id])
  useEffect(() => {
    const term=query.trim()
    if(!term){setSearchResults(null);setSearchError('');return}
    if(term.length>100){setSearchError('Search is limited to 100 characters');return}
    let active=true
    viewerSearch(doc.id,term).then(r=>{if(active){setSearchResults(r);setSearchError('')}})
      .catch(e=>{if(active){setSearchResults(null);setSearchError(e instanceof Error?e.message:'Search failed')}})
    return ()=>{active=false}
  },[doc.id,query])

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
    let frame = 0
    const sync = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        const top = root.getBoundingClientRect().top + root.clientHeight * .28
        let nearest = 1
        let distance = Number.POSITIVE_INFINITY
        sheetRefs.current.forEach((el, index) => {
          if (!el) return
          const r = el.getBoundingClientRect()
          const d = top < r.top ? r.top - top : top > r.bottom ? top - r.bottom : 0
          if (d < distance) { distance = d; nearest = index + 1 }
        })
        setPage(nearest)
      })
    }
    root.addEventListener('scroll', sync, { passive: true })
    sync()
    return () => { root.removeEventListener('scroll', sync); cancelAnimationFrame(frame) }
  }, [doc.id, pageTotal, zoom])

  useEffect(() => {
    const k = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !review && !infoOpen && !docsOpen) { if (redacting) setRedacting(false); else onClose() }
    }
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [onClose, redacting, review, infoOpen, docsOpen])

  const sheetWidth = zoom === 'width' ? Math.min(canvasSize.w - 24, 980) : zoom === 'page' ? Math.min((canvasSize.h - 96) * (8.5 / 11), canvasSize.w - 24) : 816 * zoom
  const zoomPct = Math.round((sheetWidth / 816) * 100)
  const stepZoom = (dir: 1 | -1) => setZoom(Math.min(2.5, Math.max(0.5, Math.round((sheetWidth / 816 + dir * 0.25) * 4) / 4)))
  const goPage = (p: number) => { const n = Math.min(pageTotal, Math.max(1, p)); sheetRefs.current[n - 1]?.scrollIntoView({ behavior: 'smooth', block: 'start' }); setPage(n) }

  const matchCount = searchResults?.totalMatches ?? 0
  useEffect(() => { const first=searchResults?.pages[0]?.pageNumber; if(first) goPage(first) }, [searchResults])
  useEffect(() => {
    const nav = document.querySelector('.vw-docs') as HTMLElement | null
    const active = nav?.querySelector('.vw-thumbs [aria-current="true"]') as HTMLElement | null
    if (!nav || !active) return
    const navBox = nav.getBoundingClientRect(), item = active.getBoundingClientRect()
    if (item.top < navBox.top || item.bottom > navBox.bottom) nav.scrollTop += item.top - navBox.top - nav.clientHeight / 2
  }, [page])

  // Actions are only shown to roles that have them; the server still decides. A refusal reads in plain words.
  const failed = (title: string, e: unknown) => toast(isRefused(e) ? { tone: 'bad', ...REFUSED } : { tone: 'bad', title, detail: errorText(e) })

  const [actionBusy,setActionBusy] = useState(false)
  const [redactionFailure,setRedactionFailure] = useState('')
  const [renderRevision,setRenderRevision] = useState(0)
  const download = async () => {
    if(actionBusy)return
    setActionBusy(true)
    try {
      const blob=await secureDownload(doc.id)
      const url=URL.createObjectURL(blob)
      const a=document.createElement('a')
      a.href=url;a.download=doc.filename;a.click()
      window.setTimeout(()=>URL.revokeObjectURL(url),30000)
      toast({title:'Document downloaded'})
    } catch(e){failed('Download failed',e)}
    finally{setActionBusy(false)}
  }
  const print = async () => {
    if(actionBusy)return
    setActionBusy(true)
    try {
      // Print the authorized rendered pages, without requesting the raw PDF download.
      const images=await Promise.all(Array.from({length:pageTotal},(_,i)=>viewerPage(doc.id,i+1,1,viewingId||undefined)))
      const urls=images.map(b=>URL.createObjectURL(b))
      const frame=document.createElement('iframe')
      frame.setAttribute('title','Print document')
      frame.style.position='fixed';frame.style.width='0';frame.style.height='0';frame.style.border='0'
      document.body.appendChild(frame)
      const win=frame.contentWindow
      if(!win)throw new Error('Print view unavailable')
      win.document.open()
      win.document.write('<!doctype html><html><head><title>Print document</title><style>body{margin:0}img{display:block;width:100%;page-break-after:always;break-after:page}@page{margin:0}</style></head><body>'+urls.map(u=>'<img src="'+u+'"/>').join('')+'</body></html>')
      win.document.close()
      await Promise.all(Array.from(win.document.images).map(image=>image.decode()))
      await recordPrintEvent(doc.id)
      win.focus();win.print()
      window.setTimeout(()=>{frame.remove();urls.forEach(u=>URL.revokeObjectURL(u))},60000)
    }catch(e){failed('Print failed',e)}
    finally{setActionBusy(false)}
  }
  const startRedact = () => {
    if(actionBusy)return
    setMarks([]);setRedactionFailure('');setRedacting(true);setInfoOpen(false)
  }

  const toPct = (e: RPointerEvent, el: HTMLDivElement) => { const r = el.getBoundingClientRect(); return { x: ((e.clientX - r.left) / r.width) * 100, y: ((e.clientY - r.top) / r.height) * 100 } }
  const onDown = (p: number) => (e: RPointerEvent<HTMLDivElement>) => {
    if (!redacting || (e.target as HTMLElement).closest('button')) return
    if (e.button !== 0) return
    const { x, y } = toPct(e, e.currentTarget)
    if(x < 0 || y < 0 || x > 100 || y > 100)return
    e.preventDefault()
    e.currentTarget.setPointerCapture(e.pointerId)
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

  const applyRedaction = async (reason: string) => {
    if(actionBusy || !can('PERMANENT_REDACTION'))return
    setActionBusy(true);setRedactionFailure('')
    try {
      await permanentRedaction(doc.id,reason,marks.map(m=>({pageNumber:m.page,x:m.x/100,y:m.y/100,width:m.w/100,height:m.h/100})))
      setMarks([]);setRedacting(false);setReview(false);setQuery('');setSearchResults(null)
      setRenderRevision(n=>n+1)
      setPageCount(0)
      viewerMetadata(doc.id).then(m=>{setViewingId(m.viewingId ?? '');setPageCount(m.pageCount)}).catch(e=>setViewerFailure(String(e)))
      toast({title:'Permanent corrective redaction completed'})
    }catch(e){setRedactionFailure(errorText(e)); if(isRefused(e))failed('Redaction failed',e)}
    finally{setActionBusy(false)}
    return


  }

  const [recordLoading, setRecordLoading] = useState(false)
  const switchRecord = async (dir: 1 | -1) => {
    const next = results[recIndex + dir]
    if (!next || recordLoading) return
    setRecordLoading(true)
    try {
      const loaded = next.docs.length ? next : toRecord(await fetchRecord(next.ref))
      if (!loaded.docs.length) { toast({ tone: 'bad', title: 'This record has no viewable documents' }); return }
      setRecords(prev => prev.map(r => r.ref === loaded.ref ? loaded : r))
      setSelectedRef(loaded.ref)
      setOpenDocId(loaded.docs[0].id)
    } catch (e) { toast({ tone: 'bad', title: 'Unable to open next record', detail: String(e) }) }
    finally { setRecordLoading(false) }
  }
  const switchDoc = (id: string) => { setOpenDocId(id); if(!liveSession)addDocActivity(id, 'DOCUMENT_VIEWED'); setDocsOpen(false) }

  // Real page previews, loaded in chunks of PREVIEW_CHUNK only for pages whose thumbnail is on screen
  // (so only while the column or the phone drawer is open). On failure the list falls back to plain boxes.
  const [previews, setPreviews] = useState<Record<number, string>>({})
  const [previewsFailed, setPreviewsFailed] = useState(false)
  const requestedChunks = useRef(new Set<number>())
  useEffect(() => { setPreviews({}); setPreviewsFailed(false); requestedChunks.current = new Set() }, [doc.id, renderRevision])
  const needPreview = (p: number) => {
    if (previewsFailed || !pageTotal) return
    const first = Math.floor((p - 1) / PREVIEW_CHUNK) * PREVIEW_CHUNK + 1
    if (requestedChunks.current.has(first)) return
    requestedChunks.current.add(first)
    const forDoc = doc.id
    viewerPreviews(forDoc, first, Math.min(PREVIEW_CHUNK, pageTotal - first + 1))
      .then((r) => { if (forDoc === doc.id) setPreviews((prev) => ({ ...prev, ...Object.fromEntries(r.previews.map((x) => [x.pageNumber, x.dataUrl])) })) })
      .catch(() => setPreviewsFailed(true))
  }

  // The left column folds away on wide screens; the choice is remembered in this browser.
  const [folded, setFolded] = useState(readFolded)
  const setFold = (next: boolean) => { setFolded(next); try { window.localStorage.setItem(DOCS_FOLDED_KEY, String(next)) } catch { /* storage unavailable */ } }

  const title = recordTitle || record.title
  const kind = docKind(doc.filename)

  const docsNav = (inDrawer: boolean) => (
    <>
      <div className="stack" style={{ gap: 8 }}>
        <div className="row between">
          <span className="eyebrow">Record {recIndex >= 0 ? `${recIndex + 1} of ${results.length}` : ''}</span>
          <span className="row" style={{ gap: 2 }}>
            <button type="button" className="v-btn quiet icon sm" disabled={recordLoading || recIndex <= 0} onClick={() => switchRecord(-1)} aria-label="Previous record"><ChevronLeft size={16} /></button>
            <button type="button" className="v-btn quiet icon sm" disabled={recordLoading || recIndex < 0 || recIndex >= results.length - 1} onClick={() => switchRecord(1)} aria-label="Next record"><ChevronRight size={16} /></button>
            {!inDrawer && <button type="button" className="v-btn quiet icon sm" onClick={() => setFold(true)} aria-label="Hide document list" title="Hide document list"><PanelLeftClose size={16} /></button>}
          </span>
        </div>
        <strong style={{ fontSize: 14 }}>{title}</strong>
        <div className="ws-ids stacked">{record.identifiers.map((i) => <div key={i.label}><span>{i.label}</span><strong style={{ fontSize: 13 }} title={i.value}>{i.value}</strong></div>)}</div>
      </div>
      <div className="stack" style={{ gap: 6 }}>
        <span className="eyebrow">Documents · {record.docs.length}</span>
        {record.docs.map((d) => (
          <button key={d.id} type="button" className="vw-thumb" aria-current={d.id === doc.id} onClick={() => switchDoc(d.id)}>
            <i aria-hidden /><span className="grow" style={{ minWidth: 0 }}><strong className="truncate" style={{ display: 'block', fontWeight: 500, fontSize: 13 }}>{docKind(d.filename)}</strong>{d.id === doc.id && pageTotal ? `${pageTotal} page${pageTotal === 1 ? '' : 's'} · ` : ''}{fmtDate(d.ingestedAt)}</span>
          </button>
        ))}
      </div>
      {pageTotal > 1 && (
        <div className="stack" style={{ gap: 6 }}>
          <span className="eyebrow">Pages</span>
          <div className="vw-pages">
            {Array.from({ length: pageTotal }, (_, i) => (
              <PageThumb key={doc.id + ':' + renderRevision + ':' + (i + 1)} page={i + 1} current={page === i + 1} src={previews[i + 1]} failed={previewsFailed}
                onVisible={needPreview} onOpen={() => { goPage(i + 1); setDocsOpen(false) }} />
            ))}
          </div>
        </div>
      )}
    </>
  )

  return (
    <div className="vw" role="dialog" aria-modal="true" aria-label={`${kind}, ${record.identifiers[0].value}`}>
      <div className="vw-bar">
        <button type="button" className="v-btn quiet sm vw-back" onClick={onClose} aria-label={`Back to ${backLabel.toLowerCase()}`}><ArrowLeft size={18} aria-hidden /><span className="vw-label">{backLabel}</span></button>
        <span className="vw-sep vw-hide-sm" aria-hidden />
        <button type="button" className="vw-title grow" style={{ border: 0, background: 'none', textAlign: 'left', cursor: 'pointer' }} onClick={() => setDocsOpen(true)} aria-label={`${kind} · ${title}. Switch document`}>
          <strong className="truncate">{kind} · {title}</strong>
          <span className="truncate mono-ish">{record.identifiers[0].value} · {doc.filename} · {docIndex + 1} of {record.docs.length} document{record.docs.length === 1 ? '' : 's'}</span>
        </button>
        <div className="vw-group">
          <button type="button" className="v-btn quiet sm" aria-pressed={findOpen} onClick={() => setFindOpen((f) => !f)} aria-label="Find in document"><Search size={16} aria-hidden /><span className="vw-label">Find</span></button>
          {can('DOWNLOAD_CURRENT_PDF') && <button type="button" className="v-btn sm vw-hide-sm" disabled={actionBusy} onClick={download}><Download size={16} aria-hidden />Download</button>}
          {can('PRINT_DOCUMENT') && <button type="button" className="v-btn sm vw-hide-sm" disabled={actionBusy} onClick={print}><Printer size={16} aria-hidden />Print</button>}
          {can('PERMANENT_REDACTION') && <button type="button" className="v-btn sm vw-redact" disabled={actionBusy} aria-pressed={redacting} onClick={startRedact} aria-label="Redact"><Eraser size={16} aria-hidden /><span className="vw-label">Redact</span></button>}
          <button type="button" className="v-btn quiet icon vw-panel-toggle" aria-pressed={infoOpen} onClick={() => setInfoOpen(true)} aria-label="Document information"><PanelRight size={18} /></button>
        </div>
      </div>
      {findOpen && (
        <div className="vw-bar" style={{ minHeight: 52 }}>
          <Search size={16} aria-hidden className="faint" style={{ marginLeft: 8 }} />
          <input className="v-input grow" autoFocus placeholder="Find text in this document" value={query} onChange={(e) => setQuery(e.target.value)} maxLength={100} aria-label="Find in document" style={{ border: 0, boxShadow: 'none', background: 'transparent' }} />
          <span className="faint" style={{ fontSize: 13, whiteSpace: 'nowrap' }} aria-live="polite">{searchError || (query ? `${matchCount} match${matchCount === 1 ? '' : 'es'}` : '')}</span>
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
        {folded
          ? <div className="vw-docs-rail"><button type="button" className="v-btn quiet icon sm" onClick={() => setFold(false)} aria-label="Show document list" title="Show document list"><PanelLeftOpen size={16} /></button></div>
          : <nav className="vw-docs" aria-label="Record documents">{docsNav(false)}</nav>}
        <div className="vw-stage">
        <div className="vw-canvas" ref={canvasRef} style={{ position: 'relative' }}>
          {viewerFailure && <div className="v-callout" role="alert">{viewerFailure}</div>}
          {viewerLoading && <div className="v-callout" role="status">Opening secure document…</div>}
          {Array.from({ length: pageTotal }, (_, i) => {
            const p=i+1
            return <div key={p} data-page={p} ref={el => {sheetRefs.current[i]=el}} className="vw-sheet"
              style={{width:sheetWidth,maxWidth:'none',position:'relative',minHeight:Math.round(sheetWidth*1.25)}}
              aria-label={`Page ${p} of ${pageTotal}`}>
              <SecurePage key={doc.id+':'+renderRevision+':'+p} documentId={doc.id} viewingId={viewingId} page={p} scale={Math.min(2.5,Math.max(.5,sheetWidth/816))}
                scrollRoot={canvasRef} matches={searchResults?.pages.find(x=>x.pageNumber===p)?.matchCount ?? 0}
                highlights={searchResults?.highlights?.filter(h=>h.pageNumber===p) ?? []}
                redacting={redacting} marks={marks.filter(m=>m.page===p)} drawing={drawing?.page===p?drawing:null}
                onPointerDown={onDown(p)} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onUp}/>

            </div>
          })}
        </div>
          {redacting ? (
            <div className="vw-redact-bar" style={{ width: 'min(620px, 100%)' }}>
              <span className="grow" style={{ fontSize: 14 }}><strong>{marks.length}</strong> region{marks.length === 1 ? '' : 's'} marked</span>
              <button type="button" className="v-btn sm" onClick={() => { setRedacting(false); setMarks([]) }}>Cancel</button>
              <button type="button" className="v-btn danger sm" disabled={marks.length === 0} onClick={() => setReview(true)}>Review</button>
            </div>
          ) : (
            <div className="vw-redact-bar" style={{ padding: 6, gap: 2, background: 'var(--surface)', color: 'var(--ink)', border: '1px solid var(--line)' }} role="toolbar" aria-label="Page and zoom">
              <button type="button" className="v-btn quiet icon sm" onClick={() => goPage(page - 1)} disabled={page <= 1} aria-label="Previous page"><ChevronLeft size={16} /></button>
              <span className="vw-page"><input value={page} inputMode="numeric" aria-label="Page number" onChange={(e) => { const n = Number(e.target.value); if (n) goPage(n) }} />/ {pageTotal}</span>
              <button type="button" className="v-btn quiet icon sm" onClick={() => goPage(page + 1)} disabled={page >= pageTotal} aria-label="Next page"><ChevronRight size={16} /></button>
              <span className="vw-sep" aria-hidden />
              <button type="button" className="v-btn quiet icon sm" onClick={() => stepZoom(-1)} aria-label="Zoom out"><Minus size={16} /></button>
              <span style={{ fontSize: 13, minWidth: 44, textAlign: 'center', fontVariantNumeric: 'tabular-nums' }}>{zoomPct}%</span>
              <button type="button" className="v-btn quiet icon sm" onClick={() => stepZoom(1)} aria-label="Zoom in"><Plus size={16} /></button>
              <button type="button" className="v-btn quiet sm" aria-pressed={zoom === 'width'} onClick={() => setZoom(zoom === 'width' ? 'page' : 'width')} aria-label={zoom === 'width' ? 'Fit page' : 'Fit width'}><Maximize2 size={14} aria-hidden /><span className="vw-hide-sm">{zoom === 'width' ? 'Fit width' : zoom === 'page' ? 'Fit page' : 'Fit'}</span></button>
            </div>
          )}
        </div>
        <aside className="vw-panel" aria-label="Document information"><DocInfo doc={{...doc,pages:pageTotal}} tab={infoTab} setTab={setInfoTab} /></aside>
      </div>

      {infoOpen && (
        <Drawer eyebrow={doc.kind} title="Document information" onClose={() => setInfoOpen(false)}>
          {(can('DOWNLOAD_CURRENT_PDF') || can('PRINT_DOCUMENT')) && (
            <div className="row wrap vw-actions-sm" style={{ gap: 8 }}>
              {can('DOWNLOAD_CURRENT_PDF') && <button type="button" className="v-btn sm" disabled={actionBusy} onClick={download}><Download size={16} aria-hidden />Download</button>}
              {can('PRINT_DOCUMENT') && <button type="button" className="v-btn sm" disabled={actionBusy} onClick={print}><Printer size={16} aria-hidden />Print</button>}
            </div>
          )}
          <DocInfo doc={{...doc,pages:pageTotal}} tab={infoTab} setTab={setInfoTab} embedded />
        </Drawer>
      )}
      {docsOpen && <Drawer eyebrow="Record documents" title={record.identifiers[0].value} onClose={() => setDocsOpen(false)}>{docsNav(true)}</Drawer>}
      {review && <RedactionReview doc={doc} marks={marks} onCancel={() => setReview(false)} onApply={applyRedaction} busy={actionBusy} failure={redactionFailure} />}
    </div>
  )
}

function SecurePage({documentId,viewingId,page,scale,scrollRoot,matches,highlights,redacting,marks,drawing,onPointerDown,onPointerMove,onPointerUp,onPointerCancel}:{
  documentId:string;viewingId:string;page:number;scale:number;scrollRoot:React.RefObject<HTMLDivElement>;
  matches:number;highlights:{pageNumber:number;x:number;y:number;width:number;height:number}[];
  redacting:boolean;marks:Redaction[];drawing:Redaction|null;
  onPointerDown:(e:RPointerEvent<HTMLDivElement>)=>void;onPointerMove:(e:RPointerEvent<HTMLDivElement>)=>void;
  onPointerUp:()=>void;onPointerCancel:()=>void
}) {
  const container=useRef<HTMLDivElement>(null)
  const [visible,setVisible]=useState(false)
  const [url,setUrl]=useState('')
  const [error,setError]=useState('')
  useEffect(()=>{
    const el=container.current
    const root=scrollRoot.current
    if(!el||!root)return
    const obs=new IntersectionObserver(entries=>{if(entries.some(e=>e.isIntersecting))setVisible(true)},{
      root,rootMargin:'900px 0px'
    })
    obs.observe(el)
    return ()=>obs.disconnect()
  },[scrollRoot,documentId,page])
  useEffect(()=>{
    if(!visible)return
    let active=true
    viewerPage(documentId,page,Number(scale.toFixed(2)),viewingId||undefined).then(blob=>{
      const objectUrl=URL.createObjectURL(blob)
      if(active)setUrl(objectUrl)
      else URL.revokeObjectURL(objectUrl)
    }).catch(e=>{if(active)setError(e instanceof Error?e.message:'Page failed to load')})
    return ()=>{active=false}
  },[visible,documentId,page,scale,viewingId])
  useEffect(()=>()=>{if(url)URL.revokeObjectURL(url)},[url])
  return <div ref={container} style={{width:'100%',minHeight:'inherit'}}>
    {url ? <div style={{position:'relative',width:'100%',lineHeight:0,touchAction:redacting?'none':'auto',cursor:redacting?'crosshair':'auto'}}
      onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerCancel}>
      <img src={url} alt={`Document page ${page}`} style={{width:'100%',height:'auto',display:'block'}}/>
      {marks.map((m,i)=><div key={'mark-'+i} className="vw-mark" style={{position:'absolute',left:m.x+'%',top:m.y+'%',width:m.w+'%',height:m.h+'%',pointerEvents:'none'}}/>)}
      {drawing && <div className="vw-mark" style={{position:'absolute',left:drawing.x+'%',top:drawing.y+'%',width:drawing.w+'%',height:drawing.h+'%',pointerEvents:'none'}}/>}
      {highlights.map((h,i)=><div key={i} aria-hidden style={{position:'absolute',pointerEvents:'none',left:`${h.x*100}%`,top:`${h.y*100}%`,
        width:`${h.width*100}%`,height:`${h.height*100}%`,background:'rgba(255,213,47,.45)'}}/>)}
    </div> : <div className="v-callout" role="status">{error || `Loading page ${page}…`}</div>}
    {matches>0 && <span className="sr-only">{matches} text matches on page {page}</span>}
  </div>
}

function PageThumb({ page, current, src, failed, onVisible, onOpen }: { page: number; current: boolean; src?: string; failed: boolean; onVisible: (p: number) => void; onOpen: () => void }) {
  const ref = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    const el = ref.current
    if (!el || src || failed) return
    const obs = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) onVisible(page) }, { rootMargin: '120px 0px' })
    obs.observe(el)
    return () => obs.disconnect()
  }, [page, src, failed])
  return (
    <button ref={ref} type="button" className="vw-pagethumb" aria-current={current} aria-label={`Page ${page}`} onClick={onOpen}>
      {src ? <img src={src} alt="" /> : <span className="vw-pagebox" aria-hidden>{failed ? `Page ${page}` : ''}</span>}
      <span className="vw-pagenum" aria-hidden>{page}</span>
    </button>
  )
}

function RedactionReview({ doc, marks, onCancel, onApply, busy, failure }: { doc: Doc; marks: Redaction[]; onCancel: () => void; onApply: (reason: string) => void; busy:boolean; failure:string }) {
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const pages = [...new Set(marks.map((m) => m.page))].sort((a, b) => a - b)
  const ready = reason.trim().length >= 10 && confirmed
  return (
    <Dialog title="Apply permanent redaction" onClose={onCancel}
      footer={<><button type="button" className="v-btn" onClick={onCancel}>Back to marking</button><button type="button" className="v-btn danger" disabled={!ready || busy} onClick={() => onApply(reason.trim())}>{busy?'Applying…':'Apply permanent redaction'}</button></>}>
      <Callout tone="bad" title="This permanently replaces the current PDF">
        Marked areas are removed for every user and the original is not kept. Use this only to remove content that should never have been stored.
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
      {failure && <Callout tone="warn" title="Redaction failed">{failure}</Callout>}
    </Dialog>
  )
}

function DocInfo({ doc, tab, setTab, embedded }: { doc: Doc; tab: InfoTab; setTab: (t: InfoTab) => void; embedded?: boolean }) {
  const { can, role, updateDoc, addDocActivity, toast, liveSession } = useStore()
  const [comment, setComment] = useState('')
  const [tag, setTag] = useState('')
  const [liveEvents,setLiveEvents] = useState<LiveDocumentActivity[]>([])
  const [includeViews,setIncludeViews] = useState(false)
  const [activityError,setActivityError] = useState('')
  useEffect(()=>{if(!liveSession || tab!=='activity')return;let active=true;setActivityError('');documentActivity(doc.id,includeViews).then(rows=>{if(active)setLiveEvents(rows)}).catch(e=>{if(active){setLiveEvents([]);setActivityError(String(e))}});return()=>{active=false}},[liveSession,tab,doc.id,includeViews])
  const [liveTags,setLiveTags] = useState<LiveTag[]>([])
  const [liveComments,setLiveComments] = useState<LiveComment[]>([])
  const [metadataLoading,setMetadataLoading] = useState(false)
  const [metadataError,setMetadataError] = useState('')
  const [saving,setSaving] = useState(false)
  useEffect(()=>{
    if(!liveSession || (tab!=='comments' && tab!=='tags')) return
    let active=true
    setMetadataLoading(true);setMetadataError('')
    const task=tab==='comments'?documentComments(doc.id):documentTags(doc.id)
    task.then(items=>{if(!active)return;if(tab==='comments')setLiveComments(items as LiveComment[]);else setLiveTags(items as LiveTag[])})
      .catch(e=>{if(active)setMetadataError(e instanceof Error?e.message:'Unable to load document data')})
      .finally(()=>{if(active)setMetadataLoading(false)})
    return()=>{active=false}
  },[liveSession,tab,doc.id])
  // Load comments when a document opens so the Comments tab shows its count (reading comments is not audited).
  useEffect(()=>{
    if(!liveSession)return
    let active=true
    documentComments(doc.id).then(items=>{if(active)setLiveComments(items)}).catch(()=>{})
    return()=>{active=false}
  },[liveSession,doc.id])
  const submitLiveComment=async()=>{
    const value=comment.trim()
    if(!value || saving)return
    setSaving(true);setMetadataError('')
    try{
      const saved=await createDocumentComment(doc.id,value)
      setLiveComments(items=>[...items,saved]);setComment('')
      toast({title:'Comment added'})
    }catch(e){setMetadataError(errorText(e))}
    finally{setSaving(false)}
  }
  const submitLiveTag=async()=>{
    const value=tag.trim()
    if(!value || saving)return
    if(liveTags.some(item=>item.tag.toLowerCase()===value.toLowerCase())){setMetadataError('Tag already applied');return}
    setSaving(true);setMetadataError('')
    try{
      const saved=await createDocumentTag(doc.id,value)
      setLiveTags(items=>[...items,saved]);setTag('')
      toast({title:'Tag added'})
    }catch(e){setMetadataError(errorText(e))}
    finally{setSaving(false)}
  }
  const suggestsSsn = !liveSession && (PAGE_TEXT[doc.kind] ?? []).some((t) => SSN.test(t)) && doc.redactions.length === 0
  const body = (
    <>
      {tab === 'ask' && showDevelopmentUtilities && (
        <section className="home-card home-ask">
          <h2><Sparkles size={16} aria-hidden />Ask and Key terms <Badge tone="ok">Later release</Badge></h2>
          <p className="muted">Development marker: questions about this document and its key terms will sit here.</p>
        </section>
      )}
      {tab === 'details' && (
        <>
          {suggestsSsn && can('PERMANENT_REDACTION') && <Callout tone="warn" title="Possible sensitive identifier on page 1">Sample content includes a full SSN. Stewards can correct this with permanent redaction.</Callout>}
          <dl className="v-kv">
            <div><dt>File name</dt><dd className="mono">{doc.filename}</dd></div>
            <div><dt>Document ID</dt><dd className="mono">{doc.id}</dd></div>
            <div><dt>Submission</dt><dd className="mono">{doc.submissionId}</dd></div>
            <div><dt>Pages</dt><dd>{doc.pages || 'Not available'}</dd></div>
            <div><dt>Ingested</dt><dd>{fmtDateTime(doc.ingestedAt)}</dd></div>
            <div><dt>Retained until</dt><dd>{doc.expiresAt ? fmtDate(doc.expiresAt) : 'Not specified'}{!liveSession && <span className="faint"> · policy v{RETENTION.active.version} at ingestion</span>}</dd></div>
            <div><dt>Protection</dt><dd>Encrypted at rest · served page-by-page to authorized sessions</dd></div>
            {doc.redactions.length > 0 && <div><dt>Corrections</dt><dd><Badge tone="warn">{doc.redactions.length} simulated redaction{doc.redactions.length === 1 ? '' : 's'}</Badge></dd></div>}
          </dl>
        </>
      )}
      {tab === 'comments' && (
        <>
          {liveSession && metadataLoading && <p className="faint">Loading comments…</p>}
          {liveSession && metadataError && <Callout tone="warn" title={metadataError.startsWith(REFUSED.title) ? REFUSED.title : 'Comments unavailable'}>{metadataError.startsWith(REFUSED.title) ? REFUSED.detail : metadataError}</Callout>}
          {liveSession && !metadataLoading && !metadataError && liveComments.length === 0 && <p className="faint">No comments yet.</p>}
          {liveSession && liveComments.map(c=><article key={c.commentId} className="v-comment"><header><strong>{c.createdBy}</strong><span>{fmtRel(c.createdAt)}</span></header><p>{c.comment}</p></article>)}
          {liveSession && can('ADD_DOCUMENT_COMMENT') && <form className="stack" style={{gap:8}} onSubmit={e=>{e.preventDefault();void submitLiveComment()}}>
            <textarea className="v-textarea" aria-label="New comment" value={comment} maxLength={BUSINESS_LIMITS.maxCommentLength} onChange={e=>setComment(e.target.value)} placeholder="Add a comment for other authorized users"/>
            <button type="submit" className="v-btn sm" style={{alignSelf:'flex-end'}} disabled={!comment.trim() || saving}>Add comment</button>
          </form>}
          {!liveSession && doc.comments.map((c) => <article key={c.id} className="v-comment"><header><strong>{c.by}</strong><span>{fmtRel(c.at)}</span></header><p>{c.text}</p></article>)}
          {!liveSession && can('ADD_DOCUMENT_COMMENT') ? (
            <form className="stack" style={{ gap: 8 }} onSubmit={(e) => {
              e.preventDefault(); if (!comment.trim()) return
              updateDoc(doc.id, (d) => ({ ...d, comments: [...d.comments, { id: `sim-c${Date.now()}`, text: comment.trim(), by: role.person, at: new Date().toISOString() }] }))
              addDocActivity(doc.id, 'DOCUMENT_COMMENT_ADDED'); setComment(''); toast({ title: 'Comment added (simulated)', detail: 'Visible in this tab only.' })
            }}>
              <textarea className="v-textarea" aria-label="New comment" value={comment} maxLength={BUSINESS_LIMITS.maxCommentLength} onChange={(e) => setComment(e.target.value)} placeholder="Add a comment for other authorized users" />
              <button type="submit" className="v-btn sm" style={{ alignSelf: 'flex-end' }} disabled={!comment.trim()}>Add comment</button>
            </form>
          ) : null}
        </>
      )}
      {tab === 'tags' && (
        <>
          {liveSession && metadataLoading && <p className="faint">Loading tags…</p>}
          {liveSession && metadataError && <Callout tone="warn" title={metadataError.startsWith(REFUSED.title) ? REFUSED.title : 'Tags unavailable'}>{metadataError.startsWith(REFUSED.title) ? REFUSED.detail : metadataError}</Callout>}
          <div className="v-tagrow">{liveSession ? (liveTags.length===0 ? <span className="faint">No tags yet.</span> : liveTags.map(t=><Badge key={t.tagId} tone="outline">{t.tag}</Badge>)) : doc.tags.length === 0 ? <span className="faint">No tags yet.</span> : doc.tags.map((t) => <Badge key={t.id} tone="outline">{t.tag}</Badge>)}</div>
          {liveSession && can('ADD_DOCUMENT_TAG') && <form className="row" onSubmit={e=>{e.preventDefault();void submitLiveTag()}}>
            <input className="v-input grow" aria-label="New tag" value={tag} maxLength={BUSINESS_LIMITS.maxTagLength} onChange={e=>setTag(e.target.value)} placeholder="Add tag"/>
            <button type="submit" className="v-btn" disabled={!tag.trim() || saving}>Add</button>
          </form>}
          {!liveSession && can('ADD_DOCUMENT_TAG') ? (
            <form className="row" onSubmit={(e) => {
              e.preventDefault(); const v = tag.trim(); if (!v) return
              if (doc.tags.some((t) => t.tag.toLowerCase() === v.toLowerCase())) { toast({ tone: 'bad', title: 'Tag already applied' }); return }
              updateDoc(doc.id, (d) => ({ ...d, tags: [...d.tags, { id: `sim-t${Date.now()}`, tag: v, by: role.person, at: new Date().toISOString() }] }))
              addDocActivity(doc.id, 'DOCUMENT_TAG_ADDED'); setTag('')
            }}>
              <input className="v-input grow" aria-label="New tag" value={tag} maxLength={BUSINESS_LIMITS.maxTagLength} onChange={(e) => setTag(e.target.value)} placeholder="Add tag" />
              <button type="submit" className="v-btn" disabled={!tag.trim()}>Add</button>
            </form>
          ) : null}
        </>
      )}
      {tab === 'activity' && (<>
        {liveSession && <label className="v-check"><input type="checkbox" checked={includeViews} onChange={e=>setIncludeViews(e.target.checked)}/>Include document view activity</label>}
        {activityError && <Callout tone="warn" title="Activity unavailable">{activityError}</Callout>}
        {liveSession && !activityError && liveEvents.length===0 && <p className="faint">No matching activity.</p>}
        <ol className="v-timeline">
          {liveSession && liveEvents.map(a=><li key={a.auditEventId}><span className={`pt ${a.outcome==='SUCCESS'?'ok':'bad'}`} aria-hidden/><div className="stack" style={{gap:0}}><strong>{auditEventLabel(a.action,a.safeDetailsJson,doc.filename,true)}</strong><span>{a.actorId} · {fmtDateTime(a.occurredAt)}</span></div></li>)}
          {!liveSession && doc.activity.map((a) => (
            <li key={a.id}><span className={`pt ${a.simulated ? 'sim' : a.outcome === 'SUCCESS' ? 'ok' : 'bad'}`} aria-hidden />
              <div className="stack" style={{ gap: 0 }}><strong>{humanize(a.action.replace('DOCUMENT_', ''))}</strong><span>{a.actor} · {fmtDateTime(a.at)}{a.simulated ? ' · simulated' : ''}</span></div>
            </li>
          ))}
        </ol>
      </>)}
    </>
  )
  return (
    <>
      <Tabs label="Document information" value={tab} onChange={(t) => setTab(t as InfoTab)} tabs={[...(showDevelopmentUtilities ? [{ id: 'ask', label: 'Ask' }] : []), { id: 'details', label: 'Details' }, { id: 'comments', label: `Comments${(liveSession?liveComments.length:doc.comments.length) ? ` ${liveSession?liveComments.length:doc.comments.length}` : ''}` }, { id: 'tags', label: 'Tags' }, { id: 'activity', label: 'Activity' }]} />
      {embedded ? <div className="stack-lg">{body}</div> : <div className="vw-panel-body">{body}</div>}
      {!embedded && <div style={{ padding: 12, borderTop: '1px solid var(--line)' }} className="faint"><Info size={13} aria-hidden style={{ verticalAlign: '-2px', marginRight: 6 }} /><small>Every view, download, print and correction is audited.</small></div>}
    </>
  )
}
