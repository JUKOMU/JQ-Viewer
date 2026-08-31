import type { RouteRecordRaw } from 'vue-router'
import { picacomicAuthGuard } from './routeGate'

export const PICACOMIC_ROUTE_PREFIX = '/__picacomic'

export function createPicacomicRoutes(): RouteRecordRaw[] {
  return [
    {
      path: `${PICACOMIC_ROUTE_PREFIX}/login`,
      name: 'PicacomicLoginPage',
      component: () => import('./pages/PicacomicLoginPage.vue'),
      meta: { menu: false, picacomicInternal: true },
    },
    {
      path: `${PICACOMIC_ROUTE_PREFIX}/browse`,
      name: 'PicacomicBrowsePage',
      component: () => import('./pages/PicacomicBrowsePage.vue'),
      beforeEnter: picacomicAuthGuard,
      meta: { menu: false, picacomicInternal: true },
    },
    {
      path: `${PICACOMIC_ROUTE_PREFIX}/album/:albumId`,
      name: 'PicacomicAlbumPage',
      component: () => import('./pages/PicacomicAlbumPage.vue'),
      beforeEnter: picacomicAuthGuard,
      meta: { menu: false, picacomicInternal: true },
    },
    {
      path: `${PICACOMIC_ROUTE_PREFIX}/read/:albumId/:chapterId`,
      name: 'PicacomicReaderPage',
      component: () => import('./pages/PicacomicReaderPage.vue'),
      beforeEnter: picacomicAuthGuard,
      meta: { menu: false, picacomicInternal: true },
    },
  ]
}
