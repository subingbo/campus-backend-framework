<template>
  <div class="login-page ui-v2">
    <section class="hero">
      <div class="hero-inner">
        <p class="brand">
          <span class="brand-mark"><AppIcon name="pin" :size="15" /></span>
          Campus Map
        </p>
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
          <button type="button" role="tab" :aria-selected="mode === 'login'" :class="{ on: mode === 'login' }" @click="mode = 'login'">登录</button>
          <button type="button" role="tab" :aria-selected="mode === 'register'" :class="{ on: mode === 'register' }" @click="mode = 'register'">注册</button>
        </div>
        <el-form :model="form" label-position="top" @submit.prevent="submit">
          <el-form-item label="用户名">
            <el-input v-model="form.username" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" type="password" autocomplete="current-password" show-password />
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
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../api'
import { beginSession } from '../api/authSession'
import { safeRedirect } from '../utils/redirect'
import AppIcon from '../components/ui/AppIcon.vue'

const router = useRouter()
const route = useRoute()
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
    router.replace(safeRedirect(route.query.redirect))
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100dvh;
  display: flex;
  background: var(--ui-bg);
  color: var(--ui-ink);
}
.hero {
  flex: 1 1 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
}
.hero-inner {
  max-width: 460px;
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  margin: 0 0 24px;
  font-weight: 600;
  font-size: 14px;
  letter-spacing: 0.02em;
  color: var(--ui-ink);
}
.brand-mark {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  background: var(--ui-accent);
  color: #fff;
}
.hero h1 {
  margin: 0;
  font-size: 34px;
  line-height: 1.35;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ui-ink);
}
.tagline {
  margin: 14px 0 28px;
  color: var(--ui-ink-2);
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
  font-size: 14px;
  line-height: 1.65;
  color: var(--ui-ink-2);
}
.points li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 9px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ui-accent);
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
  max-width: 380px;
  padding: 26px;
  border-radius: var(--ui-radius-xl);
  background: var(--ui-surface);
  box-shadow: var(--ui-shadow-1);
}
.seg {
  display: flex;
  padding: 4px;
  margin-bottom: 22px;
  border-radius: var(--ui-radius-md);
  background: var(--ui-surface-2);
}
.seg button {
  flex: 1;
  min-height: 34px;
  border: none;
  padding: 6px 0;
  border-radius: var(--ui-radius-sm);
  background: transparent;
  font: inherit;
  font-size: 13px;
  color: var(--ui-ink-2);
  cursor: pointer;
  transition: background var(--ui-dur) var(--ui-ease), color var(--ui-dur) var(--ui-ease);
}
.seg button:hover {
  color: var(--ui-ink);
}
.seg button.on {
  background: var(--ui-surface);
  color: var(--ui-accent);
  font-weight: 600;
}
.seg button:focus-visible {
  outline: 2px solid var(--ui-accent);
  outline-offset: 2px;
}
.submit-btn {
  width: 100%;
  height: var(--ui-tap);
  border-radius: var(--ui-radius-md);
}
@media (max-width: 900px) {
  .login-page {
    flex-direction: column;
  }
  .hero {
    padding: 36px 24px 20px;
  }
  .hero-inner {
    max-width: none;
  }
  .hero h1 {
    font-size: 24px;
    line-height: 1.35;
  }
  .tagline {
    margin-bottom: 0;
  }
  .points {
    display: none;
  }
  .panel {
    padding: 12px 16px 40px;
  }
  .panel-inner {
    max-width: none;
    padding: 20px;
  }
}
</style>
