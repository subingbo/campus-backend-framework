import test from 'node:test'
import assert from 'node:assert/strict'
import {
  beginSession,
  clearSession,
  createRefreshCoordinator,
  getAccessToken,
  getAuthEpoch,
  isAuthEndpoint
} from '../src/api/authSession.js'

function memoryStorage(initial = {}) {
  const map = new Map(Object.entries(initial))
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => map.set(key, String(value)),
    removeItem: (key) => map.delete(key),
    dump: () => Object.fromEntries(map)
  }
}

test('并发 401 只触发一次刷新，并写入新 token', async () => {
  let calls = 0
  const storage = memoryStorage({ refreshToken: 'r1' })
  const coordinator = createRefreshCoordinator({
    storage,
    refresh: async () => {
      calls += 1
      await new Promise((resolve) => setTimeout(resolve, 10))
      return { accessToken: 'a2', refreshToken: 'r2' }
    }
  })

  await Promise.all([coordinator.refreshOnce(), coordinator.refreshOnce()])

  assert.equal(calls, 1)
  assert.equal(getAccessToken(storage), 'a2')
  assert.equal(storage.getItem('refreshToken'), 'r2')
})

test('刷新期间退出登录：旧结果被丢弃，不写回 token', async () => {
  let resolveRefresh
  const storage = memoryStorage({ refreshToken: 'r1' })
  const coordinator = createRefreshCoordinator({
    storage,
    refresh: () => new Promise((resolve) => {
      resolveRefresh = resolve
    })
  })

  const pending = coordinator.refreshOnce()
  // 等 refresh 真正开始（协调器内部有 microtask 延迟）后再模拟登出
  await new Promise((resolve) => setTimeout(resolve, 0))
  clearSession(storage)
  resolveRefresh({ accessToken: 'stale-a', refreshToken: 'stale-r' })

  await assert.rejects(pending, /会话已变化/)
  assert.equal(getAccessToken(storage), null)
  assert.equal(storage.getItem('refreshToken'), null)
})

test('登录开启新会话会递增 epoch，旧刷新结果因此失效', async () => {
  const storage = memoryStorage()
  const before = getAuthEpoch(storage)
  beginSession({ accessToken: 'a1', refreshToken: 'r1' }, storage)
  assert.equal(getAuthEpoch(storage), before + 1)
  beginSession({ accessToken: 'a2', refreshToken: 'r2' }, storage)
  assert.equal(getAuthEpoch(storage), before + 2)
})

test('登录/注册/刷新接口不进入 401 自动刷新', () => {
  assert.equal(isAuthEndpoint('/auth/login'), true)
  assert.equal(isAuthEndpoint('/auth/register'), true)
  assert.equal(isAuthEndpoint('/auth/refresh'), true)
  assert.equal(isAuthEndpoint('/activities'), false)
})
