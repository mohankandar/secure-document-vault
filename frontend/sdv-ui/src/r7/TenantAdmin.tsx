import { useEffect, useState } from 'react'
import { Integrations } from './Integrations'
import { Storage } from './Storage'
import { Problems } from './Problems'
import { SignIn } from './SignIn'
import { About } from './About'
import { liveStorage } from './liveApi'
import { AREA_TABS, useStore } from './store'
import { AreaFrame } from './Governance'
import { volumeNeedsAttention } from './opsWords'

const LEDE: Record<string, string> = {
  storage: 'Where Vero keeps documents, and how full each place is.',
  authentication: 'The sign-in your people use. It is set during installation.',
  integrations: 'The systems that send documents to Vero, what they send, and how they prove who they are.',
  problems: 'Documents Vero could not store, documents that are stuck, and deliveries with documents missing. Read only.',
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
    <AreaFrame crumbs={['Tenant IT', tab.label]} title={tab.label} lede={LEDE[route.tab]} attention={storageAttention ? ['storage'] : []}>
      {route.tab === 'storage' && <Storage />}
      {route.tab === 'authentication' && <SignIn />}
      {route.tab === 'integrations' && <Integrations />}
      {route.tab === 'problems' && <Problems role="tenantIt" />}
      {route.tab === 'about' && <About role="tenantIt" />}
    </AreaFrame>
  )
}
