import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.jsx'
import { SessionProvider } from './session/SessionContext.jsx'
import { RouterProvider } from './router/RouterContext.jsx'
import 'react-datepicker/dist/react-datepicker.css'
import './styles.css'

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <SessionProvider>
      <RouterProvider>
        <App />
      </RouterProvider>
    </SessionProvider>
  </React.StrictMode>
)
