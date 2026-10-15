import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './tokens.css'
import './styles.css'
import './ui-foundation.css'
import './business-workspace.css'
import './admin-framework.css'
import './design-system.css'
import R6BPrototypes from './prototypes/R6BPrototypes'

// R6-B concepts are review-only; they replace nothing in the live workflow.
const showR6BPrototypes = new URLSearchParams(window.location.search).get('prototype') === 'r6b'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {showR6BPrototypes ? <R6BPrototypes /> : <App />}
  </React.StrictMode>,
)
