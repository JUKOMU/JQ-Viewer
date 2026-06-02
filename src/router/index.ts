import {createRouter, createWebHistory} from '@ionic/vue-router'
import type {RouteRecordRaw} from 'vue-router'

const routes: Array<RouteRecordRaw> = [
  {
    path: '/',
    redirect: '/home',
  },
  {
    path: '/home',
    component: () => import('@/views/HomePage.vue'),
    meta: {menu: true},
  },
  {
    path: '/category',
    name: 'CategoryPage',
    component: () => import('@/views/CategoryPage.vue'),
    meta: {menu: true, keepAlive: true},
  },
  {
    path: '/search',
    name: 'SearchPage',
    component: () => import('@/views/SearchPage.vue'),
    meta: {menu: true, keepAlive: true},
  },
  {
    path: '/favorite',
    name: 'FavoritePage',
    component: () => import('@/views/FavoritePage.vue'),
    meta: {menu: true, keepAlive: true},
  },
  {
    path: '/download',
    name: 'DownloadPage',
    component: () => import('@/views/DownloadPage.vue'),
    meta: {menu: true, keepAlive: true},
  },
  {
    path: '/setting',
    component: () => import('@/views/SettingPage.vue'),
    meta: {menu: true, keepAlive: true},
  },
  {
    path: '/history',
    name: 'HistoryPage',
    component: () => import('@/views/HistoryPage.vue'),
    meta: {menu: true, keepAlive: true},
  },
  {
    path: '/album/:id',
    component: () => import('@/views/AlbumDetailPage.vue'),
    meta: {menu: true},
  },
  {
    path: '/album/:albumId/preview/:chapterId',
    component: () => import('@/views/PreviewAllPage.vue'),
  },
  {
    path: '/album/:albumId/download-chapters',
    component: () => import('@/views/ChapterSelectPage.vue'),
  },
  {
    path: '/album/:albumId/read/:chapterId',
    name: 'ReaderPage',
    component: () => import('@/views/ReaderPage.vue'),
    meta: {menu: false, keepAlive: true},
  },
  {
    path: '/login',
    component: () => import('@/views/LoginPage.vue'),
  },
  {
    path: '/user',
    component: () => import('@/views/UserPage.vue'),
  },
  {
    path: '/network-status',
    component: () => import('@/views/NetworkStatusPage.vue'),
  },
  {
    path: '/about',
    component: () => import('@/views/AboutPage.vue'),
  },
  {
    path: '/pdf-template-help',
    component: () => import('@/views/PdfTemplateHelpPage.vue'),
  },
  {
    path: '/batch-parse',
    name: 'BatchParsePage',
    component: () => import('@/views/BatchParsePage.vue'),
    meta: {menu: true, keepAlive: true},
  },
  {
    path: '/import-review',
    name: 'PdfImportPage',
    component: () => import('@/views/PdfImportPage.vue'),
    meta: {menu: false, keepAlive: false},
  },
  {
    path: '/pdf-reader',
    name: 'PdfReaderPage',
    component: () => import('@/views/PdfReaderPage.vue'),
    meta: {menu: false, keepAlive: true},
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

export default router
