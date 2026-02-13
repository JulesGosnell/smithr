import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
} from 'react'
import { api } from './api'

type User = { id: string; email: string; name: string }

type AuthState = {
  user: User | null
  isLoading: boolean
  isAuthenticated: boolean
}

type AuthContextType = AuthState & {
  login: (
    email: string,
    password: string
  ) => Promise<{ success: boolean; error?: string }>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextType | null>(null)

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>({
    user: null,
    isLoading: true,
    isAuthenticated: false,
  })

  // Check for existing token on mount
  useEffect(() => {
    ;(async () => {
      await api.init()
      if (api.getToken()) {
        const { data } = await api.getProfile()
        if (data) {
          setState({
            user: data.user,
            isLoading: false,
            isAuthenticated: true,
          })
          return
        }
        await api.clearToken()
      }
      setState((s) => ({ ...s, isLoading: false }))
    })()
  }, [])

  const login = useCallback(
    async (email: string, password: string) => {
      setState((s) => ({ ...s, isLoading: true }))

      const { data, error } = await api.login(email, password)

      if (error || !data) {
        setState((s) => ({ ...s, isLoading: false }))
        return { success: false, error: error || 'Login failed' }
      }

      await api.setToken(data.token)
      setState({
        user: data.user,
        isLoading: false,
        isAuthenticated: true,
      })
      return { success: true }
    },
    []
  )

  const logout = useCallback(async () => {
    await api.clearToken()
    setState({ user: null, isLoading: false, isAuthenticated: false })
  }, [])

  return (
    <AuthContext.Provider value={{ ...state, login, logout }}>
      {children}
    </AuthContext.Provider>
  )
}
