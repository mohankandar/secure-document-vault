import { describe, expect, it } from 'vitest'
import { CHECK_STATE, checkName } from './checkWords'

describe('the checks in words', () => {
  it('names Vero\'s checks, and others readably', () => {
    expect(checkName('sign-in-keys')).toBe('Sign-in keys')
    expect(checkName('folder-export')).toBe('Folder export')
  })
  it('has a word for every state', () => {
    expect(Object.keys(CHECK_STATE)).toEqual(['WORKING', 'HAVING_PROBLEMS', 'DOWN'])
  })
})
