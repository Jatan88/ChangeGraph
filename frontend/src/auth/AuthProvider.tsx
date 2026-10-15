import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, ApiError } from '../api/client'
import type { CurrentUser } from '../api/types'

interface AuthState {
  user: CurrentUser | null
  ready: boolean
  refresh: () => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [ready, setReady] = useState(false)

  async function refresh() {
    try {
      setUser(await api<CurrentUser>('/api/auth/me'))
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setUser(null)
      } else if (error instanceof ApiError) {
        setUser(null)
      } else {
        setUser(null)
      }
    }
  }

  useEffect(() => {
    let cancelled = false
    refresh().finally(() => {
      if (!cancelled) {
        setReady(true)
      }
    })
    return () => {
      cancelled = true
    }
  }, [])

  const value = useMemo<AuthState>(
    () => ({
      user,
      ready,
      refresh: async () => {
        await refresh()
      },
      logout: async () => {
        await api<void>('/api/auth/logout', { method: 'POST' })
        setUser(null)
      },
    }),
    [user, ready],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth must be used within AuthProvider')
  }
  return value
}
