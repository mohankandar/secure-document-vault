import { describe, expect, it } from 'vitest'
import type { Field } from './model'
import { filterHint, modeHint, notEverywhere, wholeHint } from './searchRouting'

const field = (over: Partial<Field>): Field => ({ key: 'k', label: 'Last name', type: 'STRING', identifier: false, searchable: true, mode: 'PARTIAL', normalization: 'NONE', ...over })

describe('sensitive fields (task 0079)', () => {
  it('are marked and never offered by a part of the value', () => {
    const f = field({ sensitive: true })
    expect(modeHint(f)).toBe('Sensitive · whole value only')
    expect(filterHint(f)).toBe('The whole value')
    expect(wholeHint(f)).toBe('Sensitive · whole value')
    expect(notEverywhere([f])).toContain('Last name')
  })
  it('other fields keep their match', () => {
    expect(modeHint(field({}))).toBe('Contains')
    expect(filterHint(field({}))).toBe('Any part')
    expect(wholeHint(field({}))).toBe('Exact')
    expect(notEverywhere([field({})])).toBeNull()
  })
})
