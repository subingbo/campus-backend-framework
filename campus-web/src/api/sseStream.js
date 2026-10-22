/**
 * 纯 SSE 流解析器（无浏览器依赖，便于 node:test 覆盖）。
 * 401 只在尚未消费任何事件时由调用方刷新并重试一次；已开始消费后不整体重发。
 */
export function createStreamAssistantChat({ fetchImpl, getToken, refresh, onAuthLost }) {
  return async function streamAssistantChat(chatId, content, onEvent, signal, payload = null) {
    const request = (token) => fetchImpl(`/api/assistant/chats/${chatId}/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token || ''}`
      },
      body: JSON.stringify(payload ? { content, ...payload } : { content }),
      signal
    })

    let resp
    try {
      resp = await request(getToken())
    } catch {
      onEvent('error', { message: signal?.aborted ? '已取消' : '网络错误，请确认后端已启动' })
      return
    }

    if (resp.status === 401) {
      try {
        await refresh()
      } catch {
        onAuthLost?.()
        onEvent('error', { message: '登录已过期，请重新登录' })
        return
      }
      try {
        resp = await request(getToken())
      } catch {
        onEvent('error', { message: signal?.aborted ? '已取消' : '网络错误，请确认后端已启动' })
        return
      }
      if (resp.status === 401) {
        onAuthLost?.()
        onEvent('error', { message: '登录已过期，请重新登录' })
        return
      }
    }

    if (!resp.ok || !resp.body) {
      onEvent('error', { message: `请求失败（HTTP ${resp.status}）` })
      return
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    // 分帧游标：支持 chunk 任意分割、UTF-8 跨块，同时兼容 \r\n\r\n 与裸 \n\n
    let consumed = 0
    const sepRe = /\r?\n\r?\n/g

    const handleBlock = (block) => {
      let event = 'message'
      const dataLines = []
      for (const rawLine of block.replace(/\r/g, '').split('\n')) {
        if (rawLine.startsWith('event:')) {
          event = rawLine.slice(6).trim()
        } else if (rawLine.startsWith('data:')) {
          dataLines.push(rawLine.slice(5).replace(/^ /, ''))
        }
      }
      if (!dataLines.length) return
      const data = dataLines.join('\n')
      let payloadData = data
      try {
        payloadData = JSON.parse(data)
      } catch {
        // status/delta 是纯文本
      }
      onEvent(event, payloadData)
    }

    const drainBuffer = () => {
      sepRe.lastIndex = consumed
      let match
      while ((match = sepRe.exec(buffer)) !== null) {
        const block = buffer.slice(consumed, match.index)
        consumed = match.index + match[0].length
        if (block.trim()) handleBlock(block)
      }
    }

    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        drainBuffer()
      }
      const tail = buffer.slice(consumed)
      if (tail.trim()) handleBlock(tail)
    } catch (err) {
      if (err?.name !== 'AbortError') {
        onEvent('error', { message: '连接中断' })
      }
    }
  }
}
