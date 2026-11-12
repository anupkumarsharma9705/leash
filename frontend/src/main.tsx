import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from './api/client'
import { AuthProvider } from './auth/AuthProvider'
import { initTheme } from './lib/themeInit'
import App from './App'
import './lib/agGrid'
import './index.css'

initTheme()

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 2_000,
      retry: (failureCount, error) => {
        if (error instanceof ApiError) {
          if (error.status === 0 || error.status >= 500) {
            return failureCount < 2
          }
          return false
        }
        return failureCount < 1
      },
      retryDelay: (attempt) => Math.min(1_000 * 2 ** attempt, 5_000),
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
)
