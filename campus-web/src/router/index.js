import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import AppShell from '../components/AppShell.vue'
import MapListView from '../views/MapListView.vue'
import MapWorkspaceView from '../views/MapWorkspaceView.vue'
import FriendsView from '../views/FriendsView.vue'
import ActivitiesView from '../views/ActivitiesView.vue'
import AssistantView from '../views/AssistantView.vue'

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
        { path: '', component: MapListView },
        { path: 'maps/:id', component: MapWorkspaceView },
        { path: 'friends', component: FriendsView },
        { path: 'activities', component: ActivitiesView },
        { path: 'assistant', component: AssistantView },
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
    return '/login'
  }
  if (to.path === '/login' && localStorage.getItem('accessToken')) {
    return '/'
  }
})

export default router
