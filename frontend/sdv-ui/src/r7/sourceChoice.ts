import type { Source } from './data'

/**
 * Which sending systems Records and Home search, and which one they start with (task 0017).
 *
 * - Only a system with at least one searchable field can be searched or offered; one without (a paused or
 *   half-set-up system, for example) is never offered and never the default.
 * - The default is the person's last choice in this browser, if it can still be searched; otherwise the first by
 *   name. The server already lists systems by name (then key), so the same data gives the same default every time.
 *
 * Not yet in the rule, because the search schema doesn't say it (see report 0017): whether a system holds any
 * records, when it last delivered, and whether it is paused.
 */
export const searchableSources = (sources: Source[]) => sources.filter((s) => s.fields.some((f) => f.searchable))

const LAST_CHOICE_KEY = 'vero.records.sourceId'

/** The person's last choice in this browser; nothing when storage is unavailable (private window, blocked). */
function lastChoice(): string | null {
  try { return window.localStorage.getItem(LAST_CHOICE_KEY) } catch { return null }
}

/** Remembers the system the person chose in Records, for the next visit in this browser. */
export function rememberSourceChoice(sourceId: string) {
  try { window.localStorage.setItem(LAST_CHOICE_KEY, sourceId) } catch { /* storage unavailable: the rule falls back to the name order */ }
}

export function defaultSource(sources: Source[]): Source | undefined {
  const candidates = searchableSources(sources)
  const last = lastChoice()
  return candidates.find((s) => s.id === last) ?? candidates[0]
}

export const NOTHING_TO_SEARCH = 'There’s nothing to search yet. Records appear here once a sending system delivers documents.'
