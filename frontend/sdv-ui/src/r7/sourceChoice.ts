import type { Source } from './model'

/**
 * Which sending systems Records and Home search, and which one they start with (tasks 0017 and 0023).
 *
 * - Only a system with at least one searchable field can be searched or offered; one without (a half-set-up
 *   system, for example) is never offered and never the default.
 * - The default, in one place for Home and Records (defaultSource):
 *   1. only a system with a searchable field and at least one record can be the default;
 *   2. the person's last choice in this browser, if it still qualifies;
 *   3. else the active system that delivered most recently;
 *   4. a paused system only if no active one qualifies (then the paused one that delivered most recently);
 *   5. else by name, in the server's own order (name, then key).
 *   When no searchable system holds a record yet (a new installation), there is no default: Records and Home show
 *   NOTHING_TO_SEARCH instead (the owner's answer to task 0023, question 2).
 */
export const searchableSources = (sources: Source[]) => sources.filter((s) => s.fields.some((f) => f.searchable))

/** Rule 1: a system can be the default only if it can be searched and holds at least one record. */
export const canBeDefault = (s: Source) => s.fields.some((f) => f.searchable) && s.hasRecords

/** No system can be the default: nothing can be searched, or nothing searchable has been delivered yet. */
export const nothingToSearchIn = (sources: Source[]) => !sources.some(canBeDefault)

const LAST_CHOICE_KEY = 'vero.records.sourceId'

/** The person's last choice in this browser; nothing when storage is unavailable (private window, blocked). */
function lastChoice(): string | null {
  try { return window.localStorage.getItem(LAST_CHOICE_KEY) } catch { return null }
}

/** Remembers the system the person chose in Records, for the next visit in this browser. */
export function rememberSourceChoice(sourceId: string) {
  try { window.localStorage.setItem(LAST_CHOICE_KEY, sourceId) } catch { /* storage unavailable: the rule falls back to the newest delivery */ }
}

const deliveredAt = (s: Source) => { const t = s.lastDeliveredAt ? Date.parse(s.lastDeliveredAt) : NaN; return Number.isNaN(t) ? -Infinity : t }

/** The one that delivered most recently; equal times keep the server's name order (Array.sort is stable). */
const newestDelivery = (list: Source[]) => [...list].sort((a, b) => deliveredAt(b) - deliveredAt(a))[0]

/** The default rule without the browser: `last` is the stored last choice, if any. */
export function pickDefault(sources: Source[], last: string | null): Source | undefined {
  const qualifying = sources.filter(canBeDefault)
  const remembered = last ? qualifying.find((s) => s.id === last) : undefined
  if (remembered) return remembered
  const active = qualifying.filter((s) => s.status === 'ACTIVE')
  if (active.length) return newestDelivery(active)
  if (qualifying.length) return newestDelivery(qualifying)
  return undefined
}

export function defaultSource(sources: Source[]): Source | undefined {
  return pickDefault(sources, lastChoice())
}

export const NOTHING_TO_SEARCH = 'There’s nothing to search yet. Records appear here once a sending system delivers documents.'
