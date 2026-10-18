<template>
  <div class="md" v-html="html"></div>
</template>

<script setup>
/**
 * AI 回复的 Markdown 渲染：marked 解析 + DOMPurify 消毒（v-html 必须消毒）。
 * 流式期间请由调用方传纯文本，结束后再切到本组件，避免每个 delta 全量重排。
 */
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({
  text: { type: String, default: '' }
})

marked.setOptions({ gfm: true, breaks: true })

const html = computed(() => {
  const raw = props.text || ''
  if (!raw) return ''
  try {
    return DOMPurify.sanitize(marked.parse(raw))
  } catch (e) {
    const escaped = raw.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    return `<p>${escaped}</p>`
  }
})
</script>

<style scoped>
.md {
  font-size: 13px;
  line-height: 1.7;
  word-break: break-word;
  white-space: normal;
}
.md :deep(p) {
  margin: 0 0 8px;
}
.md :deep(p:last-child) {
  margin-bottom: 0;
}
.md :deep(h1),
.md :deep(h2),
.md :deep(h3),
.md :deep(h4) {
  margin: 10px 0 6px;
  font-size: 14px;
  font-weight: 600;
}
.md :deep(ul),
.md :deep(ol) {
  margin: 4px 0 8px;
  padding-left: 20px;
}
.md :deep(li) {
  margin: 2px 0;
}
.md :deep(code) {
  background: rgba(0, 0, 0, 0.06);
  padding: 1px 5px;
  border-radius: 4px;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}
.md :deep(pre) {
  background: #f6f8fa;
  padding: 8px 10px;
  border-radius: 8px;
  overflow-x: auto;
}
.md :deep(pre code) {
  background: none;
  padding: 0;
}
.md :deep(blockquote) {
  margin: 6px 0;
  padding: 2px 10px;
  border-left: 3px solid var(--el-color-primary-light-5);
  color: rgba(0, 0, 0, 0.6);
  background: rgba(0, 82, 217, 0.03);
}
.md :deep(table) {
  border-collapse: collapse;
  margin: 6px 0;
  font-size: 12px;
}
.md :deep(th),
.md :deep(td) {
  border: 1px solid var(--el-border-color-lighter);
  padding: 4px 8px;
}
.md :deep(a) {
  color: var(--el-color-primary);
  text-decoration: none;
}
.md :deep(hr) {
  border: none;
  border-top: 1px solid var(--el-border-color-lighter);
  margin: 10px 0;
}
.md :deep(strong) {
  font-weight: 600;
}
</style>
