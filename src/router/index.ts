import { createRouter, createWebHistory } from '@ionic/vue-router';
import { RouteRecordRaw } from 'vue-router';
import HomePage from '../views/HomePage.vue'
import CategoryPage from "@/views/CategoryPage.vue";
import SearchPage from "@/views/SearchPage.vue";
import FavoritePage from "@/views/FavoritePage.vue";
import DownloadPage from "@/views/DownloadPage.vue";
import SettingPage from "@/views/SettingPage.vue";

const routes: Array<RouteRecordRaw> = [
  {
    path: '/',
    redirect: '/home'
  },
  {
    path: '/home',
    component: HomePage,
    meta: { menu: true }
  },
  {
    path: '/category',
    name: 'CategoryPage',
    component: CategoryPage,
    meta: { menu: true, keepAlive: true }
  },
  {
    path: '/search',
    name: 'SearchPage',
    component: SearchPage,
    meta: { menu: true, keepAlive: true }
  },
  {
    path: '/favorite',
    component: FavoritePage,
    meta: { menu: true, keepAlive: true }
  },
  {
    path: '/download',
    component: DownloadPage,
    meta: { menu: true }
  },
  {
    path: '/setting',
    component: SettingPage,
    meta: { menu: true }
  },
  {
    path: '/album/:id',
    component: () => import('@/views/AlbumDetailPage.vue'),
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
    component: () => import('@/views/ReaderPage.vue'),
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
    name: 'NetworkStatusPage',
    component: () => import('@/views/NetworkStatusPage.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router
