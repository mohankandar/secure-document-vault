import React from 'react'
import ReactDOM from 'react-dom/client'
import { R7App } from './Shell'
import './r7.css'

export function mount(root: HTMLElement) {
  document.documentElement.classList.add('r7-html')
  document.title = 'Vero R7 · Prototype'
  ReactDOM.createRoot(root).render(<React.StrictMode><R7App /></React.StrictMode>)
}
