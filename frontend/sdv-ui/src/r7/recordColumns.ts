import type { Source } from './model'

/**
 * What a record shows in a list (task 0018, the canvas's Recently viewed and Search results):
 * the number, the record's title, and one more field, for example "300000000001 · Jordan Ellis · Mortgage".
 *
 * - Number: the record's first identifier, shown once.
 * - Title: the server's record title (Record title setting); a record without one is named by its number.
 * - Extra: the first field, in the sending system's own field order, that is not an identifier and is not already
 *   in the title (a preview value that the title contains is not repeated).
 */
type Value = { fieldKey?: string; label: string; value: string }

const words = (s: string) => s.toLocaleLowerCase().split(/\s+/).filter(Boolean)
/** True when every word of the value appears, in order, in the title ("Patel" in "Ava Patel"). */
export function inTitle(title: string | null | undefined, value: string) {
  if (!title || !value.trim()) return false
  const t = ` ${words(title).join(' ')} `
  return t.includes(` ${words(value).join(' ')} `)
}

export function orderedPreview(preview: Value[], source?: Source): Value[] {
  if (!source) return preview
  const position = (v: Value) => {
    const i = source.fields.findIndex((f) => (v.fieldKey ? f.key === v.fieldKey : f.label === v.label))
    return i < 0 ? Number.MAX_SAFE_INTEGER : i
  }
  return [...preview].sort((a, b) => position(a) - position(b))
}

export function extraField(title: string | null | undefined, preview: Value[], source?: Source): Value | undefined {
  return orderedPreview(preview, source).find((v) => v.value.trim() && !inTitle(title, v.value))
}

/** The title column's heading: the field the title is made of when it is one field ("Borrower"), else "Name". */
export function titleLabel(rows: { title?: string | null; preview: Value[] }[]): string {
  const withTitle = rows.filter((r) => r.title)
  if (!withTitle.length) return 'Name'
  const labels = withTitle[0].preview.filter((v) => v.value.trim() === withTitle[0].title!.trim()).map((v) => v.label)
  return labels.find((label) => withTitle.every((r) => r.preview.some((v) => v.label === label && v.value.trim() === r.title!.trim()))) ?? 'Name'
}
