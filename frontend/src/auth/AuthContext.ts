import { createContext } from 'react'

export interface AuthContextValue {
  username: string | null
  signIn: (username: string, password: string) => Promise<void>
  signOut: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)
