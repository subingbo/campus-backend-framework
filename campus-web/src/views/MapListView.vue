<template>
  <div class="page">
    <header class="top">
      <div>
        <h1>我的共享地图</h1>
        <p>与朋友共建校园地点</p>
      </div>
      <el-button type="danger" plain @click="logout">退出</el-button>
    </header>

    <div class="actions">
      <el-button type="primary" @click="showCreate = true">创建地图</el-button>
      <el-button @click="showJoin = true">邀请码加入</el-button>
    </div>

    <el-row :gutter="16">
      <el-col v-for="item in maps" :key="item.id" :xs="24" :sm="12" :md="8">
        <el-card class="map-card" shadow="hover" @click="openMap(item.id)">
          <h3>{{ item.name }}</h3>
          <p>{{ item.description || '暂无描述' }}</p>
          <div class="meta">
            <span>👥 {{ item.memberCount }}</span>
            <span>📍 {{ item.placeCount }}</span>
            <el-tag size="small">{{ item.myRole }}</el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-empty v-if="!loading && maps.length === 0" description="还没有地图，创建一个吧" />

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
    ElMessage.success(`创建成功，邀请码：${res.inviteCode}`)
    showCreate.value = false
    await loadMaps()
    router.push(`/maps/${res.mapId}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    creating.value = false
  }
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

function logout() {
  localStorage.clear()
  router.push('/login')
}

onMounted(loadMaps)
</script>

<style scoped>
.page {
  max-width: 1100px;
  margin: 0 auto;
  padding: 24px;
}
.top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.actions {
  margin: 16px 0 24px;
  display: flex;
  gap: 12px;
}
.map-card {
  margin-bottom: 16px;
  cursor: pointer;
}
.map-card h3 {
  margin: 0 0 8px;
}
.meta {
  display: flex;
  gap: 12px;
  align-items: center;
  color: #666;
  margin-top: 12px;
}
</style>
