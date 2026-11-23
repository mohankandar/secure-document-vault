/** Task 0055: the Restrictions page's words, kept apart so they can be tested. */

const LEVELS: Record<string, string> = { STANDARD: 'Standard', CONFIDENTIAL: 'Confidential', HIGHLY_CONFIDENTIAL: 'Highly Confidential' }

/** A level in words; none (a first level set) is "Not set". */
export const levelWords = (level: string | null) => (level ? LEVELS[level] ?? level : 'Not set')

/** DOCUMENT: one document of the record; RECORD: the whole record. */
export const changeScopeWords = (scope: string) => (scope === 'RECORD' ? 'Whole record' : 'One document')

/** A record by its primary identifier: "Contract number 300000000001"; a record Vero can't name is "Record not named". */
export function recordWords(r: { identifierLabel: string | null; identifier: string | null }): string {
  if (!r.identifier) return 'Record not named'
  return r.identifierLabel ? `${r.identifierLabel} ${r.identifier}` : r.identifier
}

/** The oldest waiting request: "The oldest has waited since 20 Nov 2026." */
export function oldestWords(oldest: string | null): string {
  if (!oldest) return ''
  return `The oldest has waited since ${new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(oldest))}.`
}
