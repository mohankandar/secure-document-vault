import { useRef, useState } from 'react'
import { FailureNotice, actionFailed, type Failure } from './CardError'
import { exportFieldList, importFieldList, previewFieldList, type FieldImportLine } from './liveApi'
import { Dialog } from './ui'
import { useStore } from './store'

// Task 0077: the field list as a file, for another product of the same customer, and back: first what it would do, then confirm.

const OUTCOME: Record<FieldImportLine['outcome'], string> = { CREATE: 'New', UNCHANGED: 'Already there', CONFLICT: 'Left as it is' }
const CONFLICT: Record<string, string> = { TYPE_DIFFERS: 'another type here', CURRENCY_DIFFERS: 'another currency here', KEY_CASE_DIFFERS: 'differs only in capitals' }
/** The report in one sentence. */
export function importWords(lines: FieldImportLine[]): string {
  const n = (o: FieldImportLine['outcome']) => lines.filter((l) => l.outcome === o).length
  return `${n('CREATE')} new, ${n('UNCHANGED')} already there, ${n('CONFLICT')} in conflict.`
}

export function FieldList({ allowed, onImported }: { allowed: boolean; onImported: () => void }) {
  const { toast } = useStore()
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [file, setFile] = useState('')
  const [lines, setLines] = useState<FieldImportLine[] | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const run = async (what: string, work: () => Promise<void>) => {
    setBusy(true); setError(null)
    try { await work() } catch (e) { setError(actionFailed(what, e)) } finally { setBusy(false) }
  }
  const save = () => run('The field list export failed', async () => {
    const url = URL.createObjectURL(await exportFieldList())
    const link = document.createElement('a')
    link.href = url; link.download = 'vero-field-list.json'
    document.body.appendChild(link); link.click(); link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    toast({ title: 'Field list saved', detail: 'The export is recorded in the audit.' })
  })
  const choose = (chosen?: File) => { if (chosen) void run('Reading the field list failed', async () => {
    const text = await chosen.text()
    setLines(await previewFieldList(text)); setFile(text)
  }) }
  const confirm = () => run('The field list import failed', async () => {
    const done = await importFieldList(file)
    setLines(null); toast({ title: 'Field list imported', detail: importWords(done) }); onImported()
  })
  return <section className="v-panel" aria-label="Field list file"><div className="v-panel-body">
    <div className="stack grow" style={{ gap: 3 }}>
      <h2 className="sf-title-h">Field list</h2>
      <span className="muted">The fields of this entity as a file, to use in another product, or to take over from one.</span>
    </div>
    {error && <FailureNotice failure={error} loadTitle="" onRetry={() => undefined} />}
    <div className="row wrap" style={{ gap: 8 }}>
      <button type="button" className="v-btn sm" disabled={!allowed || busy} onClick={() => void save()}>Export field list</button>
      <button type="button" className="v-btn sm" disabled={!allowed || busy} onClick={() => input.current?.click()}>Import field list</button>
      <input ref={input} type="file" accept="application/json,.json" hidden aria-label="Field list file to import" onChange={(e) => { choose(e.target.files?.[0]); e.target.value = '' }} />
    </div>
    {lines && <Dialog title="Import this field list?" onClose={() => { if (!busy) setLines(null) }} footer={<>
      <button type="button" className="v-btn" disabled={busy} onClick={() => setLines(null)}>Cancel</button>
      <button type="button" className="v-btn primary" disabled={busy || !lines.some((l) => l.outcome === 'CREATE')} onClick={() => void confirm()}>{busy ? 'Importing…' : 'Import new fields'}</button></>}>
      <p>{importWords(lines)} Fields that are here stay as they are.</p>
      <table className="v-table" aria-label="What the import does"><thead><tr><th>Field</th><th>Key</th><th>Result</th></tr></thead>
        <tbody>{lines.map((l) => <tr key={l.fieldKey}><td>{l.label}</td><td className="mono">{l.fieldKey}</td><td>{OUTCOME[l.outcome]}{l.conflict ? `: ${CONFLICT[l.conflict] ?? 'conflict'}` : ''}</td></tr>)}</tbody></table>
    </Dialog>}
  </div></section>
}
