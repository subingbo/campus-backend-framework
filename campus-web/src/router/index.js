import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import MapListView from '../views/MapListView.vue'
import MapWorkspaceView from '../views/MapWorkspaceView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    { path: '/', component: MapListView, meta: { auth: true } },
    { path: '/maps/:id', component: MapWorkspaceView, meta: { auth: true } }
  ]
})

router.beforeEach((to) => {
  if (to.meta.auth && !localStorage.getItem('accessToken')) {
    return '/login'
  }
  if (to.path === '/login' && localStorage.getItem('accessToken')) {
    return '/'
  }
})

export default router
