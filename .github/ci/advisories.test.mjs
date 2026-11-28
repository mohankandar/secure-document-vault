// Task 0102 (VSEC-206): the advisory check's rules. Run: node --test .github/ci/
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mavenLibraries, severityOf, suppressions, verdict } from './advisories.mjs'

test('only shipped Maven libraries are checked', () => {
  const list = [
    'The following files have been resolved:',
    '   org.springframework:spring-context:jar:7.1.0:compile\x1b[36m -- module spring.context\x1b[m',
    '[INFO]    org.postgresql:postgresql:jar:42.7.8:runtime',
    '   org.junit.jupiter:junit-jupiter:jar:6.0.1:test',
    '   jakarta.servlet:jakarta.servlet-api:jar:6.1.0:provided',
    '   org.example:with-classifier:jar:linux-x86_64:1.2.3:compile',
  ].join('\n')
  assert.deepEqual(mavenLibraries(list).map((l) => `${l.name}@${l.version}`),
    ['org.springframework:spring-context@7.1.0', 'org.postgresql:postgresql@42.7.8', 'org.example:with-classifier@1.2.3'])
})

test("GitHub's rating decides; none is High (fail closed)", () => {
  assert.equal(severityOf({ database_specific: { severity: 'CRITICAL' } }), 'CRITICAL')
  assert.equal(severityOf({ database_specific: { severity: 'MODERATE' } }), 'MEDIUM')
  assert.equal(severityOf({}), 'HIGH')
})

test('a suppression needs a reason and a date, and runs out', () => {
  const text = JSON.stringify({ suppressed: [
    { id: 'GHSA-aaaa', package: 'a:b', reason: 'not reachable', reviewedBy: 'design chat', until: '2027-01-31' },
    { id: 'GHSA-bbbb', package: 'a:b', reason: 'old', reviewedBy: 'design chat', until: '2026-01-01' },
    { id: 'GHSA-cccc', package: 'a:b' },
  ] })
  const { accepted, problems } = suppressions(text, '2026-11-27')
  assert.deepEqual([...accepted.keys()], ['GHSA-aaaa|a:b'])
  assert.equal(problems.length, 2)
})

test('High and Critical block unless accepted for that package; lower ones never block', () => {
  const { accepted } = suppressions(JSON.stringify({ suppressed: [
    { id: 'CVE-1', package: 'a:b', reason: 'r', reviewedBy: 'x', until: '2099-01-01' }] }), '2026-11-27')
  const result = verdict([
    { id: 'GHSA-1', ids: ['GHSA-1', 'CVE-1'], package: 'a:b', version: '1', severity: 'HIGH' },
    { id: 'GHSA-1', ids: ['GHSA-1', 'CVE-1'], package: 'c:d', version: '1', severity: 'HIGH' },
    { id: 'GHSA-2', ids: ['GHSA-2'], package: 'a:b', version: '1', severity: 'CRITICAL' },
    { id: 'GHSA-3', ids: ['GHSA-3'], package: 'a:b', version: '1', severity: 'MEDIUM' },
  ], accepted)
  assert.deepEqual(result.allowed.map((a) => a.package), ['a:b'])
  assert.deepEqual(result.blocking.map((a) => `${a.id} ${a.package}`), ['GHSA-1 c:d', 'GHSA-2 a:b'])
  assert.equal(result.lower.length, 1)
})
