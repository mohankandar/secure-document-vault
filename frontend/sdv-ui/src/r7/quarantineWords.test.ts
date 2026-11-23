// Task 0049: the quarantine screens' words and small rules: level counts, the review order, the filter checks, the
// export note, and the "temporarily unavailable" words never naming a level or a reason.
import { describe, expect, it } from 'vitest'
import { ApiRequestError } from '../api'
import { errorWords } from './problemWords'
import { UNAVAILABLE_SENTENCE, exportNote, filterProblem, inQuarantineCount, levelsLine, nameReady, oldestFirst, pageNote, plural, progressWords, reasonReady, releaseWords, scopeWords } from './quarantineWords'
import type { QuarantinedDocument } from './liveApi'

const doc = (id: string, receivedAt: string | null, quarantinedAt = '2026-11-20T10:00:00Z'): QuarantinedDocument => ({
  quarantineId: 'q-' + id, documentId: id, primaryIdentifierLabel: 'Contract', primaryIdentifier: id, level: 'STANDARD', receivedAt, pageCount: 2,
  quarantinedByName: 'Dana Ruiz', quarantinedAt, inForce: true, releasedByName: null, releasedAt: null,
})

describe('levelsLine', () => {
  it('lists the levels with a count, in level order', () => {
    expect(levelsLine(3, 0, 1)).toBe('3 Standard, 1 Highly Confidential')
    expect(levelsLine(0, 2, 0)).toBe('2 Confidential')
  })
  it('says None when nothing is counted', () => expect(levelsLine(0, 0, 0)).toBe('None'))
})

describe('plural and scope words', () => {
  it('counts in words', () => {
    expect(plural(1, 'document')).toBe('1 document')
    expect(plural(4, 'document')).toBe('4 documents')
  })
  it('names how an incident started', () => {
    expect(scopeWords('FILTER')).toBe('By filter')
    expect(scopeWords('DOCUMENT')).toBe('One by one')
  })
})

describe('oldestFirst', () => {
  it('orders by received date, oldest first, without a date last', () => {
    const order = oldestFirst([doc('b', '2026-10-02T00:00:00Z'), doc('n', null), doc('a', '2026-09-30T00:00:00Z')]).map((d) => d.documentId)
    expect(order).toEqual(['a', 'b', 'n'])
  })
  it('breaks a tie on received date by when each was quarantined', () => {
    const order = oldestFirst([doc('late', '2026-10-01T00:00:00Z', '2026-11-21T00:00:00Z'), doc('early', '2026-10-01T00:00:00Z', '2026-11-20T00:00:00Z')]).map((d) => d.documentId)
    expect(order).toEqual(['early', 'late'])
  })
  it('leaves the list it was given unchanged', () => {
    const list = [doc('b', '2026-10-02T00:00:00Z'), doc('a', '2026-09-30T00:00:00Z')]
    oldestFirst(list)
    expect(list.map((d) => d.documentId)).toEqual(['b', 'a'])
  })
})

describe('filterProblem (task 0048, decision 4)', () => {
  const base = { receivedFrom: '2026-11-01', receivedTo: '2026-11-12' }
  it('needs both received dates', () => {
    expect(filterProblem({ receivedFrom: '', receivedTo: '2026-11-12' })).toBe('Choose both received dates.')
    expect(filterProblem({ receivedFrom: '2026-11-01', receivedTo: '' })).toBe('Choose both received dates.')
  })
  it('refuses dates the wrong way round', () => expect(filterProblem({ receivedFrom: '2026-11-12', receivedTo: '2026-11-01' })).toMatch(/comes after/))
  it('refuses a date that is not a day', () => expect(filterProblem({ receivedFrom: '1/11/2026', receivedTo: '2026-11-12' })).toMatch(/day, month and year/))
  it('needs a sending system for a field value', () => {
    expect(filterProblem({ ...base, fields: { contract: '123' } })).toMatch(/sending system/)
    expect(filterProblem({ ...base, sourceId: 's1', fields: { contract: '123' } })).toBeNull()
  })
  it('ignores empty field values', () => expect(filterProblem({ ...base, fields: { contract: '  ' } })).toBeNull())
  it('a date range alone is enough', () => expect(filterProblem(base)).toBeNull())
})

describe('name and reason, as the server takes them', () => {
  it('a name is 1 to 200 characters', () => {
    expect(nameReady(' ')).toBe(false)
    expect(nameReady('Scanner fault')).toBe(true)
    expect(nameReady('x'.repeat(201))).toBe(false)
  })
  it('a reason is 3 to 500 characters', () => {
    expect(reasonReady('ab')).toBe(false)
    expect(reasonReady('abc')).toBe(true)
    expect(reasonReady('x'.repeat(501))).toBe(false)
  })
})

describe('exportNote', () => {
  it('gives the reference and the fingerprint when the server sent them', () => {
    expect(exportNote('3f2a9c1e-0000-4000-8000-000000000000', 'ab'.repeat(32)))
      .toBe(`The file is saved. The export is recorded in the audit. Reference 3f2a9c1e-0000-4000-8000-000000000000. SHA-256 fingerprint ${'ab'.repeat(32)}.`)
  })
  it('leaves out what the server did not send', () => expect(exportNote(null, null)).toBe('The file is saved. The export is recorded in the audit.'))
})

describe('temporarily unavailable: never the level or why', () => {
  it('the sentence names no level and no reason', () => expect(UNAVAILABLE_SENTENCE).not.toMatch(/confidential|standard|level|reason|quarantin/i))
  it('the SDV-QUA-001 refusal words name no level', () => {
    const w = errorWords(new ApiRequestError('HTTP 403', 403, 'ref-1', 'SDV-QUA-001'))
    expect(w.text).toMatch(/temporarily unavailable/)
    expect(w.text).not.toMatch(/confidential|standard/i)
  })
  it('says nothing else: not when it comes back, nor who reviews it (task 0051)', () => {
    const w = errorWords(new ApiRequestError('HTTP 403', 403, 'ref-1', 'SDV-QUA-001'))
    expect(w.text).toContain(UNAVAILABLE_SENTENCE)
    expect(w.text).not.toMatch(/comes back|review|steward|urgent/i)
  })
})

describe('no limit (task 0051)', () => {
  it('says the progress with separators, never more than all', () => {
    expect(progressWords(412000, 1000000)).toBe('412,000 of 1,000,000 done')
    expect(progressWords(1200, 1000)).toBe('1,000 of 1,000 done')
  })
  it('counts every document the filter caught while the incident is prepared', () => {
    expect(inQuarantineCount({ preparing: true, expectedCount: 20000, documentsInForce: 1000 })).toBe(20000)
    expect(inQuarantineCount({ preparing: false, expectedCount: 20000, documentsInForce: 19999 })).toBe(19999)
    expect(inQuarantineCount({ preparing: false, expectedCount: null, documentsInForce: 3 })).toBe(3)
  })
  // Task 0052: the 0051 "first 500 of N" note is replaced by the page note (the lists now page through the incident).
})

describe('a million documents (task 0052)', () => {
  it('says where a page sits, and nothing when one page shows all', () => {
    expect(pageNote(1, 100, 20000)).toBe('Documents 1 to 100 of 20,000.')
    expect(pageNote(101, 100, 1000000)).toBe('Documents 101 to 200 of 1,000,000.')
    expect(pageNote(19901, 99, 20000)).toBe('Documents 19,901 to 19,999 of 20,000.')
    expect(pageNote(1, 12, 12)).toBeNull()
    expect(pageNote(201, 0, 200)).toBe('None more of 200.')
  })
  it('says how far a whole release has come, never more than all', () => {
    expect(releaseWords(300000, 1000000)).toBe('300,000 of 1,000,000 released')
    expect(releaseWords(12, 10)).toBe('10 of 10 released')
  })
})
