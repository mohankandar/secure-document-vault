import { describe, expect, it } from 'vitest'
import { LICENCE_MAX, NAME_MAX, firstStartProblem, firstStartRefusal } from './firstStartWords'

describe('first start (task 0098)', () => {
  it('asks for the entity, its name and the licence file', () => {
    expect(firstStartProblem('', 'Acme', '{}')).toMatch(/Choose the entity/)
    expect(firstStartProblem('ENT-1', '  ', '{}')).toMatch(/name/)
    expect(firstStartProblem('ENT-1', 'x'.repeat(NAME_MAX + 1), '{}')).toMatch(/at most/)
    expect(firstStartProblem('ENT-1', 'Acme', null)).toMatch(/licence file/)
    expect(firstStartProblem('ENT-1', 'Acme', 'x'.repeat(LICENCE_MAX + 1))).toMatch(/too large/)
    expect(firstStartProblem('ENT-1', 'Acme Mortgage', '{"licence":1}')).toBeNull()
  })
  it('says why it was refused, by code', () => {
    expect(firstStartRefusal('SDV-ENT-001')).toMatch(/licence file/)
    expect(firstStartRefusal('SDV-ENT-002')).toMatch(/IT Admin/)
    expect(firstStartRefusal(undefined)).toMatch(/reference/)
  })
})
