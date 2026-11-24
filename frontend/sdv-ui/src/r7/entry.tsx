import React from 'react'
import ReactDOM from 'react-dom/client'
import { R7App } from './Shell'
import { SignInGate } from './SignInGate'
import './r7.css'
import { PRODUCT_NAME } from './brand'

export function mount(root: HTMLElement) {
  document.documentElement.classList.add('r7-html')
  document.title = PRODUCT_NAME
  ReactDOM.createRoot(root).render(<React.StrictMode><SignInGate>{(epoch) => <R7App key={epoch} />}</SignInGate></React.StrictMode>)
}
