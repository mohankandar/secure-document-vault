import { describe, expect, it } from 'vitest'
import {
  allowedFrequencies, byRole, changeOf, channelWords, chatDestinations, draftProblem, entityZoneWords, frequencyWords, hasChange, roleListWords, stateTone,
  validAppAddress, validDailyAt, validEmail, validZone, zoneSourceWords, type Destination, type EventRow, type Recipient,
} from './notificationWords'

const row: EventRow = { id: 'APPROVAL_WAITING', name: 'A request is waiting', critical: false, continuous: false, enabled: true, frequency: 'EVERY_HOUR', dailyAt: null, email: true, channels: [], withIdentifier: false }
const dest = (id: string, channel: Destination['channel']): Destination => ({ id, channel, secretPath: 'notify/' + id, state: 'Working', code: null, reason: null, lastAttempt: null })
const person = (recipientId: string, role: string): Recipient => ({ recipientId, role, userId: 'u-' + recipientId, email: recipientId + '@example.com', addedBy: 'admin', addedAt: '2026-11-01T10:00:00Z' })

describe('how often, in words (task 0081)', () => {
  it('names each frequency, with the time for once a day', () => {
    expect(frequencyWords('IMMEDIATELY', null)).toBe('Immediately')
    expect(frequencyWords('EVERY_HOUR', null)).toBe('Every hour')
    expect(frequencyWords('EVERY_4_HOURS', null)).toBe('Every 4 hours')
    expect(frequencyWords('ONCE_A_DAY', '08:30')).toBe('Once a day at 08:30')
  })
  it('a critical event goes at once or every hour only', () => {
    expect(allowedFrequencies(true)).toEqual(['IMMEDIATELY', 'EVERY_HOUR'])
    expect(allowedFrequencies(false)).toEqual(['IMMEDIATELY', 'EVERY_HOUR', 'EVERY_4_HOURS', 'ONCE_A_DAY'])
  })
})

describe('a row’s rules', () => {
  it('refuses a critical row switched off or sent rarely, and a bad time', () => {
    expect(draftProblem(row)).toBeNull()
    expect(draftProblem({ ...row, critical: true, enabled: false })).toBe('This one is always on.')
    expect(draftProblem({ ...row, critical: true, frequency: 'ONCE_A_DAY', dailyAt: '08:00' })).toBe('Choose Immediately or Every hour.')
    expect(draftProblem({ ...row, frequency: 'ONCE_A_DAY', dailyAt: '8:00' })).toContain('HH:mm')
    expect(draftProblem({ ...row, frequency: 'ONCE_A_DAY', dailyAt: '23:59' })).toBeNull()
  })
  it('sends only what changed, never On/Off for a critical row', () => {
    expect(hasChange(changeOf(row, row))).toBe(false)
    expect(changeOf(row, { ...row, enabled: false })).toEqual({ enabled: false })
    expect(changeOf({ ...row, critical: true }, { ...row, critical: true, enabled: false })).toEqual({})
    expect(changeOf(row, { ...row, frequency: 'ONCE_A_DAY', dailyAt: '07:00' })).toEqual({ frequency: 'ONCE_A_DAY', dailyAt: '07:00' })
    expect(changeOf(row, { ...row, email: false, channels: ['teams-ops'] })).toEqual({ email: false, channels: ['teams-ops'] })
    expect(changeOf({ ...row, channels: ['a', 'b'] }, { ...row, channels: ['b', 'a'] })).toEqual({})
  })
  it('checks the time of day', () => {
    expect(validDailyAt('00:00')).toBe(true)
    expect(validDailyAt('24:00')).toBe(false)
    expect(validDailyAt(null)).toBe(false)
  })
})

describe('channels', () => {
  it('in words, and only Teams and Slack as chat destinations', () => {
    expect(channelWords('EMAIL')).toBe('E-mail')
    expect(channelWords('TEAMS')).toBe('Teams')
    expect(channelWords('SLACK')).toBe('Slack')
    expect(chatDestinations([dest('mail', 'EMAIL'), dest('t', 'TEAMS'), dest('s', 'SLACK')]).map((d) => d.id)).toEqual(['t', 's'])
  })
  it('an unknown state is a warning, never working', () => {
    expect(stateTone('Working')).toBe('ok')
    expect(stateTone('Having problems')).toBe('warn')
    expect(stateTone('Down')).toBe('bad')
    expect(stateTone('Not used yet')).toBe('')
    expect(stateTone('Something new')).toBe('warn')
  })
  it('the app address is an http(s) address without a fragment', () => {
    expect(validAppAddress('https://vero.example.com')).toBe(true)
    expect(validAppAddress('http://localhost:5183/')).toBe(true)
    expect(validAppAddress('https://vero.example.com/#/home')).toBe(false)
    expect(validAppAddress('ftp://vero.example.com')).toBe(false)
    expect(validAppAddress('vero.example.com')).toBe(false)
  })
})

describe('time zones', () => {
  it('a report says where its zone comes from', () => {
    expect(zoneSourceWords('ENTITY')).toBe('this entity’s own time zone')
    expect(zoneSourceWords('CUSTOMER')).toBe('the customer’s time zone')
    expect(zoneSourceWords('NONE')).toBe('no time zone is set, so UTC')
  })
  it('the entity has its own, or follows the customer’s', () => {
    expect(entityZoneWords('Europe/London', 'America/New_York')).toBe('Europe/London')
    expect(entityZoneWords(null, 'America/New_York')).toBe('Follows the customer’s: America/New_York')
    expect(entityZoneWords(null, null)).toBe('Follows the customer’s: UTC')
  })
  it('knows an IANA name', () => {
    expect(validZone('America/New_York')).toBe(true)
    expect(validZone('Mars/Olympus_Mons')).toBe(false)
    expect(validZone('')).toBe(false)
  })
})

describe('who gets e-mail', () => {
  it('groups the lists by role, in order, with empty lists shown', () => {
    const groups = byRole([person('1', 'BUSINESS_ADMIN'), person('2', 'BUSINESS_USER'), person('3', 'BUSINESS_USER')])
    expect(groups.map((g) => g.role)).toEqual(['BUSINESS_USER', 'DOCUMENT_STEWARD', 'RESTRICTED_STEWARD', 'BUSINESS_ADMIN'])
    expect(groups[0].people.map((p) => p.recipientId)).toEqual(['2', '3'])
    expect(groups[1].people).toEqual([])
    expect(roleListWords('DOCUMENT_STEWARD')).toBe('Document stewards')
  })
  it('checks the e-mail address', () => {
    expect(validEmail('kim@example.com')).toBe(true)
    expect(validEmail('kim@example')).toBe(false)
    expect(validEmail('kim example.com')).toBe(false)
  })
})
