import * as SecureStore from 'expo-secure-store'
import Constants from 'expo-constants'

const API_URL =
  Constants.expoConfig?.extra?.apiUrl ||
  process.env.EXPO_PUBLIC_API_URL ||
  'http://10.0.2.2:3001'

class ApiClient {
  private token: string | null = null

  async init() {
    this.token = await SecureStore.getItemAsync('auth_token')
  }

  async setToken(token: string) {
    this.token = token
    await SecureStore.setItemAsync('auth_token', token)
  }

  async clearToken() {
    this.token = null
    await SecureStore.deleteItemAsync('auth_token')
  }

  getToken() {
    return this.token
  }

  private async request<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<{ data?: T; error?: string }> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(options.headers as Record<string, string>),
    }

    if (this.token) {
      headers['Authorization'] = `Bearer ${this.token}`
    }

    try {
      const response = await fetch(`${API_URL}${endpoint}`, {
        ...options,
        headers,
      })

      const data = await response.json()

      if (!response.ok) {
        return { error: data.error || response.statusText }
      }

      return { data }
    } catch (err) {
      return { error: 'Network error' }
    }
  }

  async login(email: string, password: string) {
    return this.request<{
      user: { id: string; email: string; name: string }
      token: string
    }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
  }

  async getProfile() {
    return this.request<{
      user: { id: string; email: string; name: string }
    }>('/api/auth/profile')
  }
}

export const api = new ApiClient()
