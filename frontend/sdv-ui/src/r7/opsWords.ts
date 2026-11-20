// Task 0012 (change 9): the words for Platform operations Overview, Storage and Health, in one file.
// Services, automatic cleanup and storage say what they do, what stops working, and what to check.
import type { LiveStorageVolume, OpsDetail, OpsRetention, OpSummary } from './liveApi'
import { onPurposeWord, storageRoles } from './Storage'

export type ServiceKey = 'application' | 'database' | 'keyService'
type ServiceWords = { name: string; does: string; down: string; notConfigured?: string }

/** One entry per service: its name, what it does, and what a reader needs when it is not working. */
export const SERVICE_WORDS: Record<ServiceKey, ServiceWords> = {
  application: {
    name: 'Vero',
    does: 'The application itself',
    down: 'Vero isn’t answering. Nobody can search, open documents or change settings, and new deliveries are refused. Check that the Vero service is running and look at its log.',
  },
  database: {
    name: 'Database',
    does: 'Keeps records, settings and the audit',
    down: 'The database isn’t answering. Nobody can search or open records, new deliveries are refused, and nothing can be written to the audit. Check that PostgreSQL is running and that Vero can reach it.',
  },
  keyService: {
    name: 'Key service',
    does: 'Locks and unlocks stored documents (Vault)',
    down: 'Key service isn’t answering. Nobody can open documents and new deliveries wait. Check that Vault is running and unsealed.',
    notConfigured: 'No key service is set up, so documents are stored without Vault’s locks. This is expected only on a test computer.',
  },
}

export type ServiceState = 'working' | 'down' | 'notConfigured' | 'unknown'
export function serviceState(status: string | undefined): ServiceState {
  if (status === 'UP') return 'working'
  if (status === 'DOWN') return 'down'
  if (status === 'NOT_CONFIGURED') return 'notConfigured'
  return 'unknown'
}
export const SERVICE_STATE_WORDS: Record<ServiceState, string> = { working: 'Working', down: 'Not answering', notConfigured: 'Not set up', unknown: 'Unknown' }
/** The status in words, for one service. */
export function serviceSentence(key: ServiceKey, status: string | undefined): string | null {
  const s = serviceState(status), w = SERVICE_WORDS[key]
  if (s === 'down') return w.down
  if (s === 'notConfigured') return w.notConfigured ?? `${w.name} isn’t set up.`
  if (s === 'unknown') return `Vero couldn’t tell whether the ${w.name.toLowerCase()} is working. Check now again; if it stays like this, look at Vero’s log.`
  return null
}
export const SERVICE_KEYS: ServiceKey[] = ['application', 'database', 'keyService']
/** Services that are not working (a key service that isn't set up counts only when it isn't expected). */
export const servicesDown = (s: OpSummary) => SERVICE_KEYS.filter((k) => ['down', 'unknown'].includes(serviceState(s[k]?.status)))

// ---- Times, without ISO stamps ----

const time = (d: Date) => new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit' }).format(d)
const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString()
/** "Today 09:12", "Yesterday 11:48", "16 Nov 08:02". */
export function fmtWhen(iso: string | null | undefined, now = new Date()): string {
  if (!iso) return '—'
  const d = new Date(iso)
  const yesterday = new Date(now); yesterday.setDate(now.getDate() - 1)
  if (sameDay(d, now)) return `Today ${time(d)}`
  if (sameDay(d, yesterday)) return `Yesterday ${time(d)}`
  return `${new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' }).format(d)} ${time(d)}`
}
/** "today at 07:00", "yesterday at 07:00", "on 16 Nov at 07:00" (inside a sentence). */
export function atWhen(iso: string, now = new Date()): string {
  const w = fmtWhen(iso, now)
  if (w.startsWith('Today ')) return `today at ${w.slice(6)}`
  if (w.startsWith('Yesterday ')) return `yesterday at ${w.slice(10)}`
  const [day, month, t] = w.split(' ')
  return `on ${day} ${month} at ${t}`
}
/** A time still to come, or null when there is none or it can't be read (nothing is guessed). */
function upcoming(iso: string | null | undefined, now: Date): { d: Date; day: string } | null {
  if (!iso) return null
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return null
  const tomorrow = new Date(now); tomorrow.setDate(now.getDate() + 1)
  const day = sameDay(d, now) ? 'today' : sameDay(d, tomorrow) ? 'tomorrow' : new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' }).format(d)
  return { d, day }
}
/** "today at 07:00", "tomorrow at 07:00", "on 21 Nov at 07:00" (inside a sentence); null when unknown. */
export function atWhenNext(iso: string | null | undefined, now = new Date()): string | null {
  const u = upcoming(iso, now)
  if (!u) return null
  return `${u.day === 'today' || u.day === 'tomorrow' ? u.day : `on ${u.day}`} at ${time(u.d)}`
}
/** "today 07:00", "tomorrow 07:00", "21 Nov 07:00" (a short line); null when unknown. */
export function whenNext(iso: string | null | undefined, now = new Date()): string | null {
  const u = upcoming(iso, now)
  return u ? `${u.day} ${time(u.d)}` : null
}
/** "just now", "2 minutes ago", "1 hour ago". */
export function ago(iso: string | null, now = Date.now()): string {
  if (!iso) return 'not yet'
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (s < 45) return 'just now'
  const m = Math.round(s / 60)
  if (m < 60) return `${m} minute${m === 1 ? '' : 's'} ago`
  const h = Math.round(m / 60)
  if (h < 24) return `${h} hour${h === 1 ? '' : 's'} ago`
  return fmtWhen(iso)
}

// ---- Automatic cleanup (the retention sweep) ----

const docs = (n: number) => `${n.toLocaleString()} document${n === 1 ? '' : 's'}`
export type Cleanup = { tone: 'ok' | 'bad' | 'neutral'; sentence: string; whatToDo?: string }
/**
 * The last run in a sentence, then the next one ("Next run: tomorrow at 07:00."). The server keeps only the last
 * run since it started. It works the next run out from the scheduler's own setting and sends none when it can't
 * (task 0023); then nothing is said about it.
 */
export function cleanupWords(r: OpsRetention | null, now = new Date()): Cleanup {
  if (!r) return { tone: 'neutral', sentence: 'Vero couldn’t say how automatic cleanup is doing.' }
  const c = lastRunWords(r)
  const next = atWhenNext(r.nextRunAt, now)
  return next ? { ...c, sentence: `${c.sentence} Next run: ${next}.` } : c
}
function lastRunWords(r: OpsRetention): Cleanup {
  if (r.outcome === 'NEVER_RUN' || !r.completedAt) return { tone: 'neutral', sentence: 'Automatic cleanup hasn’t run since Vero last started. It starts by itself shortly after start-up.' }
  const when = atWhen(r.completedAt)
  if (r.outcome === 'FAILED') return {
    tone: 'bad',
    sentence: `The last cleanup run failed ${when}. Documents past their keep date are not being deleted until a run succeeds.`,
    whatToDo: 'Vero tries again by itself. If it keeps failing, check that the database is answering and look for RETENTION_SWEEP_FAILED in Vero’s log.',
  }
  const deleted = r.deleted === 0 ? 'found nothing that had passed its keep date' : `deleted ${docs(r.deleted)} that had passed their keep date`
  if (r.failed > 0) return {
    tone: 'bad',
    sentence: `Last ran ${when} and ${deleted}, but ${docs(r.failed)} couldn’t be deleted.`,
    whatToDo: 'They show under Problems as “still being deleted”. Vero tries them again on the next run.',
  }
  return { tone: 'ok', sentence: `Last ran ${when} and ${deleted}.` }
}

// ---- Storage, read-only for Platform operations ----

/** The operations summary's volume in the Tenant IT shape, so both screens name roles the same way. */
export function asLiveVolume(v: OpsDetail['storageVolumes'][number]): LiveStorageVolume {
  return {
    volume: { volumeId: v.volumeId, displayName: v.displayName, rootPath: v.rootPath ?? '', state: v.state, priority: v.priority ?? 0, warningPercent: v.warningPercent ?? 0, stopWritePercent: v.stopWritePercent ?? 0 },
    capacity: { totalBytes: v.totalBytes ?? null, usableBytes: v.usableBytes ?? null, usedPercent: v.usedPercent, writable: v.writable },
    observationError: v.observationError, warningThresholdReached: v.warningThresholdReached, stopWriteThresholdReached: v.stopWriteThresholdReached,
  }
}

/** A volume Tenant IT set offline, read only or to be emptied is on purpose: never counted as needing attention. */
export const setOnPurpose = (state: string) => state === 'OFFLINE' || state === 'READ_ONLY' || state === 'DRAINING'
/** Only unexpected states count: a volume in use or standby that is nearly full, full, or can't be reached. */
export const volumeNeedsAttention = (v: LiveStorageVolume) => v.volume.state === 'ACTIVE'
  && (!!v.observationError || v.stopWriteThresholdReached || v.warningThresholdReached || !v.capacity?.writable)

export function storageRoleWord(items: LiveStorageVolume[], v: LiveStorageVolume): string {
  const { inUse, ready } = storageRoles(items)
  const onPurpose = onPurposeWord(v.volume.state)
  if (onPurpose) return onPurpose
  if (v === inUse) return 'In use'
  if (ready.includes(v)) return 'Standby'
  return 'Not usable right now'
}

export function canStoreWords(v: LiveStorageVolume): { text: string; tone: 'ok' | 'warn' | 'bad' | 'neutral' } {
  if (v.volume.state === 'OFFLINE') return { text: 'No: offline on purpose', tone: 'neutral' }
  if (v.volume.state === 'READ_ONLY') return { text: 'No: read only on purpose', tone: 'neutral' }
  if (v.volume.state === 'DRAINING') return { text: 'No: being emptied on purpose', tone: 'neutral' }
  if (v.observationError) return { text: 'No: can’t reach the folder', tone: 'bad' }
  if (v.stopWriteThresholdReached) return { text: 'No: full', tone: 'bad' }
  if (!v.capacity?.writable) return { text: 'No: can’t write to the folder', tone: 'bad' }
  if (v.warningThresholdReached) return { text: 'Yes, but nearly full', tone: 'warn' }
  return { text: 'Yes', tone: 'ok' }
}

/** "What to do", one sentence per volume that needs attention; an empty list means nothing to do. */
export function storageWhatToDo(items: LiveStorageVolume[]): string[] {
  const { next } = storageRoles(items)
  return items.filter(volumeNeedsAttention).map((v) => {
    const name = v.volume.displayName, then = next(v)?.volume.displayName, stop = v.volume.stopWritePercent
    if (v.observationError) return `Vero can’t reach the folder of ${name}. Ask the customer’s Tenant IT to check the folder and its disk.${then ? ` Meanwhile Vero stores new documents on ${then}.` : ' Until then Vero can’t store new documents.'}`
    if (v.stopWriteThresholdReached) return `${name} is full. Ask the customer’s Tenant IT to add space or add a volume.${then ? ` Vero now stores new documents on ${then}.` : ' Until then Vero can’t store new documents.'}`
    if (!v.capacity?.writable) return `Vero can’t write to the folder of ${name}. Ask the customer’s Tenant IT to check its permissions.${then ? ` Meanwhile Vero stores new documents on ${then}.` : ''}`
    return `Ask the customer’s Tenant IT to add space to ${name} or add a volume.${then ? ` At ${stop}% Vero switches new documents to ${then} by itself.` : ` At ${stop}% Vero stops storing new documents.`}`
  })
}

/** "storage primary-a is 82% full" and the like, for the Overview's opening sentence. */
export function storageAttentionPhrase(v: LiveStorageVolume): string {
  const name = v.volume.displayName
  if (v.observationError) return `Vero can’t reach storage ${name}`
  if (v.stopWriteThresholdReached) return `storage ${name} is full`
  if (!v.capacity?.writable) return `Vero can’t write to storage ${name}`
  return `storage ${name} is ${v.capacity?.usedPercent ?? '?'}% full`
}

// ---- Problems, for the Overview ----

export function problemKinds(s: OpSummary): string {
  const parts: string[] = []
  const add = (n: number | null, words: string) => { if (n) parts.push(`${n.toLocaleString()} ${words}`) }
  add(s.processingFailureCount, 'could not be stored')
  add(s.stuckDocumentCount, 'stuck')
  add(s.attentionSubmissionCount, 'missing documents')
  return parts.join(' · ')
}
export const problemTotal = (s: OpSummary) => {
  const counts = [s.processingFailureCount, s.stuckDocumentCount, s.attentionSubmissionCount]
  return counts.every((x) => typeof x === 'number') ? counts.reduce<number>((a, x) => a + (x ?? 0), 0) : null
}

/** "A, B and C" */
export const joinAnd = (xs: string[]) => (xs.length <= 1 ? xs.join('') : `${xs.slice(0, -1).join(', ')} and ${xs[xs.length - 1]}`)
const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)

/** The Overview's opening sentence: how many things need attention, and what they are. */
export function overviewSentence(s: OpSummary, volumes: LiveStorageVolume[], cleanup: OpsRetention | null): { attention: number; text: string } {
  const things: string[] = []
  const total = problemTotal(s)
  if (total == null) things.push('some problems couldn’t be counted')
  else if (total > 0) things.push(`${total.toLocaleString()} document${total === 1 ? '' : 's'} did not arrive properly`)
  for (const v of volumes.filter(volumeNeedsAttention)) things.push(storageAttentionPhrase(v))
  for (const k of servicesDown(s)) things.push(serviceState(s[k]?.status) === 'down' ? `the ${k === 'application' ? 'application' : SERVICE_WORDS[k].name.toLowerCase()} isn’t answering` : `Vero couldn’t check the ${SERVICE_WORDS[k].name.toLowerCase()}`)
  if (cleanup?.outcome === 'FAILED' || (cleanup?.failed ?? 0) > 0) things.push('the last automatic cleanup didn’t finish')
  if (things.length === 0) return { attention: 0, text: 'Everything is working.' }
  return { attention: things.length, text: `${things.length === 1 ? '1 thing needs' : `${things.length} things need`} attention. ${capital(joinAnd(things))}.` }
}
