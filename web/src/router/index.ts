import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/embed' },
    { path: '/embed', name: 'Embed', component: () => import('../views/EmbedView.vue') },
    { path: '/extract', name: 'Extract', component: () => import('../views/ExtractView.vue') }
  ]
})

export default router
