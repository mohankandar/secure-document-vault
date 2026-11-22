// Task 0044: the shared error words (errorWords): each kind, a known code, an unknown code, a 503 with and without
// Retry-After, no answer, and the server's own message never reaching the text.
import { describe, expect, it } from 'vitest'
import { ApiRequestError } from '../api'
import { CODE_WORDS, DOCUMENT_PROBLEM_WORDS, GENERAL_WORDS, errorWords, itemWords } from './problemWords'

const fixedRef = { newReference: () => 'made-up-ref' }
const err = (status: number, code?: string, ref?: string, retryAfter?: number) => new ApiRequestError(`HTTP ${status}`, status, ref, code, retryAfter)

describe('errorWords: refusal (4xx with a code)', () => {
  it('a known code gets the catalog sentence and the code', () => {
    const w = errorWords(err(400, 'SDV-RPT-002', 'ref-1'))
    expect(w.kind).toBe('refusal')
    expect(w.text).toBe(`${CODE_WORDS['SDV-RPT-002']} (SDV-RPT-002)`)
    expect(w.text).not.toContain('Ref ')
  })
  it('an unknown code gets the general refusal sentence and the code', () => {
    const w = errorWords(err(409, 'SDV-ZZZ-999'))
    expect(w.kind).toBe('refusal')
    expect(w.text).toBe(`${GENERAL_WORDS.refusal} (SDV-ZZZ-999)`)
  })
  it('no code: the general sentence alone', () => {
    expect(errorWords(err(404)).text).toBe(GENERAL_WORDS.refusal)
  })
  it('an export also shows the reference', () => {
    expect(errorWords(err(400, 'SDV-RPT-005', 'ref-2'), { withReference: true }).text).toBe(`${CODE_WORDS['SDV-RPT-005']} Ref ref-2. (SDV-RPT-005)`)
  })
})

describe('errorWords: not allowed (401/403)', () => {
  it('401 without a code: sign in again', () => {
    const w = errorWords(err(401))
    expect(w.kind).toBe('notAllowed')
    expect(w.text).toBe('You don’t have access to this. Sign in again.')
  })
  it('403 without a code: ask the IT team', () => {
    expect(errorWords(err(403)).text).toBe('You don’t have access to this. If you need it for your work, ask your IT team.')
  })
  it('403 with a known code: what to do, and the code', () => {
    const w = errorWords(err(403, 'SDV-APR-006'))
    expect(w.kind).toBe('notAllowed')
    expect(w.text).toBe(`You don’t have access to this. ${CODE_WORDS['SDV-APR-006']} (SDV-APR-006)`)
  })
  it('403 with an unknown code: the general words and the code', () => {
    expect(errorWords(err(403, 'SDV-ZZZ-001')).text).toBe('You don’t have access to this. If you need it for your work, ask your IT team. (SDV-ZZZ-001)')
  })
})

describe('errorWords: Vero’s fault (5xx)', () => {
  it('500: something went wrong, the reference and the code', () => {
    const w = errorWords(err(500, 'SDV-SYS-013', 'abc-123'))
    expect(w.kind).toBe('fault')
    expect(w.reference).toBe('abc-123')
    expect(w.text).toBe('Something went wrong in Vero. Try again. If it keeps happening, give your IT team this reference: Ref abc-123. (SDV-SYS-013)')
  })
  it('a known 5xx code adds its sentence', () => {
    expect(errorWords(err(500, 'SDV-SYS-017', 'r')).text).toBe(`Something went wrong in Vero. ${CODE_WORDS['SDV-SYS-017']} Try again. If it keeps happening, give your IT team this reference: Ref r. (SDV-SYS-017)`)
  })
  it('503 without Retry-After: try again in a few minutes', () => {
    expect(errorWords(err(503, 'SDV-SYS-014', 'r1')).text).toBe(`Something went wrong in Vero. ${CODE_WORDS['SDV-SYS-014']} Try again in a few minutes. If it keeps happening, give your IT team this reference: Ref r1. (SDV-SYS-014)`)
  })
  it('503 SDV-SYS-018 with Retry-After: wait those seconds', () => {
    expect(errorWords(err(503, 'SDV-SYS-018', 'r2', 5)).text).toBe(`Something went wrong in Vero. ${CODE_WORDS['SDV-SYS-018']} Try again in 5 seconds. If it keeps happening, give your IT team this reference: Ref r2. (SDV-SYS-018)`)
    expect(errorWords(err(503, 'SDV-SYS-018', 'r3', 1)).text).toContain('Try again in 1 second.')
  })
  it('no reference from the server: one is made, so there is always one', () => {
    const w = errorWords(err(502), fixedRef)
    expect(w.reference).toBe('made-up-ref')
    expect(w.text).toBe('Something went wrong in Vero. Try again. If it keeps happening, give your IT team this reference: Ref made-up-ref.')
  })
  it('an error that is not from the server is Vero’s fault too, with a reference', () => {
    const w = errorWords(new SyntaxError('Unexpected token < in JSON at position 0'), fixedRef)
    expect(w.kind).toBe('fault')
    expect(w.text).toContain('Ref made-up-ref.')
    expect(w.text).not.toContain('Unexpected token')
  })
})

describe('errorWords: network (no answer)', () => {
  it('says Vero can’t be reached', () => {
    const w = errorWords(new ApiRequestError('No answer', 0))
    expect(w.kind).toBe('network')
    expect(w.text).toBe('Vero can’t be reached. Check your connection.')
  })
})

describe('errorWords: the viewer’s DOC codes keep task 0043’s words', () => {
  it('a document fault: its words, the reference and the code', () => {
    expect(errorWords(err(503, 'SDV-DOC-003', 'd1')).text).toBe(`${DOCUMENT_PROBLEM_WORDS['SDV-DOC-003'].text} Ref d1. (SDV-DOC-003)`)
  })
  it('a document refusal: its words and the code', () => {
    expect(errorWords(err(404, 'SDV-DOC-001', 'd2')).text).toBe(`${DOCUMENT_PROBLEM_WORDS['SDV-DOC-001'].text} (SDV-DOC-001)`)
  })
})

describe('the server’s message never reaches the text', () => {
  const secret = 'org.postgresql.util.PSQLException: relation "sdv_document" does not exist'
  it('for every kind and status', () => {
    for (const status of [0, 400, 401, 403, 404, 409, 422, 500, 503]) {
      for (const code of [undefined, 'SDV-SEN-013', 'SDV-ZZZ-999']) {
        const e = new ApiRequestError(secret, status, 'r', code)
        expect(errorWords(e).text, `${status} ${code}`).not.toContain(secret)
        expect(errorWords(e).text, `${status} ${code}`).not.toContain('PSQLException')
      }
    }
  })
  it('for an item of a bulk decision', () => {
    expect(itemWords('REFUSED', 'SDV-APR-008')).toBe(`${CODE_WORDS['SDV-APR-008']} (SDV-APR-008)`)
    expect(itemWords('REFUSED', null)).toBe(GENERAL_WORDS.refusal)
    expect(itemWords('FAILED', null)).toBe('Something went wrong in Vero. Try again.')
  })
})
