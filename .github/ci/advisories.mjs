// Task 0102 (VSEC-206): fails CI on a published advisory of High or Critical severity for a library Vero ships.
//
//   node .github/ci/advisories.mjs maven <file of "mvn dependency:list" output> [more files]
//   node .github/ci/advisories.mjs npm <output of "npm audit --omit=dev --json">
//
// Maven: every runtime and compile library is asked at OSV (api.osv.dev, the public advisory database that also holds
// GitHub's advisories); an advisory's severity is GitHub's rating, or High when it has none (fail closed). npm: npm's
// own audit. An advisory listed in advisories-suppressed.json with a reason and a date not yet past is accepted.
// Only library names, versions and advisory ids are sent or printed; nothing of Vero's data.
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const BLOCKING = new Set(['HIGH', 'CRITICAL'])
const here = dirname(fileURLToPath(import.meta.url))

export function suppressions(text, today) {
  const list = JSON.parse(text).suppressed ?? []
  const accepted = new Map()
  const problems = []
  for (const s of list) {
    if (!s.id || !s.package || !s.reason || !s.reviewedBy || !s.until) { problems.push(`suppression ${s.id ?? '?'} lacks id, package, reason, reviewedBy or until`); continue }
    if (s.until < today) { problems.push(`suppression ${s.id} (${s.package}) ran out on ${s.until}: look at it again`); continue }
    accepted.set(`${s.id}|${s.package}`, s)
  }
  return { accepted, problems }
}

/** groupId:artifactId:type[:classifier]:version:scope lines of "mvn dependency:list"; test and provided scopes are not shipped. */
export function mavenLibraries(text) {
  const out = new Map()
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.replace(/\x1b\[[0-9;]*m/g, ' ').replace(/^\[INFO\]\s*/, '').trim().split(/\s/)[0]
    const parts = line.split(':')
    if (parts.length < 5) continue
    const scope = parts[parts.length - 1]
    if (!['compile', 'runtime'].includes(scope)) continue
    const version = parts[parts.length - 2]
    const name = `${parts[0]}:${parts[1]}`
    if (!/^[\w.-]+:[\w.-]+$/.test(name) || !/^[\w.+-]+$/.test(version)) continue
    out.set(`${name}@${version}`, { name, version })
  }
  return [...out.values()]
}

/** GitHub's rating when there is one, else High (fail closed). */
export function severityOf(vuln) {
  const rated = vuln?.database_specific?.severity
  if (typeof rated === 'string' && rated.trim()) return rated.trim().toUpperCase() === 'MODERATE' ? 'MEDIUM' : rated.trim().toUpperCase()
  return 'HIGH'
}

async function postJson(url, body) {
  const r = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
  if (!r.ok) throw new Error(`${url} answered ${r.status}`)
  return r.json()
}

async function maven(files) {
  const libraries = files.flatMap((f) => mavenLibraries(readFileSync(f, 'utf8')))
  const unique = [...new Map(libraries.map((l) => [`${l.name}@${l.version}`, l])).values()]
  if (unique.length === 0) throw new Error('no libraries found in the dependency lists')
  const found = []
  for (let i = 0; i < unique.length; i += 500) {
    const batch = unique.slice(i, i + 500)
    const answer = await postJson('https://api.osv.dev/v1/querybatch', {
      queries: batch.map((l) => ({ package: { ecosystem: 'Maven', name: l.name }, version: l.version })),
    })
    answer.results.forEach((res, k) => (res.vulns ?? []).forEach((v) => found.push({ id: v.id, library: batch[k] })))
  }
  const advisories = []
  for (const f of found) {
    const r = await fetch(`https://api.osv.dev/v1/vulns/${encodeURIComponent(f.id)}`)
    if (!r.ok) throw new Error(`advisory ${f.id} could not be read (${r.status})`)
    const v = await r.json()
    const ids = [v.id, ...(v.aliases ?? [])]
    advisories.push({ id: v.id, ids, package: f.library.name, version: f.library.version, severity: severityOf(v) })
  }
  return { checked: unique.length, advisories }
}

function npm(file) {
  const report = JSON.parse(readFileSync(file, 'utf8'))
  // An audit that couldn't ask the registry has no report: that is a failure, never "nothing found".
  if (report.error || report.auditReportVersion !== 2) throw new Error(`npm audit gave no report (${report.error?.code ?? 'unknown answer'})`)
  const advisories = []
  for (const [name, entry] of Object.entries(report.vulnerabilities ?? {})) {
    for (const via of entry.via ?? []) {
      if (typeof via !== 'object') continue
      const id = (via.url ?? '').split('/').pop() || String(via.source)
      advisories.push({ id, ids: [id], package: name, version: entry.range ?? '', severity: String(via.severity ?? 'high').toUpperCase() })
    }
  }
  return { checked: report.metadata?.dependencies?.prod ?? Object.keys(report.vulnerabilities ?? {}).length, advisories }
}

export function verdict(advisories, accepted) {
  const blocking = [], allowed = [], lower = []
  for (const a of advisories) {
    if (!BLOCKING.has(a.severity)) { lower.push(a); continue }
    const s = a.ids.map((id) => accepted.get(`${id}|${a.package}`)).find(Boolean)
    ;(s ? allowed : blocking).push(a)
  }
  return { blocking, allowed, lower }
}

async function main() {
  const [mode, ...files] = process.argv.slice(2)
  if (!['maven', 'npm'].includes(mode) || files.length === 0) {
    console.error('usage: node advisories.mjs maven <dependency lists...> | npm <npm audit json>')
    process.exit(2)
  }
  const { accepted, problems } = suppressions(readFileSync(join(here, 'advisories-suppressed.json'), 'utf8'), new Date().toISOString().slice(0, 10))
  const { checked, advisories } = mode === 'maven' ? await maven(files) : npm(files[0])
  const { blocking, allowed, lower } = verdict(advisories, accepted)
  console.log(`${mode}: ${checked} libraries checked; ${advisories.length} advisories (${blocking.length} blocking, ${allowed.length} accepted, ${lower.length} below High)`)
  for (const a of lower) console.log(`  below High: ${a.id} ${a.severity} ${a.package} ${a.version}`)
  for (const a of allowed) console.log(`  accepted: ${a.id} ${a.severity} ${a.package} ${a.version}`)
  for (const a of blocking) console.log(`::error::${a.id} ${a.severity} in ${a.package} ${a.version}: update it, or add a reviewed suppression to .github/ci/advisories-suppressed.json`)
  for (const p of problems) console.log(`::error::${p}`)
  process.exit(blocking.length > 0 || problems.length > 0 ? 1 : 0)
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((e) => { console.error(`::error::the advisory check could not run: ${e.message}`); process.exit(1) })
}
