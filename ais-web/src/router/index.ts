import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import { useAuthStore } from '@/stores/auth'
import { getAppBasePath } from '@/utils/appBasePath'

const router = createRouter({
  history: createWebHistory(getAppBasePath()),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
    {
      // Formal mobile workbench entry (standalone mobile UX).
      path: '/mobile',
      name: 'mobile-workbench',
      component: () => import('@/views/FeishuH5View.vue'),
      meta: { embedded: true, mobileEntry: 'mobile' },
    },
    {
      // Feishu / in-app browser compatibility entry; shares the same mobile workbench view.
      path: '/feishu',
      name: 'feishu-h5',
      component: () => import('@/views/FeishuH5View.vue'),
      meta: { embedded: true, mobileEntry: 'feishu' },
    },
    // Phase-1 nested mobile routes (layout + placeholders). Existing /mobile and /feishu above stay intact.
    {
      path: '/mobile',
      component: () => import('@/views/mobile/FeishuMobileLayout.vue'),
      meta: { embedded: true, mobileEntry: 'mobile' },
      redirect: { name: 'mobile-sessions' },
      children: [
        {
          path: 'sessions',
          name: 'mobile-sessions',
          component: () => import('@/views/mobile/FeishuSessionsPage.vue'),
          meta: { embedded: true, mobileEntry: 'mobile' },
        },
        {
          path: 'chat/:id',
          name: 'mobile-chat',
          component: () => import('@/views/mobile/FeishuChatPage.vue'),
          meta: { embedded: true, mobileEntry: 'mobile', hideBottomNav: true },
        },
        {
          path: 'gallery',
          name: 'mobile-gallery',
          component: () => import('@/views/mobile/FeishuGalleryPage.vue'),
          meta: { embedded: true, mobileEntry: 'mobile' },
        },
        {
          path: 'profile',
          name: 'mobile-profile',
          component: () => import('@/views/mobile/FeishuProfilePage.vue'),
          meta: { embedded: true, mobileEntry: 'mobile' },
        },
      ],
    },
    {
      path: '/feishu',
      component: () => import('@/views/mobile/FeishuMobileLayout.vue'),
      meta: { embedded: true, mobileEntry: 'feishu' },
      redirect: { name: 'feishu-sessions' },
      children: [
        {
          path: 'sessions',
          name: 'feishu-sessions',
          component: () => import('@/views/mobile/FeishuSessionsPage.vue'),
          meta: { embedded: true, mobileEntry: 'feishu' },
        },
        {
          path: 'chat/:id',
          name: 'feishu-chat',
          component: () => import('@/views/mobile/FeishuChatPage.vue'),
          meta: { embedded: true, mobileEntry: 'feishu', hideBottomNav: true },
        },
        {
          path: 'gallery',
          name: 'feishu-gallery',
          component: () => import('@/views/mobile/FeishuGalleryPage.vue'),
          meta: { embedded: true, mobileEntry: 'feishu' },
        },
        {
          path: 'profile',
          name: 'feishu-profile',
          component: () => import('@/views/mobile/FeishuProfilePage.vue'),
          meta: { embedded: true, mobileEntry: 'feishu' },
        },
      ],
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('@/views/ProfileView.vue'),
    },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('@/views/UserManagementView.vue'),
      meta: { requiresAdmin: true },
    },
    {
      path: '/admin',
      name: 'admin',
      component: () => import('@/views/AdminView.vue'),
      meta: { requiresAdmin: true },
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.bootstrapped) {
    await auth.bootstrap()
  }

  if (to.meta.public) {
    if (auth.isAuthenticated && to.name === 'login') {
      const redirect = typeof to.query.redirect === 'string' ? to.query.redirect : '/'
      return redirect.startsWith('/') ? redirect : '/'
    }
    return true
  }

  if (!auth.isAuthenticated) {
    return {
      name: 'login',
      query: { redirect: to.fullPath },
    }
  }

  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'home' }
  }

  return true
})

export default router
