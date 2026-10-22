import test from 'node:test'
import assert from 'node:assert/strict'
import { conversationRoute, notificationRoute } from '../src/utils/notificationTarget.js'

test('活动通知跳到日程并带 activityId', () => {
  assert.deepEqual(
    notificationRoute({ targetType: 'ACTIVITY', targetId: 12 }),
    { path: '/activities', query: { activityId: '12' } }
  )
})

test('活动邀请通知跳到邀请 tab', () => {
  assert.deepEqual(
    notificationRoute({ targetType: 'ACTIVITY_INVITE', targetId: 3 }),
    { path: '/activities', query: { tab: 'invites' } }
  )
})

test('好友申请通知跳好友页', () => {
  assert.deepEqual(
    notificationRoute({ targetType: 'FRIEND_REQUEST', targetId: 5 }),
    { path: '/friends' }
  )
})

test('好友通过通知按 payload.userId 打开私聊', () => {
  assert.deepEqual(
    notificationRoute({ targetType: 'FRIEND', targetId: 9, payload: { userId: 7 } }),
    { path: '/friends', query: { chat: '7' } }
  )
})

test('好友通过通知缺少 payload 时回退 targetId', () => {
  assert.deepEqual(
    notificationRoute({ targetType: 'FRIEND', targetId: 9 }),
    { path: '/friends', query: { chat: '9' } }
  )
})

test('未知通知回消息中心', () => {
  assert.deepEqual(notificationRoute({ targetType: 'NONE' }), { path: '/messages' })
  assert.deepEqual(notificationRoute(null), { path: '/messages' })
})

test('群聊会话跳地图并自动打开群聊', () => {
  assert.deepEqual(
    conversationRoute({ type: 'GROUP', mapId: 4 }),
    { path: '/maps/4', query: { chat: '1' } }
  )
})

test('私聊会话按 peerUserId 打开好友聊天', () => {
  assert.deepEqual(
    conversationRoute({ type: 'PRIVATE', peerUserId: 6 }),
    { path: '/friends', query: { chat: '6' } }
  )
})

test('地点聊天室会话说跳地图地点面板', () => {
  assert.deepEqual(
    conversationRoute({ type: 'PLACE', mapId: 4, placeId: 8 }),
    { path: '/maps/4', query: { place: '8' } }
  )
})

test('无效会话不拼出坏路径', () => {
  assert.deepEqual(conversationRoute({ type: 'GROUP' }), { path: '/messages' })
  assert.deepEqual(conversationRoute({ type: 'PRIVATE', peerUserId: 'x' }), { path: '/messages' })
  assert.deepEqual(conversationRoute(null), { path: '/messages' })
})
