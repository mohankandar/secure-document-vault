// Task 0054: the Activity report's words.
import { describe, expect, it } from 'vitest'
import { activityPeriodWords, attemptRoleWords, registeredNote } from './Reports'

describe('activity words (task 0054)', () => {
  it('names the usual periods and falls back to the period words', () => {
    expect(activityPeriodWords('2026-11-17', '2026-11-23', '2026-11-23')).toBe('the last 7 days')
    expect(activityPeriodWords('2026-10-25', '2026-11-23', '2026-11-23')).toBe('the last 30 days')
    expect(activityPeriodWords('2026-11-01', '2026-11-23', '2026-11-23')).toBe('This month (November)')
  })
  it('says the roles, and when none was recorded', () => {
    expect(attemptRoleWords(['BUSINESS_USER'])).toBe('Business user')
    expect(attemptRoleWords([])).toBe('Role not recorded')
  })
  it('gives a registered export its reference and fingerprint', () => {
    expect(registeredNote('r-1', 'a'.repeat(64))).toBe(`The file is saved. The export is recorded in the audit. Reference r-1. SHA-256 fingerprint ${'a'.repeat(64)}.`)
    expect(registeredNote(null, null)).toBe('The file is saved. The export is recorded in the audit.')
  })
})
