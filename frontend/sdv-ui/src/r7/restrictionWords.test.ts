// Task 0055: the Restrictions page's words.
import { describe, expect, it } from 'vitest'
import { changeScopeWords, levelWords, oldestWords, recordWords } from './restrictionWords'

describe('restriction words (task 0055)', () => {
  it('names a record by its primary identifier only', () => {
    expect(recordWords({ identifierLabel: 'Contract number', identifier: '300000000001' })).toBe('Contract number 300000000001')
    expect(recordWords({ identifierLabel: null, identifier: '300000000001' })).toBe('300000000001')
    expect(recordWords({ identifierLabel: 'Contract number', identifier: null })).toBe('Record not named')
  })
  it('says levels, scopes and the oldest waiting request', () => {
    expect(levelWords('HIGHLY_CONFIDENTIAL')).toBe('Highly Confidential')
    expect(levelWords(null)).toBe('Not set')
    expect(changeScopeWords('RECORD')).toBe('Whole record')
    expect(changeScopeWords('DOCUMENT')).toBe('One document')
    expect(oldestWords('2026-11-20T10:00:00Z')).toBe('The oldest has waited since 20 Nov 2026.')
    expect(oldestWords(null)).toBe('')
  })
})
