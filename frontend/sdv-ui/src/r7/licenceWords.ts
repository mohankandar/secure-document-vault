// Task 0075: the licence in words. Never a price.
export type LicenceUse = { type: string; unit: string; used: number; limit: number; level: 'WITHIN' | 'NEAR_LIMIT' | 'AT_LIMIT' | 'OVER_LIMIT' }
export type LicenceView = {
  state: 'NOT_STARTED' | 'VALID' | 'EXPIRING' | 'EXPIRED_IN_GRACE' | 'EXPIRED_GRACE_OVER'; message: string; intakeAllowed: boolean; productLicensed: boolean; testEnvironment: boolean
  licenceId: string; version: number; customer: string; kind: string; startsOn: string; expiresOn: string | null; updatesUntil: string | null; graceEndsOn: string | null; renewalStartsOn: string | null
  clockBehind: boolean; clockMessage: string | null; entities: number; entitySlot: string | null; month: string; usage: LicenceUse[]
}
const day = (d: string) => new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(d + 'T00:00:00Z'))
export const licenceDay = (d: string | null) => (d ? day(d) : '—')

export function licenceSentence(l: LicenceView): { tone: 'ok' | 'warn' | 'bad'; title: string; text: string } {
  if (!l.productLicensed) return { tone: 'bad', title: 'The licence doesn’t name Vero', text: 'Vero takes no new documents. Viewing goes on. Ask your account manager.' }
  switch (l.state) {
    case 'NOT_STARTED': return { tone: 'bad', title: `The licence starts on ${day(l.startsOn)}`, text: 'Until then Vero takes no new documents.' }
    case 'EXPIRING': return { tone: 'warn', title: `The licence ends on ${licenceDay(l.expiresOn)}`, text: l.renewalStartsOn ? `The renewal is stored and starts on ${day(l.renewalStartsOn)}.` : 'Ask your account manager for the renewal.' }
    case 'EXPIRED_IN_GRACE': return { tone: 'bad', title: `The licence ended on ${licenceDay(l.expiresOn)}`, text: `New documents are taken until ${licenceDay(l.graceEndsOn)}. After that Vero takes none; viewing goes on.` }
    case 'EXPIRED_GRACE_OVER': return { tone: 'bad', title: `The licence ended on ${licenceDay(l.expiresOn)}`, text: 'Vero takes no new documents. Viewing, printing, redaction, holds and reports go on. Store the renewal; no restart is needed.' }
    default: return { tone: 'ok', title: 'The licence is valid', text: l.expiresOn ? `It ends on ${day(l.expiresOn)}.` : 'It has no end date.' }
  }
}
const USE: Record<string, string> = { STORAGE: 'Storage', CONCURRENT_SEATS: 'People at the same time', NAMED_SEATS: 'Stewards and administrators' }
const LEVEL: Record<LicenceUse['level'], string> = { WITHIN: '', NEAR_LIMIT: 'Near the allowance', AT_LIMIT: 'At the allowance', OVER_LIMIT: 'Over the allowance' }
export const useName = (type: string) => USE[type] ?? type
export const useAmount = (u: LicenceUse) => `${u.used.toLocaleString()}${u.limit ? ` of ${u.limit.toLocaleString()}` : ''}${u.type === 'STORAGE' ? ' GB' : ''}`
/** Nothing is ever stopped by an allowance. */
export const useNote = (u: LicenceUse) => LEVEL[u.level]
