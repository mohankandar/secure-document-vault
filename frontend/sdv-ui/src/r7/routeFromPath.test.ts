import { describe, expect, it } from 'vitest'
import { routeFromPath } from './store'

describe('a link’s path becomes the route (task 0081)', () => {
  it('takes a known area with its tab, or the area’s first tab', () => {
    expect(routeFromPath('/governance/approvals')).toEqual({ area: 'governance', tab: 'approvals' })
    expect(routeFromPath('/governance/notifications/')).toEqual({ area: 'governance', tab: 'notifications' })
    expect(routeFromPath('/tenant/notifications')).toEqual({ area: 'tenant', tab: 'notifications' })
    expect(routeFromPath('/governance')).toEqual({ area: 'governance', tab: 'access' })
    expect(routeFromPath('/quarantine/incidents')).toEqual({ area: 'quarantine', tab: 'incidents' })
  })
  it('a known area with a tab it doesn’t have opens the area, as an address does', () => {
    expect(routeFromPath('/governance/nothing')).toEqual({ area: 'governance', tab: 'access' })
  })
  it('anything else is no route', () => {
    expect(routeFromPath('/')).toBeNull()
    expect(routeFromPath('')).toBeNull()
    expect(routeFromPath('/index.html')).toBeNull()
    expect(routeFromPath('/api/v1/session')).toBeNull()
    expect(routeFromPath('/unknown/approvals')).toBeNull()
    expect(routeFromPath('/governance/approvals/extra')).toBeNull()
    expect(routeFromPath('/constructor')).toBeNull()
    expect(routeFromPath('/toString/x')).toBeNull()
    expect(routeFromPath('governance/approvals')).toBeNull()
  })
})
