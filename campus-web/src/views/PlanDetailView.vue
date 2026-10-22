<template>
  <div class="page plan-detail">
    <div class="plan-detail__head">
      <el-button text @click="router.back()">← 返回群聊</el-button>
      <h1>群规划方案</h1>
      <p class="hint">群成员可查看方案摘要；完整 AI 对话与编辑保存仅发起人可见。</p>
    </div>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-else-if="loading" v-loading="true" class="plan-detail__loading" />

    <template v-else-if="card">
      <GroupPlanCard
        :card="card"
        :me="me"
        :show-view-action="false"
        @continue="continuePlan"
      />
      <p class="hint plan-detail__foot">
        规划卡是生成时快照；“已保存为活动”状态会随发起人保存/修订动态更新。
      </p>
    </template>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { chatApi } from '../api'
import { myUserId } from '../composables/useChatSocket'
import GroupPlanCard from '../components/GroupPlanCard.vue'

const route = useRoute()
const router = useRouter()
const me = myUserId()
const loading = ref(true)
const error = ref('')
const card = ref(null)

const conversationId = Number(route.params.conversationId)
const planId = Number(route.params.planId)

onMounted(async () => {
  if (!Number.isFinite(conversationId) || !Number.isFinite(planId)) {
    error.value = '规划地址无效'
    loading.value = false
    return
  }
  try {
    const view = await chatApi.agentPlan(conversationId, planId)
    card.value = { ...view, plan: view }
  } catch (e) {
    error.value = e.message || '无法查看该规划：仅当前群成员可访问'
  } finally {
    loading.value = false
  }
})

async function continuePlan(target) {
  try {
    const handoff = await chatApi.agentPlanHandoff(conversationId, target.planId)
    router.push({ path: '/assistant', query: { chat: handoff.aiConversationId } })
  } catch (e) {
    ElMessage.error(e.message || '仅发起人可继续规划')
  }
}
</script>

<style scoped>
.plan-detail {
  max-width: 760px;
  margin: 0 auto;
  padding: 24px 20px 48px;
}
.plan-detail__head h1 {
  margin: 8px 0 4px;
  font-size: 22px;
}
.plan-detail__head .hint {
  margin: 0 0 16px;
  color: #909399;
  font-size: 12px;
}
.plan-detail__loading {
  min-height: 160px;
}
.plan-detail__foot {
  margin-top: 12px;
  color: #909399;
  font-size: 12px;
}
</style>
