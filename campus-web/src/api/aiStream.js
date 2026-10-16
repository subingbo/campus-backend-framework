/**
 * AI 助手 SSE 流式封装：EventSource 不支持自定义 Authorization 头，
 * 因此走 fetch + ReadableStream 自行解析 data 协议（event: name / data: ...）。
 * 事件：status 文本提示、delta 最终答复增量、plan 草稿JSON、done 结束、error 失败。
 */
export async function streamAssistantChat(chatId, content, onEvent, signal) {
  let resp
  try {
    resp = await fetch(`/api/assistant/chats/${chatId}/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${localStorage.getItem('accessToken') || ''}`
      },
      body: JSON.stringify({ content }),
      signal
    })
  } catch (err) {
    onEvent('error', { message: signal?.aborted ? '已取消' : '网络错误，请确认后端已启动' })
    return
  }
  if (!resp.ok || !resp.body) {
    onEvent('error', { message: `请求失败（HTTP ${resp.status}）` })
    return
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  // 分帧游标：支持 chunk 任意分割、UTF-8 跨块，同时兼容 \r\n\r\n（RFC 7230 行风）与裸 \n\n
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
    let payload = data
    try {
      payload = JSON.parse(data)
    } catch (e) {
      // status/delta 是纯文本
    }
    onEvent(event, payload)
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
