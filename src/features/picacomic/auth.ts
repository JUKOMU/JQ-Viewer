import { computed, readonly, ref, type ComputedRef, type Ref } from 'vue'
import type { PicacomicAuthState, PicacomicRedirect, PicacomicRouteName } from './types'
import { picacomicService } from './service'
import type { PicacomicService } from './service'

export interface PicacomicAuthController {
  state: Readonly<Ref<PicacomicAuthState>>
  user: ComputedRef<PicacomicAuthState['user']>
  isSignedIn: ComputedRef<boolean>
  refresh: () => Promise<PicacomicAuthState>
  login: (usernameOrEmail: string, password: string) => Promise<PicacomicAuthState>
  logout: () => Promise<void>
  captureRedirect: (redirect: PicacomicRedirect) => void
  consumeRedirect: () => PicacomicRedirect | null
}

export function createPicacomicAuth(service: PicacomicService): PicacomicAuthController {
  const state = ref<PicacomicAuthState>({ state: 'signed_out' })
  const redirect = ref<PicacomicRedirect | null>(null)

  const refresh = async () => {
    const next = await service.getAuthState()
    state.value = next
    return next
  }

  const login = async (usernameOrEmail: string, password: string) => {
    state.value = { state: 'authenticating' }
    try {
      const next = await service.login(usernameOrEmail, password)
      state.value = next
      return next
    } catch (error) {
      const code = error && typeof error === 'object' && 'code' in error ? error.code : undefined
      state.value =
        code === 'PICACOMIC_AUTH_EXPIRED' ? { state: 'expired' } : { state: 'signed_out' }
      throw error
    }
  }

  const logout = async () => {
    try {
      await service.logout()
    } finally {
      state.value = { state: 'signed_out' }
      redirect.value = null
    }
  }

  const captureRedirect = (next: PicacomicRedirect) => {
    redirect.value = {
      name: next.name,
      params: { ...next.params },
    }
  }

  const consumeRedirect = () => {
    const next = redirect.value
    redirect.value = null
    return next ? { name: next.name, params: { ...next.params } } : null
  }

  return {
    state: readonly(state),
    user: computed(() => state.value.user),
    isSignedIn: computed(() => state.value.state === 'signed_in'),
    refresh,
    login,
    logout,
    captureRedirect,
    consumeRedirect,
  }
}

export const picacomicAuth = createPicacomicAuth(picacomicService)

export function usePicacomicAuth() {
  return picacomicAuth
}

export function isPicacomicRouteName(value: unknown): value is PicacomicRouteName {
  return (
    value === 'PicacomicLoginPage' ||
    value === 'PicacomicBrowsePage' ||
    value === 'PicacomicAlbumPage' ||
    value === 'PicacomicReaderPage'
  )
}
