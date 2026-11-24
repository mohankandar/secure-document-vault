import { useEffect, useState } from 'react'
import { Lock } from 'lucide-react'
import { Dialog, fmtDate } from './ui'
import { openSealedOriginal, sealedOriginalPage, sealedOriginals, type SealedOriginal } from './liveApi'
import { failureText } from './CardError'

/**
 * Task 0066: the sealed original of a document that was redacted while on legal hold. Restricted Stewards only (the
 * server checks), and only to read: there is no print or download here.
 */
export function SealedOriginalBar({ documentId, revision }: { documentId: string; revision: number }) {
  const [list, setList] = useState<SealedOriginal[]>([])
  const [open, setOpen] = useState<SealedOriginal | null>(null)
  useEffect(() => {
    let active = true
    setList([]); setOpen(null)
    sealedOriginals(documentId).then((l) => { if (active) setList(l) }).catch(() => { /* none to show */ })
    return () => { active = false }
  }, [documentId, revision])
  if (!list.length) return null
  return <>
    <div className="vw-bar" role="status" style={{ minHeight: 48 }}>
      <Lock size={18} aria-hidden style={{ marginLeft: 8, flex: 'none' }} />
      <span className="grow" style={{ fontSize: 13 }}><strong>Redacted on legal hold.</strong> The original is kept sealed until the hold is released. Only Restricted Stewards can read it.</span>
      {list.map((s) => <button key={s.sealedId} type="button" className="v-btn sm" onClick={() => setOpen(s)}>Open sealed original{list.length > 1 ? ` (${fmtDate(s.sealedAt)})` : ''}</button>)}
    </div>
    {open && <SealedOriginalDialog documentId={documentId} sealed={open} onClose={() => setOpen(null)} />}
  </>
}

function SealedOriginalDialog({ documentId, sealed, onClose }: { documentId: string; sealed: SealedOriginal; onClose: () => void }) {
  const [pages, setPages] = useState(0)
  const [page, setPage] = useState(1)
  const [viewingId, setViewingId] = useState('')
  const [url, setUrl] = useState('')
  const [failure, setFailure] = useState('')
  useEffect(() => {
    let active = true
    openSealedOriginal(documentId, sealed.sealedId).then((m) => { if (active) { setPages(m.pageCount); setViewingId(m.viewingId ?? '') } })
      .catch((e) => { if (active) setFailure(failureText('Sealed original did not open', e)) })
    return () => { active = false }
  }, [documentId, sealed.sealedId])
  useEffect(() => {
    if (!viewingId) return
    let active = true, made = ''
    setUrl('')
    sealedOriginalPage(documentId, sealed.sealedId, page, viewingId).then((b) => { if (active) { made = URL.createObjectURL(b); setUrl(made) } })
      .catch((e) => { if (active) setFailure(failureText('Sealed original: page did not load', e)) })
    return () => { active = false; if (made) URL.revokeObjectURL(made) }
  }, [documentId, sealed.sealedId, page, viewingId])
  return <Dialog title="Sealed original" onClose={onClose} footer={<>
    <button type="button" className="v-btn" disabled={page <= 1} onClick={() => setPage(page - 1)}>Previous page</button>
    <span className="faint">{pages ? `Page ${page} of ${pages}` : ''}</span>
    <button type="button" className="v-btn" disabled={page >= pages} onClick={() => setPage(page + 1)}>Next page</button>
    <button type="button" className="v-btn primary" onClick={onClose}>Close</button>
  </>}>
    <p className="muted">Sealed {fmtDate(sealed.sealedAt)}. Reading it is recorded with your name. It can't be printed or downloaded.</p>
    {failure ? <div className="v-callout" role="alert">{failure}</div>
      : url ? <img src={url} alt={`Sealed original page ${page}`} style={{ width: '100%', height: 'auto', display: 'block' }} />
        : <div className="v-callout" role="status">Opening the sealed original…</div>}
  </Dialog>
}
