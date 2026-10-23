import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import AppShell from '../components/AppShell.vue'
import MapListView from '../views/MapListView.vue'
import MapWorkspaceView from '../views/MapWorkspaceView.vue'
import FriendsView from '../views/FriendsView.vue'
import ActivitiesView from '../views/ActivitiesView.vue'
import AssistantView from '../views/AssistantView.vue'
import { safeRedirect } from '../utils/redirect'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    {
      // 全局导航壳：登录后的所有页面共用同一个顶栏（品牌/导航/退出）
      path: '/',
      component: AppShell,
      meta: { auth: true },
      children: [
        { path: '', component: MapListView, meta: { immersive: true, mobileNav: true } },
        { path: 'maps/:id', component: MapWorkspaceView, meta: { immersive: true } },
        { path: 'messages', component: () => import('../views/MessagesView.vue'), meta: { uiV2: true, mobileNav: true } },
        { path: 'friends', component: FriendsView },
        { path: 'activities', component: ActivitiesView, meta: { uiV2: true, mobileNav: true } },
        { path: 'assistant', component: AssistantView, meta: { immersive: true, mobileNav: true } },
        {
          path: 'conversations/:conversationId/plans/:planId',
          component: () => import('../views/PlanDetailView.vue')
        },
        // 执行追踪页面较大，懒加载避免进主包
        { path: 'traces', component: () => import('../views/TraceCenterView.vue') }
      ]
    }
  ]
})

router.beforeEach((to) => {
  if (to.matched.some((r) => r.meta.auth) && !localStorage.getItem('accessToken')) {
    // 保存原始目标（含 query），登录成功后回到活动/群聊/方案，而不是首页
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && localStorage.getItem('accessToken')) {
    return safeRedirect(to.query.redirect)
  }
})

export default router
