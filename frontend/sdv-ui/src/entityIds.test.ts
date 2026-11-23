// Task 0041: the demo entity UUIDs in api.ts are platform-core's EntityUuid.fromSlotId of the demo slots: a SHA-256
// of "platform-entity|" + slot ID, cut to 16 bytes, with the version set to 5 and the RFC 4122 variant.
import { describe, expect, it } from 'vitest'
import { LOCAL_DEMO_ENTITY_ID, LOCAL_ISOLATION_ENTITY_ID } from './api'

async function fromSlotId(slot: string): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode('platform-entity|' + slot))
  const b = new Uint8Array(digest).slice(0, 16)
  b[6] = (b[6] & 0x0f) | 0x50
  b[8] = (b[8] & 0x3f) | 0x80
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

describe('demo entity IDs', () => {
  it('are derived from the demo slots', async () => {
    expect(LOCAL_DEMO_ENTITY_ID).toBe(await fromSlotId('ENT-VERO-DEMO-0001'))
    expect(LOCAL_ISOLATION_ENTITY_ID).toBe(await fromSlotId('ENT-VERO-DEMO-0002'))
  })
})
