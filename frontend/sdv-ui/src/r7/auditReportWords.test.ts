// Task 0050: the sealed audit report screen's helpers: the fingerprint worked out in the browser (the server's SHA-256),
// a pasted fingerprint, the period's days (the end is the next day's start), the filter words and the form's checks.
import { describe, expect, it } from 'vitest'
import { dayWords, filterWords, pastedFingerprint, periodDays, periodProblem, periodWords, sha256Hex, shortFingerprint } from './auditReportWords'

const bytes = (text: string) => new TextEncoder().encode(text).buffer as ArrayBuffer

describe('sha256Hex', () => {
  it('is the SHA-256 the server uses (known value of "abc")', async () => {
    expect(await sha256Hex(bytes('abc'))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  })
  it('changes when one character of the file changes', async () => {
    const a = await sha256Hex(bytes('"Events","2"\r\n'))
    const b = await sha256Hex(bytes('"Events","3"\r\n'))
    expect(a).toMatch(/^[0-9a-f]{64}$/)
    expect(a).not.toBe(b)
  })
})

describe('pastedFingerprint', () => {
  const f = 'ab'.repeat(32)
  it('takes 64 hex characters in any case, ignoring spaces and line breaks', () => {
    expect(pastedFingerprint(f.toUpperCase())).toBe(f)
    expect(pastedFingerprint(` ${f.slice(0, 32)}\n${f.slice(32)} `)).toBe(f)
  })
  it('refuses anything else', () => {
    expect(pastedFingerprint(f.slice(1))).toBeNull()
    expect(pastedFingerprint(f.slice(1) + 'g')).toBeNull()
    expect(pastedFingerprint('')).toBeNull()
  })
})

describe('the period', () => {
  const report = { from: '2026-11-01T00:00:00Z', to: '2026-11-23T00:00:00Z' }
  it('names the last day included, not the end', () => {
    expect(periodDays(report)).toEqual({ first: '2026-11-01', last: '2026-11-22' })
    expect(periodWords(report)).toBe('1 Nov 2026 to 22 Nov 2026')
  })
  it('names one day once', () => expect(periodWords({ from: '2026-11-20T00:00:00Z', to: '2026-11-21T00:00:00Z' })).toBe('20 Nov 2026'))
  it('writes a UTC day as written', () => expect(dayWords('2026-01-09')).toBe('9 Jan 2026'))
})

describe('filterWords', () => {
  it('says the filters as the stamp does', () => {
    expect(filterWords({ outcome: 'DENIED', recordNumber: '300000000001', sensitivityLevel: 'HIGHLY_CONFIDENTIAL' }))
      .toBe('Result: Refused; Record: 300000000001; Level: Highly Confidential')
  })
  it('says None without filters', () => expect(filterWords({})).toBe('None'))
})

describe('periodProblem', () => {
  it('needs both days, as days, in order', () => {
    expect(periodProblem('', '2026-11-22')).toBe('Choose the first and last day.')
    expect(periodProblem('1/11/2026', '2026-11-22')).toMatch(/day, month and year/)
    expect(periodProblem('2026-11-23', '2026-11-22')).toMatch(/comes after/)
    expect(periodProblem('2026-11-22', '2026-11-22')).toBeNull()
  })
})

describe('shortFingerprint', () => {
  it('keeps the start and the end', () => expect(shortFingerprint('0123456789abcdef'.repeat(4))).toBe('01234567…cdef'))
})
