// Task 0065: the sign-in problems in words. The login system's own text is never shown.
const WORDS: Record<string, { title: string; text: string }> = {
  'SDV-AUT-014': { title: 'Sign-in is not set up', text: 'Vero has no company sign-in in use. Tell your IT team.' },
  'SDV-AUT-015': { title: 'Your company sign-in can’t be reached', text: 'Try again in a few minutes. If it stays, tell your IT team.' },
  'SDV-AUT-016': { title: 'This sign-in can’t be completed', text: 'It was started somewhere else, was used already or took too long. Start again.' },
  'SDV-AUT-017': { title: 'Your company sign-in refused', text: 'Start again. If it happens again, tell your IT team.' },
  NETWORK: { title: 'Vero can’t be reached', text: 'Check your connection and try again.' },
}

export const signInProblemWords = (code: string): { title: string; text: string } =>
  WORDS[code] ?? { title: 'Sign-in didn’t work', text: 'Try again. If it stays, tell your IT team.' }
