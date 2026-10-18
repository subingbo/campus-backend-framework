<template>
  <div class="page">
    <div class="hero">
      <h1>我的共享地图</h1>
      <p>与朋友共建校园地点</p>
    </div>

    <div class="actions">
      <el-button type="primary" @click="showCreate = true">创建地图</el-button>
      <el-button @click="showJoin = true">邀请码加入</el-button>
    </div>

    <el-row :gutter="16">
      <el-col v-for="item in maps" :key="item.id" :xs="24" :sm="12" :md="8">
        <el-card class="map-card" @click="openMap(item.id)">
          <h3>{{ item.name }}</h3>
          <p>{{ item.description || '暂无描述' }}</p>
          <div class="meta">
            <span class="chip">👥 {{ item.memberCount }}</span>
            <span class="chip">📍 {{ item.placeCount }}</span>
            <el-tag size="small" round>{{ item.myRole }}</el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-empty v-if="!loading && maps.length === 0" class="empty" description="还没有地图，创建一个吧">
      <el-button type="primary" @click="showCreate = true">创建地图</el-button>
    </el-empty>

    <el-dialog v-model="showCreate" title="创建共享地图" width="420px">
      <el-form :model="createForm" label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="createForm.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createMap">创建</el-button>
      </template>
    </el-dialog>

    <!-- 创建成功：邀请码在这里展示并可复制（此前只在 toast 里一闪而过） -->
    <el-dialog v-model="createdDialog" title="地图已创建" width="420px">
      <p class="hint">把邀请码发给朋友，对方凭码即可加入这张地图：</p>
      <div class="created-code">
        <span class="created-code__num">{{ createdInviteCode }}</span>
        <el-button size="small" @click="copyCreatedCode">复制</el-button>
      </div>
      <template #footer>
        <el-button @click="createdDialog = false">留在本页</el-button>
        <el-button type="primary" @click="enterCreatedMap">进入地图</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showJoin" title="邀请码加入" width="420px">
      <el-input v-model="joinCode" placeholder="输入 8 位邀请码" />
      <template #footer>
        <el-button @click="showJoin = false">取消</el-button>
        <el-button type="primary" :loading="joining" @click="joinMap">加入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { mapApi } from '../api'

const router = useRouter()
const maps = ref([])
const loading = ref(false)
const showCreate = ref(false)
const showJoin = ref(false)
const creating = ref(false)
const joining = ref(false)
const joinCode = ref('')
const createdDialog = ref(false)
const createdInviteCode = ref('')
const createdMapId = ref(null)
const createForm = reactive({ name: '', description: '' })

async function loadMaps() {
  loading.value = true
  try {
    maps.value = await mapApi.list()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openMap(id) {
  router.push(`/maps/${id}`)
}

async function createMap() {
  if (!createForm.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  creating.value = true
  try {
    const res = await mapApi.createGroup(createForm)
    showCreate.value = false
    createdInviteCode.value = res.inviteCode
    createdMapId.value = res.mapId
    createdDialog.value = true
    await loadMaps()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    creating.value = false
  }
}

function copyCreatedCode() {
  navigator.clipboard.writeText(createdInviteCode.value)
  ElMessage.success('已复制邀请码')
}

function enterCreatedMap() {
  createdDialog.value = false
  router.push(`/maps/${createdMapId.value}`)
}

async function joinMap() {
  joining.value = true
  try {
    const detail = await mapApi.joinGroup({ inviteCode: joinCode.value.trim().toUpperCase() })
    ElMessage.success('加入成功')
    showJoin.value = false
    await loadMaps()
    router.push(`/maps/${detail.id}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    joining.value = false
  }
}

onMounted(() => {
  loadMaps()
})
</script>

<style scoped>
.page {
  max-width: 1100px;
  margin: 0 auto;
  padding: 0 32px 56px;
}
.hero {
  margin: 40px 0 28px;
}
.hero h1 {
  margin: 0 0 6px;
  font-size: 28px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.9);
}
.hero p {
  margin: 0;
  color: rgba(0, 0, 0, 0.55);
}
.actions {
  margin: 0 0 24px;
  display: flex;
  gap: 12px;
}
.map-card {
  margin-bottom: 16px;
  cursor: pointer;
  border: none;
  border-radius: 16px;
  box-shadow: 0 2px 12px rgba(31, 56, 88, 0.06);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.map-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(31, 56, 88, 0.1);
}
.map-card h3 {
  margin: 0 0 8px;
  font-weight: 600;
}
.map-card p {
  margin: 0;
  font-size: 14px;
  color: rgba(0, 0, 0, 0.55);
}
.meta {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 14px;
}
.chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 12px;
  line-height: 20px;
}
.empty {
  padding: 56px 0;
}
.created-code {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding: 10px 14px;
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
}
.created-code__num {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.14em;
  font-variant-numeric: tabular-nums;
  color: var(--el-color-primary);
}
@media (max-width: 600px) {
  .page {
    padding: 0 16px 40px;
  }
  .hero {
    margin: 24px 0 20px;
  }
  .hero h1 {
    font-size: 24px;
  }
}
</style>
