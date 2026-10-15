import {
  FormEvent,
  PointerEvent as ReactPointerEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { contentApi, contentBinary, DocumentComment, DocumentTag, RecordDocument } from './api'
import Icon from './Icon'

type ViewerMetadata = {
  documentId: string
  originalFilename: string
  pageCount: number
}

type PageMatch = {
  pageNumber: number
  matchCount: number
}

type SearchResults = {
  pageCount: number
  totalMatches: number
  pages: PageMatch[]
}

type RedactionMark = {
  id: string
  pageNumber: number
  x: number
  y: number
  width: number
  height: number
}

type RedactionResult = {
  operationId: string
  documentId: string
  status: string
  regionCount: number
  contentSha256: string
  expiresAt: string | null
}

type Props = {
  documentId: string
  filename: string
  documents?: RecordDocument[]
  selectedDocumentId?: string
  onSelectDocument?: (documentId: string) => void
  persona: 'business' | 'steward'
  canRedact: boolean
  canDownload: boolean
  canPrint: boolean
  canTag: boolean
  canComment: boolean
  embedded?: boolean
  onClose: () => void
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

export function PdfViewer({ documentId, filename, documents = [], selectedDocumentId, onSelectDocument, persona, canRedact, canDownload, canPrint, canTag, canComment, embedded = false, onClose }: Props) {
  const [metadata, setMetadata] = useState<ViewerMetadata | null>(null)
  const [metadataLoading, setMetadataLoading] = useState(true)
  const [page, setPage] = useState(1)
  const [jump, setJump] = useState('1')
  const [zoom, setZoom] = useState(1)
  const [imageUrl, setImageUrl] = useState('')
  const [loadingPage, setLoadingPage] = useState(false)
  const [query, setQuery] = useState('')
  const [matches, setMatches] = useState<PageMatch[]>([])
  const [totalMatches, setTotalMatches] = useState(0)
  const [searching, setSearching] = useState(false)
  const [error, setError] = useState('')
  const [reloadToken, setReloadToken] = useState(0)

  const [redactionMode, setRedactionMode] = useState(false)
  const [redactionMarks, setRedactionMarks] = useState<RedactionMark[]>([])
  const [draftMark, setDraftMark] = useState<RedactionMark | null>(null)
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [redacting, setRedacting] = useState(false)
  const [redactionMessage, setRedactionMessage] = useState('')
  const [downloading, setDownloading] = useState(false)
  const [tags, setTags] = useState<DocumentTag[]>([])
  const [tagInput, setTagInput] = useState('')
  const [tagging, setTagging] = useState(false)
  const [tagPanelOpen, setTagPanelOpen] = useState(false)
  const [comments, setComments] = useState<DocumentComment[]>([])
  const [commentsLoading, setCommentsLoading] = useState(false)
  const [commentsError, setCommentsError] = useState('')
  const [commentInput, setCommentInput] = useState('')
  const [commenting, setCommenting] = useState(false)
  const [commentPanelOpen, setCommentPanelOpen] = useState(false)
  const [showAllComments, setShowAllComments] = useState(false)
  const [detailsPanelOpen, setDetailsPanelOpen] = useState(false)
  const [fitToPage, setFitToPage] = useState(true)
  const [pageSize, setPageSize] = useState<{ width: number; height: number } | null>(null)
  const [canvasSize, setCanvasSize] = useState({ width: 0, height: 0 })
  const imageRef = useRef<HTMLImageElement | null>(null)
  const canvasRef = useRef<HTMLDivElement | null>(null)
  const renderedScaleRef = useRef(1)
  const draftAnchor = useRef<{ x: number; y: number } | null>(null)

  const pageCount = metadata?.pageCount ?? 0
  const fitScale = useMemo(() => {
    if (!pageSize || !canvasSize.width) return null
    const wideWorkspace = canvasSize.width >= 1360
    const horizontalGutter = wideWorkspace ? 64 : 48
    const targetWidth = wideWorkspace
      ? Math.min(canvasSize.width - horizontalGutter, 1280)
      : canvasSize.width - horizontalGutter
    return clamp(targetWidth / pageSize.width, 0.5, 2.5)
  }, [pageSize, canvasSize.width])
  const displayScale = fitToPage && fitScale ? fitScale : zoom
  const renderScale = useMemo(() => {
    if (!fitToPage || !fitScale) return zoom
    const density = typeof window === 'undefined' ? 1 : Math.min(window.devicePixelRatio || 1, 2)
    return clamp(Math.ceil(fitScale * density * 4) / 4, 0.5, 2.5)
  }, [fitToPage, fitScale, zoom])
  const zoomPercent = useMemo(() => Math.round(displayScale * 100), [displayScale])
  const currentDocument = documents.find((item) => item.documentId === (selectedDocumentId ?? documentId))
  const formatSealDate = (value: string | null | undefined) => value ? new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' }).format(new Date(value)) : null
  const stepZoom = (delta: number) => {
    setFitToPage(false)
    setZoom(clamp(Math.round((displayScale + delta) * 4) / 4, 0.5, 2.5))
  }

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect
      setCanvasSize((current) => Math.abs(current.width - width) < 8 && Math.abs(current.height - height) < 8 ? current : { width, height })
    })
    observer.observe(canvas)
    return () => observer.disconnect()
  }, [])

  const measurePage = () => {
    const image = imageRef.current
    if (!image?.naturalWidth) return
    const width = image.naturalWidth / renderedScaleRef.current
    const height = image.naturalHeight / renderedScaleRef.current
    setPageSize((current) => current && Math.abs(current.width - width) < 1 && Math.abs(current.height - height) < 1 ? current : { width, height })
  }
  const currentMarks = redactionMarks.filter((mark) => mark.pageNumber === page)
  const visibleComments = showAllComments ? comments : comments.slice(0, 3)

  useEffect(() => {
    let active = true
    setMetadata(null)
    setPage(1)
    setJump('1')
    setZoom(1)
    setFitToPage(true)
    setPageSize(null)
    setMatches([])
    setTotalMatches(0)
    setError('')
    setCommentPanelOpen(false)
    setTagPanelOpen(false)
    setDetailsPanelOpen(false)
    setShowAllComments(false)

    setMetadataLoading(true)
    contentApi<ViewerMetadata>(`/api/v1/business/documents/${documentId}/viewer`, persona)
      .then((value) => {
        if (active) setMetadata(value)
      })
      .catch((reasonValue: unknown) => {
        if (active) setError(reasonValue instanceof Error ? reasonValue.message : 'Could not load document page information.')
      })
      .finally(() => {
        if (active) setMetadataLoading(false)
      })
    contentApi<DocumentTag[]>(`/api/v1/business/documents/${documentId}/tags`, persona)
      .then((value) => { if (active) setTags(value) })
      .catch(() => { if (active) setTags([]) })
    setComments([])
    setCommentsError('')
    contentApi<DocumentComment[]>(`/api/v1/business/documents/${documentId}/comments`, persona)
      .then((value) => { if (active) setComments(value) })
      .catch((reasonValue: unknown) => {
        if (active) setCommentsError(reasonValue instanceof Error ? reasonValue.message : 'Could not load document comments.')
      })
    return () => { active = false }
  }, [documentId, persona, reloadToken])

  useEffect(() => {
    if (!metadata) return
    let active = true
    let localUrl = ''
    setLoadingPage(true)
    setError('')

    contentBinary(
      `/api/v1/business/documents/${documentId}/viewer/pages/${page}?scale=${renderScale.toFixed(2)}`,
      persona,
    )
      .then((blob) => {
        if (!active) return
        renderedScaleRef.current = renderScale
        localUrl = URL.createObjectURL(blob)
        setImageUrl((previous) => {
          if (previous) URL.revokeObjectURL(previous)
          return localUrl
        })
      })
      .catch((reasonValue: unknown) => {
        if (active) setError(reasonValue instanceof Error ? reasonValue.message : 'Could not render this page.')
      })
      .finally(() => {
        if (active) setLoadingPage(false)
      })

    return () => {
      active = false
      if (localUrl) URL.revokeObjectURL(localUrl)
    }
  }, [documentId, metadata, page, renderScale, persona, reloadToken])

  useEffect(() => () => {
    if (imageUrl) URL.revokeObjectURL(imageUrl)
  }, [imageUrl])

  useEffect(() => {
    setJump(String(page))
    setDraftMark(null)
  }, [page])

  function goToPage(requested: number) {
    if (!metadata) return
    setPage(clamp(Math.trunc(requested), 1, metadata.pageCount))
  }

  function jumpToPage(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const requested = Number.parseInt(jump, 10)
    if (Number.isFinite(requested)) goToPage(requested)
  }

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = query.trim()
    if (!value) return

    setSearching(true)
    setError('')
    try {
      const response = await contentApi<SearchResults>(
        `/api/v1/business/documents/${documentId}/viewer/search?q=${encodeURIComponent(value)}`,
        persona,
      )
      setMatches(response.pages)
      setTotalMatches(response.totalMatches)
      if (response.pages.length > 0) goToPage(response.pages[0].pageNumber)
    } catch (reasonValue) {
      setError(reasonValue instanceof Error ? reasonValue.message : 'Document text search failed.')
    } finally {
      setSearching(false)
    }
  }


  async function downloadCurrent() {
    if (!canDownload || downloading) return
    setDownloading(true)
    setError('')
    try {
      const blob = await contentBinary(`/api/v1/business/documents/${documentId}/download`, persona)
      const url = URL.createObjectURL(blob)
      try {
        const anchor = document.createElement('a')
        anchor.href = url
        anchor.download = metadata?.originalFilename ?? filename
        anchor.rel = 'noopener'
        document.body.appendChild(anchor)
        anchor.click()
        anchor.remove()
      } finally {
        URL.revokeObjectURL(url)
      }
    } catch (reasonValue) {
      setError(reasonValue instanceof Error ? reasonValue.message : 'Controlled PDF download failed.')
    } finally {
      setDownloading(false)
    }
  }

  async function printCurrent() {
    if (!canPrint) return
    setError('')
    try {
      await contentApi(`/api/v1/business/documents/${documentId}/print-event`, persona, { method: 'POST' })
      window.print()
    } catch (reasonValue) {
      setError(reasonValue instanceof Error ? reasonValue.message : 'Print permission/audit failed.')
    }
  }

  async function addTag(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canTag || !tagInput.trim() || tagging) return
    setTagging(true)
    setError('')
    try {
      const created = await contentApi<DocumentTag>(`/api/v1/business/documents/${documentId}/tags`, persona, {
        method: 'POST',
        body: JSON.stringify({ tag: tagInput.trim() }),
      })
      setTags((current) => current.some((item) => item.tagId === created.tagId) ? current : [...current, created])
      setTagInput('')
    } catch (reasonValue) {
      setError(reasonValue instanceof Error ? reasonValue.message : 'Could not add tag.')
    } finally {
      setTagging(false)
    }
  }

  async function addComment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canComment || !commentInput.trim() || commenting) return
    setCommenting(true)
    setError('')
    try {
      const created = await contentApi<DocumentComment>(`/api/v1/business/documents/${documentId}/comments`, persona, {
        method: 'POST',
        body: JSON.stringify({ comment: commentInput.trim() }),
      })
      setComments((current) => [created, ...current])
      setCommentInput('')
    } catch (reasonValue) {
      setError(reasonValue instanceof Error ? reasonValue.message : 'Could not add comment.')
    } finally {
      setCommenting(false)
    }
  }

  function normalizedPoint(event: ReactPointerEvent<HTMLDivElement>) {
    const image = imageRef.current
    if (!image) return null
    const rect = image.getBoundingClientRect()
    if (rect.width <= 0 || rect.height <= 0) return null
    return {
      x: clamp((event.clientX - rect.left) / rect.width, 0, 1),
      y: clamp((event.clientY - rect.top) / rect.height, 0, 1),
    }
  }

  function startRedaction(event: ReactPointerEvent<HTMLDivElement>) {
    if (!redactionMode || event.button !== 0) return
    const point = normalizedPoint(event)
    if (!point) return
    event.currentTarget.setPointerCapture(event.pointerId)
    draftAnchor.current = point
    setDraftMark({
      id: crypto.randomUUID(),
      pageNumber: page,
      x: point.x,
      y: point.y,
      width: 0,
      height: 0,
    })
  }

  function moveRedaction(event: ReactPointerEvent<HTMLDivElement>) {
    if (!redactionMode || !draftMark) return
    const point = normalizedPoint(event)
    const anchor = draftAnchor.current
    if (!point || !anchor) return
    const x = Math.min(anchor.x, point.x)
    const y = Math.min(anchor.y, point.y)
    const width = Math.abs(point.x - anchor.x)
    const height = Math.abs(point.y - anchor.y)
    setDraftMark((current) => current ? { ...current, x, y, width, height } : current)
  }

  function finishRedaction(event: ReactPointerEvent<HTMLDivElement>) {
    if (!redactionMode || !draftMark) return
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
    if (draftMark.width >= 0.003 && draftMark.height >= 0.003) {
      setRedactionMarks((current) => [...current, draftMark])
    }
    setDraftMark(null)
    draftAnchor.current = null
  }

  function removeMark(id: string) {
    setRedactionMarks((current) => current.filter((mark) => mark.id !== id))
  }

  function cancelRedaction() {
    setRedactionMode(false)
    setDraftMark(null)
    draftAnchor.current = null
    setRedactionMarks([])
    setReason('')
    setConfirmed(false)
  }

  async function submitRedaction(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!canRedact || redactionMarks.length === 0 || !reason.trim() || !confirmed) return

    setRedacting(true)
    setError('')
    setRedactionMessage('')
    try {
      const result = await contentApi<RedactionResult>(
        `/api/v1/business/documents/${documentId}/redactions`,
        persona,
        {
          method: 'POST',
          body: JSON.stringify({
            reason: reason.trim(),
            confirmed: true,
            regions: redactionMarks.map(({ pageNumber, x, y, width, height }) => ({
              pageNumber, x, y, width, height,
            })),
          }),
        },
      )
      setRedactionMessage(`Permanent correction completed across ${result.regionCount} region${result.regionCount === 1 ? '' : 's'}.`)
      setRedactionMode(false)
      setRedactionMarks([])
      setDraftMark(null)
      setReason('')
      setConfirmed(false)
      setQuery('')
      setMatches([])
      setTotalMatches(0)
      setReloadToken((current) => current + 1)
    } catch (reasonValue) {
      setError(reasonValue instanceof Error ? reasonValue.message : 'Permanent corrective redaction failed.')
    } finally {
      setRedacting(false)
    }
  }

  const overlayMarks = draftMark && draftMark.pageNumber === page
    ? [...currentMarks, draftMark]
    : currentMarks

  // Reload from the server when Comments is opened, including after a
  // permanent redaction or a return from another document.
  useEffect(() => {
    if (!commentPanelOpen || redactionMode) return
    let active = true
    setCommentsLoading(true)
    setCommentsError('')
    contentApi<DocumentComment[]>(`/api/v1/business/documents/${documentId}/comments`, persona)
      .then((value) => { if (active) setComments(value) })
      .catch((reasonValue: unknown) => {
        if (active) setCommentsError(reasonValue instanceof Error ? reasonValue.message : 'Could not load document comments.')
      })
      .finally(() => { if (active) setCommentsLoading(false) })
    return () => { active = false }
  }, [commentPanelOpen, documentId, persona, redactionMode])

  const sidePanelOpen = (detailsPanelOpen || tagPanelOpen || commentPanelOpen) && !redactionMode
  const displayName = metadata?.originalFilename ?? filename
  const activeDocumentId = selectedDocumentId ?? documentId
  const documentIndex = documents.findIndex((item) => item.documentId === activeDocumentId)
  const togglePanel = (panel: 'details' | 'comments' | 'tags') => {
    setDetailsPanelOpen((value) => panel === 'details' ? !value : false)
    setCommentPanelOpen((value) => panel === 'comments' ? !value : false)
    setTagPanelOpen((value) => panel === 'tags' ? !value : false)
  }
  const closePanels = () => { setDetailsPanelOpen(false); setTagPanelOpen(false); setCommentPanelOpen(false) }
  const enterRedactionMode = () => { setRedactionMode(true); setRedactionMessage(''); closePanels() }

  return (
    <div className={embedded ? 'viewer-embedded bwv' : 'viewer-backdrop bwv'} role={embedded ? undefined : 'dialog'} aria-modal={embedded ? undefined : true} aria-label={`Secure PDF viewer for ${filename}`}>
      <section className={`bwv-shell ${embedded ? 'embedded' : ''} ${redactionMode ? 'is-redacting' : ''}`}>
        {!embedded && (
          <header className="bwv-header">
            <div>
              <span className="bwv-header-kicker">Secure PDF Viewer</span>
              <h2>{displayName}</h2>
              <p>View permission is separate from download. Pages are decrypted and rendered only through authorized SDV requests.</p>
            </div>
            <button type="button" className="bwv-btn" onClick={onClose} aria-label="Close secure PDF viewer">Close</button>
          </header>
        )}

        <div className="bwv-toolbar">
          <div className="bwv-tb-doc">
            {embedded && <button type="button" className="bwv-btn bwv-back" onClick={onClose} aria-label="Back to results"><Icon name="chevronLeft" /><span className="bwv-back-label" aria-hidden="true">Results</span></button>}
            {embedded && documents.length > 1 ? (
              <label className="bwv-doc-picker">
                <Icon name="document" />
                <span className="sr-only">Document</span>
                <select value={activeDocumentId} onChange={(event) => onSelectDocument?.(event.target.value)} disabled={redactionMode}>
                  {documents.map((document) => <option key={document.documentId} value={document.documentId}>{document.originalFilename}</option>)}
                </select>
                {documentIndex >= 0 && <span className="bwv-doc-index" aria-hidden="true">{documentIndex + 1} of {documents.length}</span>}
              </label>
            ) : (
              <div className="bwv-doc-single" aria-label="Document"><Icon name="document" /><span>{displayName}</span></div>
            )}
          </div>

          <div className="bwv-tb-actions" role="group" aria-label="Document actions">
            {canDownload && <button type="button" className="bwv-btn bwv-btn-icon" title="Download" aria-label="Download" onClick={downloadCurrent} disabled={downloading || redactionMode}><Icon name="download" /></button>}
            {canPrint && <button type="button" className="bwv-btn bwv-btn-icon" title="Print" aria-label="Print" onClick={printCurrent} disabled={redactionMode}><Icon name="print" /></button>}
            {canRedact && <button type="button" className="bwv-btn bwv-redact" onClick={enterRedactionMode} disabled={redactionMode}><Icon name="redaction" />Redact</button>}
            <span className="bwv-sep" aria-hidden="true" />
            <div className="bwv-segment" role="group" aria-label="Document information panels">
              <button type="button" className={`bwv-btn ${detailsPanelOpen ? 'active' : ''}`} aria-pressed={detailsPanelOpen} onClick={() => togglePanel('details')} disabled={redactionMode}>Details</button>
              <button type="button" className={`bwv-btn ${commentPanelOpen ? 'active' : ''}`} aria-pressed={commentPanelOpen} onClick={() => togglePanel('comments')} disabled={redactionMode}>Comments{comments.length ? <span className="bwv-count">{comments.length}</span> : null}</button>
              {canTag && <button type="button" className={`bwv-btn ${tagPanelOpen ? 'active' : ''}`} aria-pressed={tagPanelOpen} onClick={() => togglePanel('tags')} disabled={redactionMode}>Tags{tags.length ? <span className="bwv-count">{tags.length}</span> : null}</button>}
            </div>
          </div>

          <span className="bwv-tb-break" aria-hidden="true" />

          <div className="bwv-tb-nav">
            <form className="bwv-segment bwv-pager" onSubmit={jumpToPage}>
              <button type="button" className="bwv-btn bwv-btn-icon" aria-label="Previous page" disabled={page <= 1} onClick={() => goToPage(page - 1)}><Icon name="chevronLeft" /></button>
              <input inputMode="numeric" value={jump} onChange={(event) => setJump(event.target.value)} aria-label="Page number" />
              <span>of {pageCount || (metadataLoading ? '…' : '—')}</span>
              <button type="button" className="bwv-btn bwv-btn-icon" aria-label="Next page" disabled={!metadata || page >= pageCount} onClick={() => goToPage(page + 1)}><Icon name="chevronRight" /></button>
            </form>

            <div className="bwv-segment bwv-zoom" role="group" aria-label="Zoom controls">
              <button type="button" className="bwv-btn bwv-btn-icon" aria-label="Zoom out" disabled={displayScale <= 0.5 || redactionMode} onClick={() => stepZoom(-0.25)}><span aria-hidden="true">−</span></button>
              <strong aria-live="polite">{zoomPercent}%</strong>
              <button type="button" className="bwv-btn bwv-btn-icon" aria-label="Zoom in" disabled={displayScale >= 2.5 || redactionMode} onClick={() => stepZoom(0.25)}><span aria-hidden="true">+</span></button>
              <button type="button" className={`bwv-btn ${fitToPage ? 'active' : ''}`} aria-label="Fit document to viewer width" title="Fit to width" aria-pressed={fitToPage} disabled={redactionMode} onClick={() => setFitToPage(true)}>Fit</button>
            </div>
          </div>

          <form className="bwv-find" onSubmit={search}>
            <label>
              <span className="sr-only">Search document</span>
              <Icon name="search" />
              <input value={query} maxLength={100} onChange={(event) => setQuery(event.target.value)} placeholder="Search in document" disabled={redactionMode} />
            </label>
            <button type="submit" className="bwv-btn" disabled={redactionMode || searching || !query.trim()}>{searching ? 'Searching…' : 'Search'}</button>
          </form>
        </div>

        {matches.length > 0 && !redactionMode && (
          <div className="bwv-strip" aria-live="polite">
            <strong>{totalMatches} match{totalMatches === 1 ? '' : 'es'}</strong>
            {matches.map((match) => (
              <button key={match.pageNumber} type="button" className={match.pageNumber === page ? 'active' : ''} onClick={() => goToPage(match.pageNumber)}>
                Page {match.pageNumber} · {match.matchCount}
              </button>
            ))}
          </div>
        )}
        {query.trim() && !searching && totalMatches === 0 && matches.length === 0 && metadata && !redactionMode && (
          <div className="bwv-strip">No text match found. Image-only/scanned PDFs are not OCRed by SDV.</div>
        )}
        {redactionMessage && <div className="bwv-notice is-success" role="status">{redactionMessage}</div>}
        {error && <div className="bwv-notice is-error" role="alert">{error}</div>}

        <div className={`bwv-body ${sidePanelOpen || redactionMode ? 'side-open' : ''}`}>
          <div className="bwv-stage-col">
            <div ref={canvasRef} className={`bwv-canvas ${redactionMode ? 'redaction-active' : ''} ${fitToPage ? 'fit-page' : ''}`} aria-busy={loadingPage}>
              {loadingPage && <div className="bwv-loading">Rendering page securely…</div>}
              {!imageUrl && !loadingPage && metadataLoading && <div className="bwv-loading">Preparing secure view…</div>}
              {imageUrl && (
                <div
                  className="viewer-page-stage"
                  onPointerDown={startRedaction}
                  onPointerMove={moveRedaction}
                  onPointerUp={finishRedaction}
                  onPointerCancel={() => { setDraftMark(null); draftAnchor.current = null }}
                >
                  <img ref={imageRef} src={imageUrl} alt={`Page ${page} of ${pageCount}`} draggable={false} onLoad={measurePage} style={pageSize ? { width: `${Math.round(pageSize.width * displayScale)}px`, height: 'auto' } : undefined} />
                  {overlayMarks.map((mark) => (
                    <div
                      key={mark.id}
                      className="redaction-overlay"
                      style={{
                        left: `${mark.x * 100}%`,
                        top: `${mark.y * 100}%`,
                        width: `${mark.width * 100}%`,
                        height: `${mark.height * 100}%`,
                      }}
                    />
                  ))}
                </div>
              )}
            </div>
            <footer className="bwv-seal" aria-label="Secure document context">
              <span className="bwv-seal-mark"><Icon name="eye" />Secure view</span>
              <span className="bwv-seal-file">{displayName}</span>
              <span>Page {page} of {pageCount || (metadataLoading ? '…' : '—')}</span>
              {formatSealDate(currentDocument?.ingestedAt) && <span>Ingested {formatSealDate(currentDocument?.ingestedAt)}</span>}
              {formatSealDate(currentDocument?.availableAt) && <span>Available {formatSealDate(currentDocument?.availableAt)}</span>}
              {formatSealDate(currentDocument?.expiresAt) && <span>Retained until {formatSealDate(currentDocument?.expiresAt)}</span>}
              {!embedded && <span>No plaintext PDF is persisted to normal server files or browser cache by this viewer workflow.</span>}
            </footer>
          </div>

          {redactionMode && (
            <aside className="bwv-side bwv-redaction" aria-label="Permanent corrective redaction">
              <form onSubmit={submitRedaction}>
                <div className="bwv-side-head">
                  <strong><Icon name="redaction" />Permanent corrective redaction</strong>
                </div>
                <div className="bwv-side-body">
                  <p>Drag rectangles over every region that must be removed. You can navigate between pages and add multiple marks. The corrected PDF is rebuilt from sanitized page images so underlying PDF objects are not retained.</p>
                  <div className="bwv-marks">
                    <strong className="bw-tabular">{redactionMarks.length} region{redactionMarks.length === 1 ? '' : 's'} selected</strong>
                    {redactionMarks.length > 0 && <div className="bwv-chips">{redactionMarks.map((mark, index) => (
                      <button key={mark.id} type="button" className="bwv-chip" onClick={() => removeMark(mark.id)} aria-label={`Remove page ${mark.pageNumber} region ${index + 1}`}>
                        Page {mark.pageNumber} · Region {index + 1} <span aria-hidden="true">×</span>
                      </button>
                    ))}</div>}
                  </div>
                  <label className="bw-field">
                    <span className="bw-field-label"><span>Mandatory reason</span></span>
                    <textarea
                      value={reason}
                      maxLength={1000}
                      onChange={(event) => setReason(event.target.value)}
                      placeholder="Why is this permanent correction required?"
                    />
                  </label>
                  <label className="bwv-confirm">
                    <input
                      type="checkbox"
                      checked={confirmed}
                      onChange={(event) => setConfirmed(event.target.checked)}
                    />
                    <span>I understand this permanently sanitizes the current PDF and the prior pre-correction version will not be retained.</span>
                  </label>
                </div>
                <div className="bwv-side-foot">
                  <button type="button" className="bw-btn bw-btn-secondary" onClick={cancelRedaction} disabled={redacting}>Cancel</button>
                  <button
                    type="submit"
                    className="bw-btn bw-btn-danger"
                    disabled={redacting || redactionMarks.length === 0 || reason.trim().length < 3 || !confirmed}
                  >
                    {redacting ? 'Permanently redacting…' : 'Confirm permanent redaction'}
                  </button>
                </div>
              </form>
            </aside>
          )}

          {sidePanelOpen && (
            <aside className="bwv-side" aria-label="Document information">
              <div className="bwv-side-head">
                <strong>{detailsPanelOpen ? 'Document details' : tagPanelOpen ? 'Document tags' : `Comments (${comments.length})`}</strong>
                <button type="button" className="bw-icon-btn" aria-label="Close document information" onClick={closePanels}><span aria-hidden="true">×</span></button>
              </div>

              {detailsPanelOpen && (
                <dl className="bwv-side-body bwv-details">
                  <div><dt>File</dt><dd>{displayName}</dd></div>
                  <div><dt>Pages</dt><dd className="bw-tabular">{pageCount || '—'}</dd></div>
                  <div><dt>Current page</dt><dd className="bw-tabular">{page}</dd></div>
                  <div><dt>Viewing</dt><dd>Secure page rendering</dd></div>
                </dl>
              )}

              {tagPanelOpen && canTag && (
                <form className="bwv-side-body" onSubmit={addTag}>
                  <p>Tags are stored with this document and recorded in Activity.</p>
                  {tags.length > 0 ? <ul className="bwv-tags">{tags.map((tag) => <li key={tag.tagId}>{tag.tag}</li>)}</ul> : <p className="bwv-empty">No tags yet.</p>}
                  <div className="bwv-entry">
                    <input maxLength={80} value={tagInput} onChange={(event) => setTagInput(event.target.value)} placeholder="Enter tag" aria-label="New tag" />
                    <button type="submit" className="bw-btn bw-btn-primary" disabled={tagging || !tagInput.trim()}>{tagging ? 'Adding…' : 'Add tag'}</button>
                  </div>
                </form>
              )}

              {commentPanelOpen && (
                <form className="bwv-side-body" onSubmit={addComment}>
                  <p>Comments are attached to this document. Activity records when a comment is added.</p>
                  {canComment ? (
                    <div className="bwv-entry bwv-entry-stack">
                      <textarea maxLength={2000} value={commentInput} onChange={(event) => setCommentInput(event.target.value)} placeholder="Add a comment about this document" aria-label="New comment" />
                      <button type="submit" className="bw-btn bw-btn-primary" disabled={commenting || !commentInput.trim()}>{commenting ? 'Adding…' : 'Add comment'}</button>
                    </div>
                  ) : <p className="bwv-readonly">You can read comments but do not have permission to add them.</p>}
                  {commentsLoading && <p role="status">Loading comments…</p>}
                  {commentsError && <p role="alert">Comments could not be loaded: {commentsError}</p>}
                  {!commentsLoading && !commentsError && comments.length === 0 && <p className="bwv-empty">No comments have been added to this document.</p>}
                  {comments.length > 0 && <ol className="bwv-comments">{visibleComments.map((comment) => <li key={comment.commentId}>
                    <div className="bwv-comment-meta"><strong className="bw-mono">{comment.createdBy}</strong><time dateTime={comment.createdAt}>{new Date(comment.createdAt).toLocaleString()}</time></div>
                    <p>{comment.comment}</p>
                  </li>)}</ol>}
                  {comments.length > 3 && <button type="button" className="bw-btn bw-btn-quiet" onClick={() => setShowAllComments((current) => !current)}>{showAllComments ? 'Show latest 3' : `View all ${comments.length} comments`}</button>}
                </form>
              )}
            </aside>
          )}
        </div>
      </section>
    </div>
  )
}
