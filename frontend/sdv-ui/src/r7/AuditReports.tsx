import { useEffect, useRef, useState } from 'react'
import { Check, Download, FileCheck2, ShieldCheck, X } from 'lucide-react'
import { useStore } from './store'
import { FailureNotice, exportFailureText, loadFailed, type Failure } from './CardError'
import { Callout, fmtDateTime } from './ui'
import { refusalText } from './Sensitivity'
import { auditReportFile, auditReports, generateAuditReport, verifyAuditReport, type AuditReport, type AuditReportVerification } from './liveApi'
import { filterWords, pastedFingerprint, periodProblem, periodWords, sha256Hex, shortFingerprint } from './auditReportWords'

/**
 * Task 0050: the sealed audit report, for a Business admin (Business administration) and IT (IT). There is no auditor
 * role: an admin generates the report and hands it over. Built like the Reports tab's exports and the Legal holds form.
 * - Generate: a period (whole UTC days) and the Audit list's filters. The server registers the report and audits it.
 * - The register: every report, who made it, when, the period, the filters, the number of events and the fingerprint
 *   (the SHA-256 of its CSV), with its CSV and PDF.
 * - Verify: a CSV file (its fingerprint is worked out in this browser; the file isn't sent) or a pasted fingerprint.
 * Errors in the catalog's words with their code (task 0044).
 */

const today = () => new Date().toISOString().slice(0, 10)
const daysAgo = (n: number) => new Date(Date.now() - n * 24 * 60 * 60 * 1000).toISOString().slice(0, 10)

export function AuditReports() {
  const { can, toast } = useStore()
  const [reports, setReports] = useState<AuditReport[] | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [first, setFirst] = useState(daysAgo(29))
  const [last, setLast] = useState(today())
  const [outcome, setOutcome] = useState('')
  const [recordNumber, setRecordNumber] = useState('')
  const [level, setLevel] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [made, setMade] = useState<AuditReport | null>(null)
  const [fileNote, setFileNote] = useState<{ ok: boolean; text: string } | null>(null)

  useEffect(() => {
    let active = true
    auditReports().then((r) => { if (active) { setReports(r); setError(null) } }).catch((e) => { if (active) setError(loadFailed('Audit reports did not load', e)) })
    return () => { active = false }
  }, [reload])

  if (!can('GENERATE_AUDIT_REPORT')) return <Callout tone="warn" title="Only a Business admin or IT generates audit reports" />

  const notYet = periodProblem(first, last)
  const generate = async () => {
    if (notYet || busy) return
    setBusy(true); setProblem(null); setMade(null); setFileNote(null)
    try {
      const report = await generateAuditReport({ from: first, to: last, outcome, recordNumber, sensitivityLevel: level })
      setMade(report)
      toast({ tone: 'ok', title: 'Audit report generated', detail: `${report.rowCount} event${report.rowCount === 1 ? '' : 's'}. It is in the list below; its generation is in the audit.` })
      setReload((x) => x + 1)
    } catch (e) { setProblem(refusalText(e)) }
    finally { setBusy(false) }
  }
  const save = async (report: AuditReport, format: 'csv' | 'pdf') => {
    setFileNote(null)
    try {
      const file = await auditReportFile(report.reportId, format)
      const url = URL.createObjectURL(file.blob)
      const link = document.createElement('a')
      link.href = url; link.download = file.filename ?? `vero-audit-report-${report.reportId.slice(0, 8)}.${format}`
      document.body.appendChild(link); link.click(); link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      setFileNote({ ok: true, text: `The ${format.toUpperCase()} is saved. Reference ${file.reference ?? report.reportId}. SHA-256 fingerprint of the CSV ${file.fingerprint ?? report.fingerprint}. The copy is recorded in the audit.` })
    } catch (e) {
      setFileNote({ ok: false, text: `The file didn’t download. ${exportFailureText('An audit report download failed', e)}` })
    }
  }

  return <>
    <section className="v-panel hold-form" aria-label="New audit report">
      <span className="v-sec">New audit report</span>
      <form className="hold-fields" onSubmit={(e) => { e.preventDefault(); void generate() }}>
        <label className="v-field" style={{ width: 170 }}><span>First day</span>
          <input className="v-input" type="date" value={first} max={last || undefined} onChange={(e) => setFirst(e.target.value)} /></label>
        <label className="v-field" style={{ width: 170 }}><span>Last day</span>
          <input className="v-input" type="date" value={last} min={first || undefined} onChange={(e) => setLast(e.target.value)} /></label>
        <label className="v-field" style={{ width: 150 }}><span>Result</span>
          <select className="v-select" value={outcome} onChange={(e) => setOutcome(e.target.value)}>
            <option value="">Any result</option><option value="SUCCESS">Done</option><option value="DENIED">Refused</option><option value="FAILED">Failed</option>
          </select></label>
        <label className="v-field" style={{ width: 200 }}><span>Record number</span>
          <input className="v-input mono" value={recordNumber} maxLength={300} onChange={(e) => setRecordNumber(e.target.value)} placeholder="Any record" /></label>
        <label className="v-field" style={{ width: 190 }}><span>Level</span>
          <select className="v-select" value={level} onChange={(e) => setLevel(e.target.value)}>
            <option value="">Any level</option><option value="STANDARD">Standard</option><option value="CONFIDENTIAL">Confidential</option><option value="HIGHLY_CONFIDENTIAL">Highly Confidential</option>
          </select></label>
        <button type="submit" className="v-btn primary" disabled={!!notYet || busy}>{busy ? 'Generating…' : 'Generate report'}</button>
      </form>
      <p className="faint" style={{ fontSize: 13 }}>Days are whole days in UTC, both included. At most 10,000 events per report. Document IDs and record numbers only: never content, file names, titles or reasons.</p>
      {notYet && <p className="faint" role="status">{notYet}</p>}
      {problem && <Callout tone="warn" title="Not generated">{problem}</Callout>}
      {made && <div className="hold-found" role="status"><ShieldCheck size={16} aria-hidden />
        <span className="q-note">Report <span className="mono">{made.reportId.slice(0, 8)}</span> · {periodWords(made)} · {made.rowCount} event{made.rowCount === 1 ? '' : 's'} · fingerprint <span className="mono" title={made.fingerprint}>{shortFingerprint(made.fingerprint)}</span></span>
        <span className="row" style={{ gap: 6, marginLeft: 'auto' }}>
          <button type="button" className="v-btn sm" onClick={() => void save(made, 'csv')}><Download size={15} aria-hidden />CSV</button>
          <button type="button" className="v-btn sm" onClick={() => void save(made, 'pdf')}><Download size={15} aria-hidden />PDF</button>
        </span></div>}
    </section>

    {fileNote && (fileNote.ok ? <div className="hold-found" role="status"><Check size={16} strokeWidth={2.2} aria-hidden /><span className="q-note">{fileNote.text}</span></div>
      : <Callout tone="warn" title="Not downloaded">{fileNote.text}</Callout>)}

    {error && !reports ? <FailureNotice failure={error} loadTitle="Audit reports didn't load" onRetry={() => setReload((x) => x + 1)} />
      : <section className="v-panel flush" aria-label="Audit reports">
        <header className="hold-head"><h2>Audit reports · {reports?.length ?? '…'}</h2><span className="faint" style={{ fontSize: 13 }}>Every report generated, newest first. Reports can&apos;t be changed or removed</span></header>
        {reports && reports.length === 0 ? <p className="muted" style={{ padding: 16 }}>No audit report has been generated yet.</p> : reports &&
          <table className="v-table" aria-label="Audit report register"><thead><tr><th>Generated</th><th>By</th><th>Period (UTC)</th><th>Filters</th><th>Events</th><th>Fingerprint (SHA-256)</th><th><span className="sr-only">Files</span></th></tr></thead>
            <tbody>{reports.map((r) => <tr key={r.reportId}>
              <td className="lead" data-label="Generated">{fmtDateTime(r.generatedAt)}</td>
              <td data-label="By">{r.generatedBy}</td>
              <td data-label="Period (UTC)">{periodWords(r)}</td>
              <td data-label="Filters" className="faint">{filterWords(r.filters)}</td>
              <td data-label="Events">{r.rowCount}</td>
              <td data-label="Fingerprint (SHA-256)" className="mono" title={r.fingerprint}>{shortFingerprint(r.fingerprint)}</td>
              <td className="act"><span className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
                <button type="button" className="v-btn sm" onClick={() => void save(r, 'csv')} aria-label={`Download the CSV of report ${r.reportId.slice(0, 8)}`}>CSV</button>
                <button type="button" className="v-btn sm" onClick={() => void save(r, 'pdf')} aria-label={`Download the PDF of report ${r.reportId.slice(0, 8)}`}>PDF</button>
              </span></td>
            </tr>)}</tbody></table>}
      </section>}

    <Verify />
  </>
}

/** Verify a copy: the CSV's fingerprint, worked out in this browser, or a pasted one, against the register. */
function Verify() {
  const [pasted, setPasted] = useState('')
  const [fileName, setFileName] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<AuditReportVerification | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const input = useRef<HTMLInputElement>(null)

  const check = async (fingerprint: string) => {
    setBusy(true); setProblem(null); setResult(null)
    try { setResult(await verifyAuditReport(fingerprint)) } catch (e) { setProblem(refusalText(e)) }
    finally { setBusy(false) }
  }
  const fromFile = async (file: File | undefined) => {
    if (!file) return
    setFileName(file.name); setPasted('')
    await check(await sha256Hex(await file.arrayBuffer()))
  }
  const typed = pastedFingerprint(pasted)

  return (
    <section className="v-panel hold-form" aria-label="Verify an audit report">
      <span className="v-sec">Verify an audit report</span>
      <p className="muted" style={{ fontSize: 14 }}>Check that a CSV is exactly as Vero generated it. Its fingerprint is worked out on this computer; the file isn&apos;t sent. Any change to the file makes it fail.</p>
      <div className="hold-fields">
        <label className="v-field" style={{ minWidth: 260 }}><span>The report&apos;s CSV file</span>
          <input ref={input} className="v-input" type="file" accept=".csv,text/csv" onChange={(e) => void fromFile(e.target.files?.[0])} /></label>
        <span className="faint" style={{ alignSelf: 'center', paddingTop: 22 }}>or</span>
        <label className="v-field" style={{ flex: 1, minWidth: 280 }}><span>Its fingerprint, from the PDF</span>
          <input className="v-input mono" value={pasted} maxLength={200} onChange={(e) => { setPasted(e.target.value); setFileName(null) }} placeholder="64 characters, 0 to 9 and a to f" /></label>
        <button type="button" className="v-btn" disabled={!typed || busy} onClick={() => { if (typed) void check(typed) }}>{busy ? 'Checking…' : 'Verify'}</button>
      </div>
      {problem && <Callout tone="warn" title="Not checked">{problem}</Callout>}
      {result && (result.matches && result.report
        ? <div className="hold-found" role="status"><FileCheck2 size={16} aria-hidden /><span className="q-note"><b>Matches.</b> {fileName ? `${fileName} is` : 'This fingerprint is'} the report generated by {result.report.generatedBy} on {fmtDateTime(result.report.generatedAt)}: {periodWords(result.report)}, {result.report.rowCount} event{result.report.rowCount === 1 ? '' : 's'}, filters {filterWords(result.report.filters).toLowerCase() === 'none' ? 'none' : filterWords(result.report.filters)}.</span></div>
        : <div className="hold-found warn" role="status"><X size={16} aria-hidden /><span className="q-note"><b>No match.</b> {fileName ? `${fileName} matches` : 'This fingerprint matches'} no audit report generated in Vero. The file may have been changed, or it isn&apos;t a Vero audit report.</span></div>)}
    </section>
  )
}
