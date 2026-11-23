/**
 * Task 0053: the words of the Storage and retention report, kept apart so they can be tested. Counts only: sending
 * systems, levels, retention rules and numbers, never a document.
 */

const count = (n: number) => new Intl.NumberFormat('en-GB').format(n)

/** Stored bytes in words, in powers of 1,024: "512 bytes", "1.5 KB", "20.3 GB" (one decimal below 100). */
export function bytesWords(bytes: number): string {
  if (bytes < 1024) return `${count(bytes)} byte${bytes === 1 ? '' : 's'}`
  const units = ['KB', 'MB', 'GB', 'TB', 'PB']
  let v = bytes / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  const shown = v < 100 ? (Math.round(v * 10) / 10).toFixed(1).replace(/\.0$/, '') : count(Math.round(v))
  return `${shown} ${units[i]}`
}

/** A retention rule's name: "One year (365 days)", "Kept 30 days" (no policy of that length), or the keep-forever name. */
export function ruleWords(rule: { retentionDays: number | null; ruleName: string | null; keptForever: boolean }): string {
  if (rule.keptForever) return rule.ruleName ?? 'Keep forever'
  const days = `${count(rule.retentionDays ?? 0)} day${rule.retentionDays === 1 ? '' : 's'}`
  return rule.ruleName ? `${rule.ruleName} (${days})` : `Kept ${days}`
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
/** "2026-11" as "Nov 2026". */
export const monthWords = (month: string) => `${MONTHS[Number(month.slice(5, 7)) - 1]} ${month.slice(0, 4)}`

/** When the counts were made, relative to now: "counted 4 minutes ago", "counted just now", else the time. */
export function countedWords(countedAt: string, now: Date = new Date()): string {
  const minutes = Math.floor((now.getTime() - Date.parse(countedAt)) / 60_000)
  if (minutes < 1) return 'counted just now'
  if (minutes < 60) return `counted ${minutes} minute${minutes === 1 ? '' : 's'} ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `counted ${hours} hour${hours === 1 ? '' : 's'} ago`
  return `counted ${new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(countedAt))}`
}
