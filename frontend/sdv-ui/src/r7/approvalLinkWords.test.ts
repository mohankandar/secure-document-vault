// Task 0082: approval links from e-mail.
import { describe, expect, it } from 'vitest'
import { linkTokenFromPath, linkWords } from './approvalLinkWords'

describe('approval links (task 0082)', () => {
  it('takes a token only from the link page path', () => {
    expect(linkTokenFromPath('/approvals/link/AQEBAAAAAAAA.c2lnbmF0dXJl')).toBe('AQEBAAAAAAAA.c2lnbmF0dXJl')
    expect(linkTokenFromPath('/approvals/link/AQEBAAAAAAAA.c2lnbmF0dXJl/')).toBe('AQEBAAAAAAAA.c2lnbmF0dXJl')
    expect(linkTokenFromPath('/approvals/link/')).toBeNull()
    expect(linkTokenFromPath('/approvals/link/no-dot-here-at-all')).toBeNull()
    expect(linkTokenFromPath('/approvals/link/AQEB<script>.x')).toBeNull()
    expect(linkTokenFromPath('/governance/approvals')).toBeNull()
  })
  it('says what the link does and what happened', () => {
    expect(linkWords({ state: 'READY', action: 'APPROVE', decidedAction: null, decidedByName: null }).title).toContain('approve')
    expect(linkWords({ state: 'READY', action: 'DECLINE', decidedAction: null, decidedByName: null }).title).toContain('decline')
    expect(linkWords({ state: 'DECIDED', action: 'DECLINE', decidedAction: 'DECLINED', decidedByName: 'Bea' }).title).toBe('Declined.')
    expect(linkWords({ state: 'ALREADY_DECIDED', action: null, decidedAction: 'APPROVE', decidedByName: 'Bea' }).title).toContain('Bea')
    expect(linkWords({ state: 'REFUSED', action: null, decidedAction: null, decidedByName: null }).tone).toBe('bad')
  })
})
