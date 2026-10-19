import { useState } from 'react'
import { Download, Eraser, Lock, Plus } from 'lucide-react'
import { useStore } from './store'
import { AreaFrame } from './Governance'
import { Badge, Callout, Empty, ErrorState, Meter, Panel, Seg, Skeleton, Status, Switch } from './ui'

const SWATCHES = [
  { name: 'Vault green', token: '--primary', use: 'Primary actions, selection, healthy' },
  { name: 'Ink', token: '--ink', use: 'Text and structure' },
  { name: 'Paper', token: '--surface', use: 'Surfaces' },
  { name: 'Mist', token: '--bg', use: 'Canvas and quiet fills' },
  { name: 'Amber', token: '--attention', use: 'Attention and simulation' },
]

export function DesignSystem() {
  const { route } = useStore()
  return (
    <AreaFrame crumbs={['Prototype', 'Design system']} title="Vero R7 design system" lede="Custody ledger: calm, dense surfaces where identifiers, state and accountability are always legible.">
      {route.tab === 'foundations' && <Foundations />}
      {route.tab === 'components' && <Components />}
      {route.tab === 'states' && <States />}
    </AreaFrame>
  )
}

function Foundations() {
  return (
    <>
      <Panel title="Color" description="One primary, two neutrals, one attention accent. Red is reserved for destructive and failed states.">
        <div className="ds-swatches">
          {SWATCHES.map((s) => <div key={s.token} className="ds-swatch"><i style={{ background: `var(${s.token})` }} /><strong style={{ fontWeight: 500 }}>{s.name}</strong><span className="mono faint">{s.token}</span><span className="muted">{s.use}</span></div>)}
        </div>
      </Panel>
      <Panel title="Type" description="IBM Plex Sans for interface, IBM Plex Mono for identifiers, IDs and claims — the data users verify by eye.">
        <div className="ds-type">
          <div><span className="faint" style={{ fontSize: 12 }}>Page title · 22/600</span><span style={{ fontSize: 22, fontWeight: 600, letterSpacing: '-0.015em' }}>Find a record</span></div>
          <div><span className="faint" style={{ fontSize: 12 }}>Section · 16/600</span><span style={{ fontSize: 16, fontWeight: 600 }}>Storage volumes</span></div>
          <div><span className="faint" style={{ fontSize: 12 }}>Body · 15/400 · 1.5</span><span>Documents keep the retention they were ingested with.</span></div>
          <div><span className="faint" style={{ fontSize: 12 }}>Identifier · Plex Mono 15/500</span><span className="mono" style={{ fontSize: 15, fontWeight: 500 }}>100245 · 88123401 · doc-1a2b3c</span></div>
          <div><span className="faint" style={{ fontSize: 12 }}>Eyebrow · 12/500 caps</span><span className="eyebrow">Records workspace</span></div>
        </div>
      </Panel>
      <Panel title="Layout">
        <ul className="stack muted" style={{ margin: 0, paddingLeft: 18, fontSize: 14 }}>
          <li><strong>Phone (&lt;768):</strong> top bar + bottom navigation; inspectors and drawers become bottom sheets; tables become labelled cards.</li>
          <li><strong>iPad (768–1279):</strong> 72px icon rail; search and results side-by-side from 1024; record inspector and document info open as side sheets.</li>
          <li><strong>Desktop (≥1280):</strong> labelled rail; three-pane workspace; viewer shows documents, canvas and information together.</li>
          <li>All touch targets are at least 44px on coarse pointers; inputs are 16px on phones to avoid zoom.</li>
        </ul>
      </Panel>
    </>
  )
}

function Components() {
  const [on, setOn] = useState(true)
  const [seg, setSeg] = useState('a')
  return (
    <>
      <Panel title="Buttons">
        <div className="row wrap">
          <button type="button" className="v-btn primary"><Plus size={16} aria-hidden />Primary</button>
          <button type="button" className="v-btn">Secondary</button>
          <button type="button" className="v-btn quiet">Quiet</button>
          <button type="button" className="v-btn danger"><Eraser size={16} aria-hidden />Destructive</button>
          <button type="button" className="v-btn" disabled>Disabled</button>
          <button type="button" className="v-btn quiet icon" aria-disabled="true" aria-label="Download not permitted" style={{ position: 'relative' }}><Download size={18} /><Lock size={10} aria-hidden style={{ position: 'absolute', right: 6, bottom: 6 }} /></button>
        </div>
      </Panel>
      <Panel title="Status and badges">
        <div className="row wrap" style={{ gap: 14 }}>
          <Status value="UP" label="Healthy" /><Status value="DEGRADED" /><Status value="DOWN" />
          <Badge tone="ok">Identifier</Badge><Badge tone="warn">Incomplete</Badge><Badge tone="bad">Irreversible</Badge><Badge tone="outline">read-only</Badge><Badge>3 tags</Badge>
        </div>
      </Panel>
      <Panel title="Inputs">
        <div className="stack" style={{ maxWidth: 420 }}>
          <label className="v-field"><span>Contract number<span className="v-hint">Exact</span></span><input className="v-input" defaultValue="100245" /></label>
          <label className="v-field"><span>Invalid</span><input className="v-input invalid" defaultValue="abc" /><small style={{ color: 'var(--danger)' }}>Enter digits only.</small></label>
          <div className="row between"><span className="v-label">Switch</span><Switch label="Example" checked={on} onChange={setOn} /></div>
          <Seg label="Segmented" value={seg} onChange={setSeg} options={[{ value: 'a', label: 'Populated' }, { value: 'b', label: 'Loading' }, { value: 'c', label: 'Empty' }]} />
          <div className="stack" style={{ gap: 6 }}><span className="v-label">Capacity meter with thresholds</span><Meter pct={86} warn={80} stop={95} label="Example" /></div>
        </div>
      </Panel>
      <Panel title="Callouts">
        <div className="stack">
          <Callout title="Information">Neutral context.</Callout>
          <Callout tone="ok" title="Success">Operation completed.</Callout>
          <Callout tone="warn" title="Warning">Needs attention soon.</Callout>
          <Callout tone="bad" title="Destructive">Permanent and irreversible.</Callout>
          <Callout tone="sim" title="Simulated">Prototype-only behavior; nothing reaches a backend.</Callout>
        </div>
      </Panel>
    </>
  )
}

function States() {
  return (
    <>
      <Callout title="Every data panel supports these states">Switch the data scenario from Session to see them in place on any screen.</Callout>
      <div className="v-grid-2">
        <Panel title="Loading"><Skeleton rows={5} /></Panel>
        <Panel title="Empty"><Empty title="No matching records">Exact fields must match in full. Try fewer criteria.</Empty></Panel>
        <Panel title="Error"><ErrorState what="search results" /></Panel>
        <Panel title="Permission boundary"><Callout title="Commenting isn't assigned to your role">Controls stay visible but locked so people understand what exists and why it&apos;s unavailable.</Callout></Panel>
      </div>
    </>
  )
}
