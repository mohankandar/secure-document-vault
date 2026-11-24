// Task 0074: the checks' names in words. A check Vero doesn't name here keeps the server's name.
const NAMES: Record<string, string> = {
  database: 'Database', 'vero-schema': 'Database version', busy: 'Free database connections', storage: 'Storage', 'intake-attempts': 'Unidentified intake attempts',
  'key-service': 'Key service', 'sign-in-keys': 'Sign-in keys', 'login-system': 'Login system', licence: 'Licence',
}
export const checkName = (name: string) => NAMES[name] ?? name.replace(/-/g, ' ').replace(/^./, (c) => c.toUpperCase())
export const CHECK_STATE: Record<string, { word: string; dot: string }> = {
  WORKING: { word: 'Working', dot: 'g' }, HAVING_PROBLEMS: { word: 'Having problems', dot: 'y' }, DOWN: { word: 'Not working', dot: 'r' },
}
