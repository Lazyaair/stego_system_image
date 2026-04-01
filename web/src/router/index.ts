import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/chats' },
    {
      path: '/login',
      component: () => import('../views/auth/LoginView.vue'),
      meta: { guest: true },
    },
    {
      path: '/register',
      component: () => import('../views/auth/RegisterView.vue'),
      meta: { guest: true },
    },
    {
      path: '/chats',
      component: () => import('../views/chat/ChatListView.vue'),
    },
    {
      path: '/chat/:id',
      component: () => import('../views/chat/ChatView.vue'),
    },
    {
      path: '/contacts',
      component: () => import('../views/contact/ContactsView.vue'),
    },
    {
      path: '/contacts/add',
      component: () => import('../views/contact/AddContactView.vue'),
    },
    {
      path: '/contacts/requests',
      component: () => import('../views/contact/RequestsView.vue'),
    },
    {
      path: '/profile',
      component: () => import('../views/profile/ProfileView.vue'),
    },
    {
      path: '/embed',
      component: () => import('../views/EmbedView.vue'),
    },
    {
      path: '/extract',
      component: () => import('../views/ExtractView.vue'),
    },
  ],
})

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (!token && !to.meta.guest) {
    return '/login'
  }
  if (token && to.meta.guest) {
    return '/chats'
  }
})

export default router
