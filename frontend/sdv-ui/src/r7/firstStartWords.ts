// Task 0098: the first-start screen. While no entity is active, an IT Admin of a slot activates the first one with its name
// and the install's licence file (the same file setup stored). After that the screen is gone for good.

export type FirstStart = { slots: string[] }
export type FirstActivated = { entityId: string; slotId: string; name: string }

export const NAME_MAX = 120
export const LICENCE_MAX = 200_000

/** Why the form can't be sent yet, or null. */
export function firstStartProblem(slot: string, name: string, licence: string | null): string | null {
  if (!slot) return 'Choose the entity to activate.'
  const n = name.trim()
  if (!n) return 'Give the entity its name, as people will see it.'
  if (n.length > NAME_MAX) return `The name is at most ${NAME_MAX} characters.`
  if (!licence || !licence.trim()) return 'Choose the licence file (the same file setup stored).'
  if (licence.length > LICENCE_MAX) return 'This file is too large to be a licence file.'
  return null
}

/** The words for a refusal, by its code. */
export function firstStartRefusal(code: string | undefined): string {
  switch (code) {
    case 'SDV-ENT-001': return 'This entity can’t be activated with this licence file. Choose this install’s licence file, as setup stored it, and check the name.'
    case 'SDV-ENT-002': return 'Only an IT Admin of this entity can activate it. Ask for the IT Admin group in your sign-in system.'
    default: return 'The entity wasn’t activated. Try again; if it repeats, give IT the reference.'
  }
}
