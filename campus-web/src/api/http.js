import axios from 'axios'
import {
  clearSession,
  createRefreshCoordinator,
  getAccessToken,
  getRefreshToken,
  isAuthEndpoint
} from './authSession'
import { clearOutboxForCurrentUser } from '../composables/useChatSocket'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

/** 刷新请求走独立实例，避免再次进入 401 拦截器造成递归 */
const refreshClient = axios.create({
  baseURL: '/api',
  timeout: 15000
})

function unwrap(res) {
  const body = res.data
  if (body && body.code !== 200) {
    throw new Error(body.message || '请求失败')
  }
  return body.data
}

const refreshCoordinator = createRefreshCoordinator({
  refresh: async (refreshToken) => {
    if (!refreshToken) throw new Error('缺少 refreshToken')
    const res = await refreshClient.post('/auth/refresh', { refreshToken })
    const body = res.data
    if (!body || body.code !== 200 || !body.data?.accessToken) {
      throw new Error(body?.message || '登录已过期，请重新登录')
    }
    return body.data
  }
})

function setAuthHeader(config, token) {
  if (!config.headers) config.headers = {}
  if (typeof config.headers.set === 'function') {
    config.headers.set('Authorization', `Bearer ${token}`)
  } else {
    config.headers.Authorization = `Bearer ${token}`
  }
}

function headerToken(config) {
  const raw = config.headers?.Authorization
    || (typeof config.headers?.get === 'function' ? config.headers.get('Authorization') : '')
    || ''
  return String(raw).replace(/^Bearer\s+/i, '')
}

function forceLogin() {
  clearOutboxForCurrentUser()
  clearSession()
  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
  }
}

http.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) setAuthHeader(config, token)
  return config
})

http.interceptors.response.use(
  unwrap,
  async (err) => {
    const response = err.response
    const config = err.config
    const msg = response?.data?.message || err.message
    if (response?.status !== 401 || !config) {
      return Promise.reject(new Error(msg))
    }
    // 登录/注册/刷新自身的 401 交给页面显示凭证错误，不触发刷新
    if (isAuthEndpoint(config.url)) {
      return Promise.reject(new Error(msg))
    }
    const current = getAccessToken()
    if (!current) {
      forceLogin()
      return Promise.reject(new Error(msg))
    }
    const sent = headerToken(config)
    // 迟到的旧 token 401：当前 token 已被其他请求刷新，直接用新 token 重放
    if (sent && sent !== current && !config._retry) {
      config._retry = true
      setAuthHeader(config, current)
      return http(config)
    }
    if (config._retry) {
      forceLogin()
      return Promise.reject(new Error(msg))
    }
    try {
      await refreshCoordinator.refreshOnce()
      config._retry = true
      setAuthHeader(config, getAccessToken())
      return http(config)
    } catch {
      forceLogin()
      return Promise.reject(new Error(msg))
    }
  }
)

export { forceLogin, refreshCoordinator }
export default http
