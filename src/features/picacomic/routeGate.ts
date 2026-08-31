import type { RouteLocationNormalized } from 'vue-router'
import { picacomicNativeClient } from './client'
import { isPicacomicRouteName, usePicacomicAuth } from './auth'
import type { PicacomicPluginClient, PicacomicRedirect } from './types'

export function shouldExposePicacomicRoutes(mode: string, nativeDebugUiEnabled = false): boolean {
  return mode === 'test' || mode === 'development' || nativeDebugUiEnabled
}

export async function isPicacomicDebugUiEnabled(
  client: Pick<PicacomicPluginClient, 'getBuildInfo'> = picacomicNativeClient,
): Promise<boolean> {
  if (shouldExposePicacomicRoutes(import.meta.env.MODE, false)) return true
  try {
    const info = await client.getBuildInfo()
    return shouldExposePicacomicRoutes(import.meta.env.MODE, info.debugUiEnabled === true)
  } catch {
    return false
  }
}

export function redirectFromPicacomicRoute(
  route: Pick<RouteLocationNormalized, 'name' | 'params'>,
): PicacomicRedirect | null {
  if (!isPicacomicRouteName(route.name)) return null
  const params: Record<string, string> = {}
  for (const key of ['albumId', 'chapterId']) {
    const value = route.params[key]
    if (typeof value === 'string' && value.length > 0) params[key] = value
  }
  return { name: route.name, params }
}

export async function picacomicAuthGuard(to: RouteLocationNormalized) {
  if (to.name === 'PicacomicLoginPage') return true
  const auth = usePicacomicAuth()
  try {
    const state = await auth.refresh()
    if (state.state === 'signed_in') return true
  } catch {
    // Treat bridge failures as signed out for an internal debug route.
  }
  const redirect = redirectFromPicacomicRoute(to)
  if (redirect) auth.captureRedirect(redirect)
  return { name: 'PicacomicLoginPage' }
}

export function routeForPicacomicError(error: unknown) {
  const auth = usePicacomicAuth()
  if (error && typeof error === 'object' && 'code' in error) {
    const code = (error as { code?: unknown }).code
    if (code === 'PICACOMIC_AUTH_REQUIRED' || code === 'PICACOMIC_AUTH_EXPIRED') {
      auth.captureRedirect({ name: 'PicacomicBrowsePage', params: {} })
      return { name: 'PicacomicLoginPage' }
    }
  }
  return null
}
