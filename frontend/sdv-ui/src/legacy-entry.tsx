import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './tokens.css'
import './styles.css'
import './ui-foundation.css'
import './business-workspace.css'
import './admin-framework.css'

export function mount(root: HTMLElement) {
  ReactDOM.createRoot(root).render(<React.StrictMode><App /></React.StrictMode>)
}
