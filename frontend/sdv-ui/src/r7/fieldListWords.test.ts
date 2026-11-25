import { expect, it } from 'vitest'
import { importWords } from './FieldList'

it('says what an import does in one sentence', () => {
  const line = (outcome: 'CREATE' | 'UNCHANGED' | 'CONFLICT') => ({ fieldKey: 'a', label: 'A', outcome, conflict: null, message: null })
  expect(importWords([line('CREATE'), line('CREATE'), line('CONFLICT')])).toBe('2 new, 0 already there, 1 in conflict.')
})
