import { useEffect, useState } from 'react'
import { ArrowLeft, ChevronLeft, ChevronRight, Minus, Plus, Search, X, PanelRight, Lock } from 'lucide-react'
import type { RecordItem } from './data'
import { viewerMetadata, viewerPage, viewerSearch, type ViewerMetadata, type ViewerSearch } from './liveApi'
import { Drawer, fmtDateTime } from './ui'

type PageState = { url: string; key: string }
export function LiveViewer({ record, docId, onSelectDoc, onClose }: {
  record: RecordItem; docId: string; onSelectDoc: (id:string)=>void; onClose:()=>void
}) {
  const doc = record.docs.find(d=>d.id===docId)
  const [metadata,setMetadata] = useState<ViewerMetadata|null>(null)
  const [page,setPage] = useState(1)
  const [scale,setScale] = useState(1)
  const [pageImage,setPageImage] = useState<PageState|null>(null)
  const [loading,setLoading] = useState(true)
  const [failure,setFailure] = useState('')
  const [infoOpen,setInfoOpen] = useState(false)
  const [docsOpen,setDocsOpen] = useState(false)
  const [findOpen,setFindOpen] = useState(false)
  const [query,setQuery] = useState('')
  const [matches,setMatches] = useState<ViewerSearch|null>(null)
  const [searchError,setSearchError] = useState('')
  useEffect(()=>{
    let active=true
    setMetadata(null);setPage(1);setPageImage(null);setFailure('');setQuery('');setMatches(null);setLoading(true)
    viewerMetadata(docId).then(m=>{if(active)setMetadata(m)}).catch(e=>{if(active)setFailure(e instanceof Error?e.message:'Document unavailable')})
      .finally(()=>{if(active)setLoading(false)})
    return ()=>{active=false}
  },[docId])
  useEffect(()=>{
    if(!metadata || page<1 || page>metadata.pageCount)return
    let active=true
    setPageImage(null);setFailure('');setLoading(true)
    viewerPage(docId,page,scale).then(blob=>{
      if(!active)return
      const url=URL.createObjectURL(blob)
      setPageImage({url,key:docId+':'+page+':'+scale})
    }).catch(e=>{if(active)setFailure(e instanceof Error?e.message:'Page unavailable')})
      .finally(()=>{if(active)setLoading(false)})
    return ()=>{active=false}
  },[docId,page,scale,metadata])
  useEffect(()=>()=>{if(pageImage)URL.revokeObjectURL(pageImage.url)},[pageImage])
  useEffect(()=>{
    if(!query.trim()){setMatches(null);setSearchError('');return}
    let active=true
    const trimmed=query.trim()
    if(trimmed.length>100){setSearchError('Search is limited to 100 characters.');return}
    viewerSearch(docId,trimmed).then(r=>{if(active){setMatches(r);setSearchError('')}})
      .catch(e=>{if(active){setMatches(null);setSearchError(e instanceof Error?e.message:'Search unavailable')}})
    return ()=>{active=false}
  },[docId,query])
  useEffect(()=>{
    const onKey=(e:KeyboardEvent)=>{if(e.key==='Escape' && !infoOpen && !docsOpen)onClose()}
    window.addEventListener('keydown',onKey)
    return ()=>window.removeEventListener('keydown',onKey)
  },[onClose,infoOpen,docsOpen])
  const maxPages=metadata?.pageCount ?? 0
  const clamp=(p:number)=>setPage(Math.min(maxPages,Math.max(1,p)))
  const navigation=<div className="stack" style={{gap:10}}>
    <span className="eyebrow">Record documents · {record.docs.length}</span>
    {record.docs.map(d=><button key={d.id} type="button" className="vw-thumb" aria-current={d.id===docId} onClick={()=>{onSelectDoc(d.id);setDocsOpen(false)}}>
      <i aria-hidden/><span className="grow truncate">{d.filename}</span>
    </button>)}
    {maxPages>1 && <><span className="eyebrow">Pages</span><div className="vw-thumbs">{Array.from({length:maxPages},(_,i)=>
      <button type="button" key={i} className="vw-thumb" aria-current={page===i+1} onClick={()=>clamp(i+1)}>Page {i+1}</button>
    )}</div></>}
  </div>
  return <div className="vw" role="dialog" aria-modal="true" aria-label={doc?.filename ?? 'Secure document viewer'}>
    <div className="vw-bar">
      <button type="button" className="v-btn quiet icon" onClick={onClose} aria-label="Back to results"><ArrowLeft size={20}/></button>
      <button type="button" className="vw-title grow" style={{border:0,background:'none',textAlign:'left'}} onClick={()=>setDocsOpen(true)}>
        <strong className="truncate">{metadata?.originalFilename ?? doc?.filename ?? 'Document'}</strong>
        <span>{record.identifiers[0]?.value} · {maxPages?maxPages+' pages':'Loading document'}</span>
      </button>
      <div className="vw-group">
        <button type="button" className="v-btn quiet icon" aria-label="Find in document" aria-pressed={findOpen} onClick={()=>setFindOpen(x=>!x)}><Search size={18}/></button>
        <button type="button" className="v-btn quiet icon" aria-label="Document information" onClick={()=>setInfoOpen(true)}><PanelRight size={18}/></button>
      </div>
    </div>
    {findOpen && <div className="vw-bar" style={{minHeight:52}}>
      <Search size={16} className="faint"/>
      <input className="v-input grow" value={query} maxLength={100} onChange={e=>setQuery(e.target.value)} aria-label="Find in document" placeholder="Find text in document"/>
      <span className="faint">{matches ? matches.totalMatches+' matches' : searchError}</span>
      <button type="button" className="v-btn quiet icon" onClick={()=>{setFindOpen(false);setQuery('')}} aria-label="Close find"><X size={16}/></button>
    </div>}
    <div className="vw-body">
      <nav className="vw-docs" aria-label="Record documents">{navigation}</nav>
      <div className="vw-canvas" style={{position:'relative',display:'flex',flexDirection:'column',alignItems:'center',gap:12}}>
        {failure && <div className="v-callout" role="alert">{failure}</div>}
        {loading && <div className="v-callout" role="status">Loading secure document page…</div>}
        {pageImage && !loading && <img className="vw-sheet" src={pageImage.url} alt={`Page ${page} of ${maxPages}`} style={{width:`${Math.round(816*scale)}px`,maxWidth:'calc(100% - 24px)',height:'auto',objectFit:'contain'}}/>}
        <div className="vw-redact-bar" style={{position:'sticky',bottom:12,transform:'none',left:'auto',right:'auto',background:'var(--surface)',border:'1px solid var(--line)',padding:6,gap:6}} role="toolbar" aria-label="Page and zoom">
          <button type="button" className="v-btn quiet icon sm" disabled={!metadata||page<=1} onClick={()=>clamp(page-1)} aria-label="Previous page"><ChevronLeft size={16}/></button>
          <span className="vw-page">{page} / {maxPages || '–'}</span>
          <button type="button" className="v-btn quiet icon sm" disabled={!metadata||page>=maxPages} onClick={()=>clamp(page+1)} aria-label="Next page"><ChevronRight size={16}/></button>
          <span className="vw-sep" aria-hidden/>
          <button type="button" className="v-btn quiet icon sm" disabled={scale<=0.5} onClick={()=>setScale(s=>Math.max(.5,Math.round((s-.25)*100)/100))} aria-label="Zoom out"><Minus size={16}/></button>
          <span style={{minWidth:42,textAlign:'center'}}>{Math.round(scale*100)}%</span>
          <button type="button" className="v-btn quiet icon sm" disabled={scale>=2.5} onClick={()=>setScale(s=>Math.min(2.5,Math.round((s+.25)*100)/100))} aria-label="Zoom in"><Plus size={16}/></button>
        </div>
      </div>
      <aside className="vw-panel" aria-label="Document information">
        <div className="stack" style={{padding:20,gap:14}}><h3>Document details</h3>
          <dl className="v-kv"><div><dt>File name</dt><dd>{doc?.filename}</dd></div><div><dt>Document ID</dt><dd className="mono">{docId}</dd></div>
          <div><dt>Pages</dt><dd>{maxPages||'–'}</dd></div><div><dt>Ingested</dt><dd>{doc?.ingestedAt?fmtDateTime(doc.ingestedAt):'–'}</dd></div></dl>
          <div className="v-callout"><Lock size={14}/> Viewing is connected to the secure SDV backend. Download, printing, tags, comments and redaction are not integrated yet.</div>
        </div>
      </aside>
    </div>
    {infoOpen && <Drawer eyebrow="Document" title="Document information" onClose={()=>setInfoOpen(false)}><p>{doc?.filename}</p><p>{maxPages} pages</p><p>Additional actions will be integrated in later checkpoints.</p></Drawer>}
    {docsOpen && <Drawer eyebrow="Record documents" title={record.identifiers[0]?.value ?? 'Record'} onClose={()=>setDocsOpen(false)}>{navigation}</Drawer>}
  </div>
}
