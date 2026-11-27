// Task 0081: notifications in words, and the rules the screens keep (the server checks them again).
// Never a secret: a channel is shown by the path where its secret is kept.

export type NotificationChannel = 'EMAIL' | 'TEAMS' | 'SLACK'
export type Frequency = 'IMMEDIATELY' | 'EVERY_HOUR' | 'EVERY_4_HOURS' | 'ONCE_A_DAY'
export type Destination = { id: string; channel: NotificationChannel; secretPath: string | null; state: string; code: string | null; reason: string | null; lastAttempt: string | null }
export type Channels = { appAddress: string | null; connected: boolean; mailConfigured: boolean; mailAuth: 'PASSWORD' | 'OAUTH2_MICROSOFT' | null; mailSecretPath: string | null; destinations: Destination[] }
export type EventRow = { id: string; name: string; critical: boolean; continuous: boolean; enabled: boolean; frequency: Frequency; dailyAt: string | null; email: boolean; channels: string[]; withIdentifier: boolean }
export type EventChange = { enabled?: boolean; frequency?: Frequency; dailyAt?: string | null; email?: boolean; channels?: string[] }
export type Recipient = { recipientId: string; role: string; userId: string; email: string; addedBy: string; addedAt: string; confirmed?: boolean }
export type ItNotifications = { channels: Channels; customerZone: string | null }
export type BusinessNotifications = { events: EventRow[]; channels: Destination[]; channelsConnected: boolean; ownZone: string | null; customerZone: string | null }
export type ZoneSource = 'ENTITY' | 'CUSTOMER' | 'NONE'

export const FREQUENCIES: Frequency[] = ['IMMEDIATELY', 'EVERY_HOUR', 'EVERY_4_HOURS', 'ONCE_A_DAY']
const FREQUENCY_NAME: Record<Frequency, string> = { IMMEDIATELY: 'Immediately', EVERY_HOUR: 'Every hour', EVERY_4_HOURS: 'Every 4 hours', ONCE_A_DAY: 'Once a day' }
/** The name of a choice in the list ("Once a day", without the time). */
export const frequencyName = (f: Frequency) => FREQUENCY_NAME[f] ?? f
/** How often, in words: "Once a day at 08:00". */
export const frequencyWords = (f: Frequency, dailyAt: string | null) => (f === 'ONCE_A_DAY' ? `Once a day at ${dailyAt ?? '—'}` : frequencyName(f))
/** A critical event (something is down) is always on and goes at once or every hour. */
export const allowedFrequencies = (critical: boolean): Frequency[] => (critical ? ['IMMEDIATELY', 'EVERY_HOUR'] : FREQUENCIES)

const CHANNEL_NAME: Record<string, string> = { EMAIL: 'E-mail', TEAMS: 'Teams', SLACK: 'Slack' }
export const channelWords = (channel: string) => CHANNEL_NAME[channel] ?? channel
/** The Teams and Slack destinations an event can go to. */
export const chatDestinations = (list: Destination[]) => list.filter((d) => d.channel === 'TEAMS' || d.channel === 'SLACK')
export const mailAuthWords = (auth: Channels['mailAuth']) => (auth === 'PASSWORD' ? 'User name and password' : auth === 'OAUTH2_MICROSOFT' ? 'Microsoft 365' : '—')

/** A destination's health. A state Vero doesn't know is shown as a warning, never as working. */
export function stateTone(state: string): 'ok' | 'warn' | 'bad' | '' {
  if (state === 'Working') return 'ok'
  if (state === 'Down') return 'bad'
  if (state === 'Not used yet') return ''
  return 'warn'
}

/** Task 0081: where a report's time zone comes from (identity: the entity's own, else the customer's, else UTC). */
export function zoneSourceWords(source: string): string {
  if (source === 'ENTITY') return 'this entity’s own time zone'
  if (source === 'CUSTOMER') return 'the customer’s time zone'
  if (source === 'NONE') return 'no time zone is set, so UTC'
  return 'where the time zone comes from isn’t known'
}
/** The entity's time zone in a sentence: its own, or the customer's it follows. */
export const entityZoneWords = (ownZone: string | null, customerZone: string | null) =>
  ownZone ? ownZone : `Follows the customer’s: ${customerZone || 'UTC'}`

/** An IANA name the browser knows (America/New_York). The server checks it too. */
export function validZone(zone: string): boolean {
  const z = zone.trim()
  if (!z || !/^[A-Za-z_+\-/0-9]+$/.test(z)) return false
  try { new Intl.DateTimeFormat('en', { timeZone: z }); return true } catch { return false }
}
export const validDailyAt = (v: string | null) => !!v && /^([01]\d|2[0-3]):[0-5]\d$/.test(v)
/** Task 0100 (VSEC-204): why nothing is sent yet, or null when the channels are connected under the install's address. */
export function appAddressNote(c: Pick<Channels, 'appAddress' | 'connected'>): string | null {
  if (c.connected) return null
  if (!c.appAddress) return 'The app address is not set in this install. IT sets sdv.public-url (SDV_PUBLIC_URL) to the https address people open Vero on, then starts Vero again.'
  return 'Connect the channels to start sending. Until then Vero sends no messages.'
}
/** Task 0100 (VSEC-203): an entry gets messages only when its person held the role at their last sign-in. */
export function confirmedWords(r: Pick<Recipient, 'confirmed'>): string | null {
  return r.confirmed === false ? 'Not confirmed: this person has not signed in with this role since, so gets no messages.' : null
}
export const validEmail = (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.trim())

/** What a row may be changed to: a critical row stays on, and keeps to the frequencies allowed for it. */
export function draftProblem(d: EventRow): string | null {
  if (d.critical && !d.enabled) return 'This one is always on.'
  if (!allowedFrequencies(d.critical).includes(d.frequency)) return 'Choose Immediately or Every hour.'
  if (d.frequency === 'ONCE_A_DAY' && !validDailyAt(d.dailyAt)) return 'Give the time as HH:mm, for example 08:00.'
  return null
}

const sameList = (a: string[], b: string[]) => a.length === b.length && [...a].sort().join('\n') === [...b].sort().join('\n')
/** Only what changed. A critical row never sends On/Off; a time only goes with Once a day. */
export function changeOf(saved: EventRow, d: EventRow): EventChange {
  const c: EventChange = {}
  if (!d.critical && d.enabled !== saved.enabled) c.enabled = d.enabled
  if (d.frequency !== saved.frequency) c.frequency = d.frequency
  if (d.frequency === 'ONCE_A_DAY' && (d.dailyAt !== saved.dailyAt || c.frequency)) c.dailyAt = d.dailyAt
  if (d.email !== saved.email) c.email = d.email
  if (!sameList(d.channels, saved.channels)) c.channels = [...d.channels]
  return c
}
export const hasChange = (c: EventChange) => Object.keys(c).length > 0

/** The roles whose lists a Business Admin keeps, in this order. */
export const RECIPIENT_ROLES = ['BUSINESS_USER', 'DOCUMENT_STEWARD', 'RESTRICTED_STEWARD', 'BUSINESS_ADMIN'] as const
const ROLE_NAME: Record<string, string> = { BUSINESS_USER: 'Business users', DOCUMENT_STEWARD: 'Document stewards', RESTRICTED_STEWARD: 'Restricted stewards', BUSINESS_ADMIN: 'Business admins', IT_ADMIN: 'IT Admins' }
export const roleListWords = (role: string) => ROLE_NAME[role] ?? role
/** The lists by role, in the order above; a role Vero doesn't list comes last under its own name. */
export function byRole(list: Recipient[]): { role: string; people: Recipient[] }[] {
  const roles = [...RECIPIENT_ROLES, ...[...new Set(list.map((r) => r.role))].filter((r) => !(RECIPIENT_ROLES as readonly string[]).includes(r))]
  return roles.map((role) => ({ role, people: list.filter((r) => r.role === role) }))
}

/** SDV-NTF-007: notifications are switched off in this install, or the entity isn't set up. Not a failure. */
export const SWITCHED_OFF = 'SDV-NTF-007'
