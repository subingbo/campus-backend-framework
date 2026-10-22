const ACCESS_KEY = 'accessToken'
const REFRESH_KEY = 'refreshToken'
const EPOCH_KEY = 'authEpoch'

export function getAccessToken(storage = localStorage) {
  return storage.getItem(ACCESS_KEY)
}

export function getRefreshToken(storage = localStorage) {
  return storage.getItem(REFRESH_KEY)
}

export function getAuthEpoch(storage = localStorage) {
  return Number(storage.getItem(EPOCH_KEY) || 0)
}

function bumpEpoch(storage) {
  storage.setItem(EPOCH_KEY, String(getAuthEpoch(storage) + 1))
}

/** 登录成功：写入 token 并开启新会话 epoch */
export function beginSession(tokens, storage = localStorage) {
  storage.setItem(ACCESS_KEY, tokens.accessToken)
  storage.setItem(REFRESH_KEY, tokens.refreshToken)
  bumpEpoch(storage)
}

export function setTokens(tokens, storage = localStorage) {
  if (tokens?.accessToken) storage.setItem(ACCESS_KEY, tokens.accessToken)
  if (tokens?.refreshToken) storage.setItem(REFRESH_KEY, tokens.refreshToken)
}

/** 退出/换账号：递增 epoch，使进行中的刷新结果失效 */
export function clearSession(storage = localStorage) {
  storage.removeItem(ACCESS_KEY)
  storage.removeItem(REFRESH_KEY)
  bumpEpoch(storage)
}

export function isAuthEndpoint(url = '') {
  return url.includes('/auth/login') || url.includes('/auth/register') || url.includes('/auth/refresh')
}

/**
 * 单飞刷新协调器：并发 401 共用同一个 refresh Promise；
 * 刷新开始时记录 epoch，完成时若已退出/换账号则丢弃旧结果。
 */
export function createRefreshCoordinator({ storage = localStorage, refresh }) {
  let inflight = null
  return {
    refreshOnce() {
      if (inflight) return inflight
      const epoch = getAuthEpoch(storage)
      inflight = Promise.resolve()
        .then(() => refresh(getRefreshToken(storage)))
        .then((tokens) => {
          if (getAuthEpoch(storage) !== epoch) {
            const error = new Error('会话已变化')
            error.code = 'AUTH_SESSION_CHANGED'
            throw error
          }
          setTokens(tokens, storage)
          return tokens
        })
        .finally(() => {
          inflight = null
        })
      return inflight
    }
  }
}
