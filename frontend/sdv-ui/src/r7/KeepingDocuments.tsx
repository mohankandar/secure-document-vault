import { useEffect, useState } from 'react'
import { Plus } from 'lucide-react'
import { useStore } from './store'
import { FailureNotice, actionFailed, loadFailed, type Failure } from './CardError'
import { Badge, Callout, Drawer, Panel, fmtDate } from './ui'
import { activateRetentionPolicy, getRetentionPolicy, type RetentionPolicyOverview } from './liveApi'

// Task 0009 Part A (finding 21): the keeping rule in words.
// The existing API keeps days. A year is 365 days and a month 30 days, both ways: "7 years" is 2,555 days, and
// 2,555 days reads back as "7 years". A number of days that is not whole years or months reads as days.

export type PeriodUnit = 'years' | 'months' | 'days' | 'forever'
const DAYS_PER: Record<Exclude<PeriodUnit, 'forever'>, number> = { years: 365, months: 30, days: 1 }
/** The API's upper limit for a rule, in days. */
const MAX_DAYS = 36500

export const toDays = (amount: number, unit: Exclude<PeriodUnit, 'forever'>) => amount * DAYS_PER[unit]
const plural = (n: number, word: string) => `${n.toLocaleString()} ${word}${n === 1 ? '' : 's'}`
/** Days as the largest whole unit: 2555 → { 7, years }. */
export function fromDays(days: number): { amount: number; unit: Exclude<PeriodUnit, 'forever'> } {
  if (days % 365 === 0) return { amount: days / 365, unit: 'years' }
  if (days % 30 === 0) return { amount: days / 30, unit: 'months' }
  return { amount: days, unit: 'days' }
}
export function ruleWords(days: number | null, indefinite: boolean): string {
  if (indefinite || days == null) return 'Keep forever'
  const { amount, unit } = fromDays(days)
  return `Keep for ${plural(amount, unit.slice(0, -1))}`
}
/** Rules written by the installation itself, not by a person. */
const byInstallation = (createdBy: string) => createdBy === 'SYSTEM_MIGRATION'
const setBy = (createdBy: string) => (byInstallation(createdBy) ? 'Set at installation' : createdBy)

export function KeepingDocuments() {
  const { can, toast } = useStore()
  const [policy, setPolicy] = useState<RetentionPolicyOverview | null>(null)
  const [error, setError] = useState<Failure | null>(null)
  const [reload, setReload] = useState(0)
  const [stale, setStale] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [open, setOpen] = useState(false)
  const [amount, setAmount] = useState('')
  const [unit, setUnit] = useState<PeriodUnit>('years')
  const [why, setWhy] = useState('')
  const [approved, setApproved] = useState(false)
  useEffect(() => {
    let active = true
    setLoading(true)
    getRetentionPolicy().then((p) => { if (active) { setPolicy(p); setError(null) } })
      .catch((e) => { if (active) setError(loadFailed('Keeping documents did not load', e)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [reload])

  const n = Number(amount)
  const days = unit === 'forever' ? null : Number.isInteger(n) && n >= 1 ? toDays(n, unit) : NaN
  const periodValid = unit === 'forever' || (amount.trim().length > 0 && days != null && Number.isFinite(days) && days >= 1 && days <= MAX_DAYS)
  const ready = periodValid && why.trim().length > 0 && approved && !saving

  const startNew = () => {
    if (!policy) return
    const current = policy.indefinite || policy.defaultRetentionDays == null ? null : fromDays(policy.defaultRetentionDays)
    setUnit(current ? current.unit : 'forever'); setAmount(current ? String(current.amount) : '')
    setWhy(''); setApproved(false); setStale(false); setOpen(true)
  }
  const activate = async () => {
    if (!policy || !ready || !can('CONFIGURE_RETENTION')) return
    setSaving(true); setError(null); setStale(false)
    try {
      const current = await getRetentionPolicy()
      if (current.policyVersionId !== policy.policyVersionId) { setStale(true); return }
      const name = ruleWords(days, unit === 'forever')
      const updated = await activateRetentionPolicy(name, why.trim(), unit === 'forever' ? null : days)
      setPolicy(updated); setOpen(false)
      toast({ title: 'New rule in use', detail: `${name}. Documents already stored keep the rule they arrived with.` })
    } catch (e) { setError(actionFailed('Starting a new keeping rule failed', e)) }
    finally { setSaving(false) }
  }

  if (loading && !policy && !error) return <Callout title="Loading">Reading how long Vero keeps documents…</Callout>
  return <>
    {error && <FailureNotice failure={error} loadTitle="Keeping documents didn't load" actionTitle="The new rule wasn't started" onRetry={() => setReload((x) => x + 1)} />}
    {stale && <Callout tone="warn" title="The rule changed while you were editing">Someone else started a new rule. Reload the page, then make your change again.</Callout>}
    {policy && <>
      <section className="v-panel" aria-label="Rule in use"><div className="v-panel-body keep-now">
        <div className="stack" style={{ gap: 6 }}>
          <span className="eyebrow">Rule in use since {fmtDate(policy.effectiveFrom)}</span>
          <span className="keep-rule">{ruleWords(policy.defaultRetentionDays, policy.indefinite)}</span>
          <span className="muted">
            Counted from the day each document arrives.{' '}
            {byInstallation(policy.createdBy) ? 'Set at installation.' : <>Set by {policy.createdBy}{policy.description ? <>: &ldquo;{policy.description}&rdquo;</> : null}.</>}
          </span>
        </div>
        <button type="button" className="v-btn primary" disabled={!can('CONFIGURE_RETENTION')} onClick={startNew}><Plus size={16} aria-hidden />New rule</button>
      </div></section>
      <div className="keep-facts">
        <Panel title="When time is up">
          {/* The last cleanup run is in Platform operations' retention status, which a Business admin cannot read. */}
          <p>Vero deletes the document for good and records it in the audit. A daily cleanup does this.</p>
        </Panel>
        <Panel title="Changing the rule">
          <p>A new rule applies to documents that arrive after it starts. Documents already stored keep the rule they arrived with.</p>
        </Panel>
      </div>
      <Panel flush title="Earlier rules" meta={policy.history.length}>
        <table className="v-table"><thead><tr><th>Rule</th><th>In use</th><th>Set by</th><th>Why</th></tr></thead>
          <tbody>{policy.history.map((h) => <tr key={h.policyVersionId}>
            <td className="lead" data-label="Rule"><span className="row" style={{ gap: 8 }}><strong>{ruleWords(h.defaultRetentionDays, h.indefinite)}</strong>{!h.effectiveUntil && <Badge tone="ok">In use</Badge>}</span></td>
            <td data-label="In use">{h.effectiveUntil ? `${fmtDate(h.effectiveFrom)} – ${fmtDate(h.effectiveUntil)}` : `Since ${fmtDate(h.effectiveFrom)}`}</td>
            <td data-label="Set by">{setBy(h.createdBy)}</td>
            <td data-label="Why">{byInstallation(h.createdBy) || !h.description ? <span className="faint">—</span> : h.description}</td>
          </tr>)}</tbody>
        </table>
      </Panel>
      {open && <Drawer eyebrow="Keeping documents" title="New rule for how long to keep" onClose={() => { if (!saving) setOpen(false) }} footer={<>
        <button type="button" className="v-btn" disabled={saving} onClick={() => setOpen(false)}>Cancel</button>
        <button type="button" className="v-btn primary" disabled={!ready} onClick={() => void activate()}>{saving ? 'Starting…' : 'Start using this rule'}</button>
      </>}>
        <fieldset className="v-field keep-period">
          <legend>Keep new documents for</legend>
          <div className="row" style={{ gap: 8 }}>
            {unit !== 'forever' && <input className={`v-input${amount === '' || periodValid ? '' : ' invalid'}`} inputMode="numeric" aria-label="How many" value={amount} style={{ width: 96 }}
              onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))} />}
            <select className="v-select" aria-label="Unit" value={unit} onChange={(e) => setUnit(e.target.value as PeriodUnit)}>
              <option value="years">years</option><option value="months">months</option><option value="days">days</option><option value="forever">Forever</option>
            </select>
          </div>
          <small className={periodValid || amount === '' ? 'faint' : 'bad'}>
            {unit === 'forever' ? 'Documents are never deleted.'
              : periodValid ? `${(days as number).toLocaleString()} days. Or choose “Forever”.`
              : `Enter a whole number, at most ${MAX_DAYS.toLocaleString()} days in all.`}
          </small>
        </fieldset>
        <label className="v-field"><span>Why this changed</span>
          <textarea className="v-textarea" value={why} maxLength={1000} required onChange={(e) => setWhy(e.target.value)} placeholder="For example: records schedule approved by Legal on 12 Nov" />
        </label>
        <Callout title="Applies only to documents received from now on">Documents already stored keep the rule they arrived with.</Callout>
        <label className="v-check"><input type="checkbox" checked={approved} onChange={(e) => setApproved(e.target.checked)} /><span>This change has been approved</span></label>
      </Drawer>}
    </>}
  </>
}
