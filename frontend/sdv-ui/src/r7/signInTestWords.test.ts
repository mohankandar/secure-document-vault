// Task 0057: the Sign-in tab's "Test sign-in" words.
import { describe, expect, it } from 'vitest'
import { checkedWords, codeWords, outcomeWords } from './signInTestWords'

describe('test sign-in words (task 0057)', () => {
  it('says what was checked, and that the audience needs a company sign-in', () => {
    expect(checkedWords(true)).toContain('API audience')
    expect(checkedWords(true)).not.toContain('wasn’t checked')
    expect(checkedWords(false)).toContain('discovery document, its signing keys and issuer')
    expect(checkedWords(false)).toContain('wasn’t checked here')
  })
  it('passes only on PASSED (fail closed)', () => {
    expect(outcomeWords({ outcome: 'PASSED' })).toEqual({ lead: 'Test sign-in passed.', tone: 'ok' })
    expect(outcomeWords({ outcome: 'FAILED' }).tone).toBe('bad')
    expect(outcomeWords({ outcome: 'NO_CONNECTION' }).tone).toBe('info')
    expect(outcomeWords({ outcome: 'SOMETHING_NEW' as never }).tone).toBe('bad')
  })
  it('shows platform-core’s code as it is, with the outbound reason', () => {
    expect(codeWords({ code: null, reason: null })).toBeNull()
    expect(codeWords({ code: 'IDN_TEST_KEYS_MISSING', reason: null })).toBe('IDN_TEST_KEYS_MISSING')
    expect(codeWords({ code: 'IDN_TEST_ISSUER_UNREACHABLE', reason: 'OUT_TLS_FAILED' })).toBe('IDN_TEST_ISSUER_UNREACHABLE (OUT_TLS_FAILED)')
  })
})

// Task 0099: platform-core 1.2.0 refuses a replacement of the connection in use without a token test.
import { codeHint } from './signInTestWords'
describe('code hints (task 0099)', () => {
  it('says what to do about IDN_TEST_SIGN_IN_REQUIRED, and nothing for other codes', () => {
    expect(codeHint('IDN_TEST_SIGN_IN_REQUIRED')).toContain('setup identity rollback-login-connection')
    expect(codeHint('IDN_TEST_SIGN_IN_REQUIRED')).toContain('people go on signing in')
    expect(codeHint('IDN_TEST_KEYS_MISSING')).toBeNull()
    expect(codeHint(null)).toBeNull()
  })
})
