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
    component: CategoryPage,
    meta: { menu: true }
  },
  {
    path: '/search',
    component: SearchPage,
    meta: { menu: true }
  },
  {
    path: '/favorite',
    component: FavoritePage,
    meta: { menu: true }
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
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router
