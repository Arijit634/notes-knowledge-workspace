import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import { App } from './app/App'
import { ApplicationErrorBoundary } from './app/errors/ApplicationErrorBoundary'
import './app/App.css'

const rootElement = document.getElementById('root')

if (rootElement === null) {
  throw new Error('Application root element is missing')
}

createRoot(rootElement).render(
  <StrictMode>
    <ApplicationErrorBoundary><App /></ApplicationErrorBoundary>
  </StrictMode>,
)
