import React from 'react'
import ReactDOM from 'react-dom/client'
import flagsmith from 'flagsmith'
import { FlagsmithProvider } from 'flagsmith/react'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'
import '@fontsource/jetbrains-mono/700.css'
import App from './App.jsx'
import './styles.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <FlagsmithProvider
      flagsmith={flagsmith}
      options={{
        environmentID: import.meta.env.VITE_FLAGSMITH_ENV_KEY,
        api: import.meta.env.VITE_FLAGSMITH_API,
        cacheFlags: false,
      }}
    >
      <App />
    </FlagsmithProvider>
  </React.StrictMode>,
)

// Poll every 2 seconds so a toggle in the WebStorm plugin shows up here live
flagsmith.startListening(2000)
