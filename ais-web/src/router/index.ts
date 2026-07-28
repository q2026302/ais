import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import { useAuthStore } from '@/stores/auth'
import { getAppBasePath } from '@/utils/appBasePath'
import {
  isMobileClient,
  resolvePostLoginTarget,
} from '@/utils/mobileWorkspace'

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
      path: '/pwa',
      component: () => import('@/views/mobile/FeishuMobileLayout.vue'),
      meta: { embedded: true, mobileEntry: 'pwa' },
      redirect: { name: 'pwa-sessions' },
      children: [
        {
          path: 'sessions',
          name: 'pwa-sessions',
          component: () => import('@/views/mobile/FeishuSessionsPage.vue'),
          meta: { embedded: true, mobileEntry: 'pwa' },
        },
        {
          path: 'chat/:id',
          name: 'pwa-chat',
          component: () => import('@/views/mobile/FeishuChatPage.vue'),
          meta: { embedded: true, mobileEntry: 'pwa', hideBottomNav: true },
        },
        {
          path: 'gallery',
          name: 'pwa-gallery',
          component: () => import('@/views/mobile/FeishuGalleryPage.vue'),
          meta: { embedded: true, mobileEntry: 'pwa' },
        },
        {
          path: 'profile',
          name: 'pwa-profile',
          component: () => import('@/views/mobile/FeishuProfilePage.vue'),
          meta: { embedded: true, mobileEntry: 'pwa' },
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
      return resolvePostLoginTarget(to.query.redirect)
    }
    return true
  }

  if (!auth.isAuthenticated) {
    return {
      name: 'login',
      query: { redirect: to.fullPath },
    }
  }

  // Desktop HomeView is not touch-friendly — send phone browsers to /mobile.
  if (to.name === 'home' && isMobileClient()) {
    return { path: '/mobile/sessions', replace: true }
  }

  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'home' }
  }

  return true
})

export default router
