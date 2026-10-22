<template>
  <div class="login-page">
    <section class="hero">
      <div class="hero-inner">
        <p class="brand">Campus Map</p>
        <h1>把校园地点，<br />和朋友一起标出来</h1>
        <p class="tagline">校园共享地图与协作</p>
        <ul class="points">
          <li>共建地点 — 教室、食堂、社团据点，一起补全</li>
          <li>邀请加入 — 一个邀请码，拉朋友进同一张地图</li>
          <li>实时定位 — 看到彼此此刻在哪里</li>
        </ul>
      </div>
    </section>

    <section class="panel">
      <div class="panel-inner">
        <div class="seg" role="tablist">
          <button type="button" :class="{ on: mode === 'login' }" @click="mode = 'login'">登录</button>
          <button type="button" :class="{ on: mode === 'register' }" @click="mode = 'register'">注册</button>
        </div>
        <el-form :model="form" label-position="top" @submit.prevent="submit">
          <el-form-item label="用户名">
            <el-input v-model="form.username" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" type="password" autocomplete="current-password" />
          </el-form-item>
          <el-form-item v-if="mode === 'register'" label="昵称">
            <el-input v-model="form.nickname" />
          </el-form-item>
          <el-button class="submit-btn" type="primary" native-type="submit" :loading="loading">
            {{ mode === 'login' ? '登录' : '注册并登录' }}
          </el-button>
        </el-form>
      </div>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../api'
import { beginSession } from '../api/authSession'

const router = useRouter()
const mode = ref('login')
const loading = ref(false)
const form = reactive({ username: '', password: '', nickname: '' })

async function submit() {
  loading.value = true
  try {
    if (mode.value === 'register') {
      await authApi.register({
        username: form.username,
        password: form.password,
        nickname: form.nickname || form.username
      })
    }
    const tokens = await authApi.login({ username: form.username, password: form.password })
    beginSession(tokens)
    ElMessage.success('登录成功')
    router.push('/')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  background: #fff;
}
.hero {
  flex: 1 1 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
  background: linear-gradient(160deg, #dfe9fd 0%, #eef3fe 45%, #f8faff 100%);
}
.hero-inner {
  max-width: 460px;
}
.brand {
  margin: 0 0 24px;
  font-weight: 700;
  font-size: 15px;
  letter-spacing: 0.06em;
  color: var(--el-color-primary);
}
.hero h1 {
  margin: 0;
  font-size: 36px;
  line-height: 1.4;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.9);
}
.tagline {
  margin: 14px 0 28px;
  color: rgba(0, 0, 0, 0.55);
}
.points {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 12px;
}
.points li {
  position: relative;
  padding-left: 16px;
  font-size: 15px;
  line-height: 1.6;
  color: rgba(0, 0, 0, 0.55);
}
.points li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-primary);
}
.panel {
  flex: 1 1 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 24px;
}
.panel-inner {
  width: 100%;
  max-width: 360px;
}
.seg {
  display: flex;
  padding: 4px;
  margin-bottom: 24px;
  border-radius: 10px;
  background: #f0f3fa;
}
.seg button {
  flex: 1;
  border: none;
  padding: 8px 0;
  border-radius: 8px;
  background: transparent;
  font: inherit;
  font-size: 14px;
  color: rgba(0, 0, 0, 0.55);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.seg button.on {
  background: #fff;
  color: var(--el-color-primary);
  font-weight: 600;
  box-shadow: 0 1px 4px rgba(31, 56, 88, 0.12);
}
.submit-btn {
  width: 100%;
}
@media (max-width: 900px) {
  .login-page {
    flex-direction: column;
  }
  .hero {
    padding: 32px 24px;
  }
  .hero-inner {
    max-width: none;
  }
  .hero h1 {
    font-size: 24px;
  }
  .tagline {
    margin-bottom: 0;
  }
  .points {
    display: none;
  }
  .panel {
    padding: 32px 24px;
  }
}
</style>
