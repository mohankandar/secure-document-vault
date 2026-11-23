// Task 0053: the Storage and retention report's words.
import { describe, expect, it } from 'vitest'
import { bytesWords, countedWords, monthWords, ruleWords } from './storageWords'

describe('storage words (task 0053)', () => {
  it('says stored bytes in powers of 1,024', () => {
    expect(bytesWords(0)).toBe('0 bytes')
    expect(bytesWords(1)).toBe('1 byte')
    expect(bytesWords(1536)).toBe('1.5 KB')
    expect(bytesWords(1024 * 1024)).toBe('1 MB')
    expect(bytesWords(20.3 * 1024 ** 3)).toBe('20.3 GB')
    expect(bytesWords(512 * 1024 ** 4)).toBe('512 TB')
  })
  it('names a retention rule, and keep forever never with days', () => {
    expect(ruleWords({ retentionDays: 365, ruleName: 'One year', keptForever: false })).toBe('One year (365 days)')
    expect(ruleWords({ retentionDays: 30, ruleName: null, keptForever: false })).toBe('Kept 30 days')
    expect(ruleWords({ retentionDays: null, ruleName: null, keptForever: true })).toBe('Keep forever')
  })
  it('says the month and when the counts were made', () => {
    expect(monthWords('2026-11')).toBe('Nov 2026')
    const now = new Date('2026-11-23T10:30:00Z')
    expect(countedWords('2026-11-23T10:29:40Z', now)).toBe('counted just now')
    expect(countedWords('2026-11-23T10:26:00Z', now)).toBe('counted 4 minutes ago')
    expect(countedWords('2026-11-23T08:00:00Z', now)).toBe('counted 2 hours ago')
  })
})
