/** 登录回跳只接受站内绝对路径，拒绝 //evil.com 与协议地址 */
export function safeRedirect(value) {
  if (typeof value !== 'string') return '/'
  if (!value.startsWith('/') || value.startsWith('//')) return '/'
  return value
}
