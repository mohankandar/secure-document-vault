import React from 'react'
import ReactDOM from 'react-dom/client'
import { R7App } from './Shell'
import { SignInGate } from './SignInGate'
import './r7.css'
import { PRODUCT_NAME } from './brand'
import { adoptPathRoute } from './store'
import { adoptLinkPath } from './approvalLinkWords'

export function mount(root: HTMLElement) {
  document.documentElement.classList.add('r7-html')
  document.title = PRODUCT_NAME
  // Task 0081: a link's path (/governance/approvals) becomes the route before sign-in, so the sign-in comes back to it.
  // Task 0082: an approval link's token waits in this tab's session storage; the address becomes Approvals without it.
  adoptLinkPath()
  adoptPathRoute()
  ReactDOM.createRoot(root).render(<React.StrictMode><SignInGate>{(epoch) => <R7App key={epoch} />}</SignInGate></React.StrictMode>)
}
