import { describe, expect, it } from 'vitest'
import { licenceSentence, useAmount, useNote, type LicenceView } from './licenceWords'

const base: LicenceView = { state: 'VALID', message: '', intakeAllowed: true, productLicensed: true, testEnvironment: false, licenceId: 'LIC-1', version: 1, customer: 'Acme', kind: 'PRODUCTION', startsOn: '2027-01-01', expiresOn: '2027-12-31', updatesUntil: null, graceEndsOn: '2028-01-30', renewalStartsOn: null, clockBehind: false, clockMessage: null, entities: 2, entitySlot: 'ENT-1', month: '2027-06', usage: [] }

describe('the licence in words', () => {
  it('says the state, and what stops', () => {
    expect(licenceSentence(base)).toEqual({ tone: 'ok', title: 'The licence is valid', text: 'It ends on 31 December 2027.' })
    expect(licenceSentence({ ...base, expiresOn: null }).text).toBe('It has no end date.')
    expect(licenceSentence({ ...base, state: 'EXPIRED_IN_GRACE' }).text).toContain('until 30 January 2028')
    expect(licenceSentence({ ...base, state: 'EXPIRED_GRACE_OVER' }).text).toContain('takes no new documents')
    expect(licenceSentence({ ...base, state: 'NOT_STARTED' }).tone).toBe('bad')
    expect(licenceSentence({ ...base, productLicensed: false }).title).toContain('doesn’t name Vero')
  })
  it('shows use against the allowance', () => {
    expect(useAmount({ type: 'STORAGE', unit: 'GB', used: 12, limit: 100, level: 'WITHIN' })).toBe('12 of 100 GB')
    expect(useAmount({ type: 'NAMED_SEATS', unit: 'seats', used: 3, limit: 0, level: 'WITHIN' })).toBe('3')
    expect(useNote({ type: 'STORAGE', unit: 'GB', used: 101, limit: 100, level: 'OVER_LIMIT' })).toBe('Over the allowance')
  })
})
