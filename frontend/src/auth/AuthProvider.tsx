import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  UNAUTHORIZED_EVENT,
  signIn as apiSignIn,
  signOut as apiSignOut,
} from '../api/client'
import { AuthContext, type AuthContextValue } from './AuthContext'

/**
 * Holds the signed-in operator in React state and the Basic header in the API
 * module. Any 401 from an authenticated request clears cached data and returns
 * to the sign-in screen.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [username, setUsername] = useState<string | null>(null)
  const queryClient = useQueryClient()

  const signIn = useCallback(async (user: string, password: string) => {
    await apiSignIn(user, password)
    setUsername(user)
  }, [])

  const signOut = useCallback(() => {
    apiSignOut()
    setUsername(null)
    queryClient.clear()
  }, [queryClient])

  useEffect(() => {
    const handleUnauthorized = () => signOut()
    window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
  }, [signOut])

  const value = useMemo<AuthContextValue>(
    () => ({ username, signIn, signOut }),
    [username, signIn, signOut],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
