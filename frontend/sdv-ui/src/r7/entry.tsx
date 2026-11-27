import React from 'react'
import ReactDOM from 'react-dom/client'
import { R7App } from './Shell'
import { SignInGate } from './SignInGate'
import './r7.css'
import { PRODUCT_NAME } from './brand'
import { adoptPathRoute } from './store'

export function mount(root: HTMLElement) {
  document.documentElement.classList.add('r7-html')
  document.title = PRODUCT_NAME
  // Task 0081: a link's path (/governance/approvals) becomes the route before sign-in, so the sign-in comes back to it.
  adoptPathRoute()
  ReactDOM.createRoot(root).render(<React.StrictMode><SignInGate>{(epoch) => <R7App key={epoch} />}</SignInGate></React.StrictMode>)
}
