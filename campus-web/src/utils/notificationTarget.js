/**
 * 通知/会话点击后的跳转目标（纯函数，便于 node:test）。
 * 只使用通知里的最小路由字段；真正的数据权限由目标页面接口再校验。
 */

function positiveInt(value) {
  const n = Number(value)
  return Number.isInteger(n) && n > 0 ? n : null
}

export function conversationRoute(conversation) {
  if (!conversation) return { path: '/messages' }
  if (conversation.type === 'PRIVATE') {
    const peer = positiveInt(conversation.peerUserId)
    return peer ? { path: '/friends', query: { chat: String(peer) } } : { path: '/messages' }
  }
  const mapId = positiveInt(conversation.mapId)
  if (!mapId) return { path: '/messages' }
  if (conversation.type === 'GROUP') {
    return { path: `/maps/${mapId}`, query: { chat: '1' } }
  }
  const placeId = positiveInt(conversation.placeId)
  if (conversation.type === 'PLACE' && placeId) {
    return { path: `/maps/${mapId}`, query: { place: String(placeId) } }
  }
  return { path: `/maps/${mapId}` }
}

export function notificationRoute(notification) {
  const targetType = notification?.targetType
  const targetId = positiveInt(notification?.targetId)
  const payload = notification?.payload || {}

  if (targetType === 'ACTIVITY') {
    return targetId
      ? { path: '/activities', query: { activityId: String(targetId) } }
      : { path: '/activities' }
  }
  if (targetType === 'ACTIVITY_INVITE') {
    return { path: '/activities', query: { tab: 'invites' } }
  }
  if (targetType === 'FRIEND_REQUEST') {
    return { path: '/friends' }
  }
  if (targetType === 'FRIEND') {
    const peer = positiveInt(payload.userId) || targetId
    return peer
      ? { path: '/friends', query: { chat: String(peer) } }
      : { path: '/friends' }
  }
  if (targetType === 'CONVERSATION') {
    return conversationRoute({
      type: payload.type,
      mapId: payload.mapId,
      placeId: payload.placeId,
      peerUserId: payload.peerUserId
    })
  }
  return { path: '/messages' }
}
