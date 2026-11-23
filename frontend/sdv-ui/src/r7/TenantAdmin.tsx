import { useEffect, useState } from 'react'
import { Integrations } from './Integrations'
import { Storage } from './Storage'
import { Problems } from './Problems'
import { SignIn } from './SignIn'
import { About } from './About'
import { AuditReports } from './AuditReports'
import { liveStorage } from './liveApi'
import { AREA_TABS, useStore } from './store'
import { AreaFrame } from './Governance'
import { volumeNeedsAttention } from './opsWords'

const LEDE: Record<string, string> = {
  storage: 'Where Vero keeps your encrypted documents.',
  authentication: 'The sign-in your people use. It is set during installation.',
  integrations: 'The systems that send documents to Vero, and what they send.',
  problems: 'Documents that could not be stored, and deliveries still missing documents. Read only.',
  'audit-report': 'A sealed copy of the audit for a period, to hand to an auditor. Nobody can change what Vero recorded.',
  about: 'The facts support will ask for.',
}

export function TenantAdmin() {
  const { route } = useStore()
  const tab = AREA_TABS.tenant.find((t) => t.id === route.tab)!
  // The Storage tab is marked only for unexpected states; a volume set offline or read only on purpose doesn't count.
  const [storageAttention, setStorageAttention] = useState(false)
  useEffect(() => {
    let live = true
    liveStorage().then((items) => { if (live) setStorageAttention(items.some(volumeNeedsAttention)) }).catch(() => { if (live) setStorageAttention(false) })
    return () => { live = false }
  }, [route.tab])
  return (
    <AreaFrame crumbs={['IT', tab.label]} title={route.tab === 'about' ? 'About this installation' : tab.label} lede={LEDE[route.tab]} attention={storageAttention ? ['storage'] : []}>
      {route.tab === 'storage' && <Storage />}
      {route.tab === 'authentication' && <SignIn />}
      {route.tab === 'integrations' && <Integrations />}
      {route.tab === 'problems' && <Problems role="tenantIt" />}
      {route.tab === 'audit-report' && <AuditReports />}
      {route.tab === 'about' && <About role="tenantIt" />}
    </AreaFrame>
  )
}
