import { computed, ref } from 'vue'
import type { UserInfo } from '@/services/JmcomicTypes'
import { JmcomicService } from '@/services/JmcomicService'
import { clearFavoriteFolderStore } from '@/composables/favoriteFolderStore'
import { clearFavoritePageCache } from '@/composables/favoritePageCache'
import { normalizeRuntimeError } from '@/runtime/errors'

const userInfo = ref<UserInfo | null>(null)
const isLoggedIn = computed(() => userInfo.value !== null)

export type AuthInitializationResult = 'authenticated' | 'unauthenticated' | 'retryable-error'
export type AuthStatus =
  | 'idle'
  | 'loading'
  | 'authenticated'
  | 'retryable-error'
  | 'state-check-error'
  | 'credentials-invalid'
  | 'no-credentials'
export type SavedAuthProfile = Pick<UserInfo, 'uid' | 'username' | 'avatarUrl'>

/** 仅用于启动期间展示和登录页回填，不包含密码等敏感凭据。 */
const AUTH_PROFILE_KEY = 'jq_auth_profile'

function readSavedAuthProfile(): SavedAuthProfile | null {
  try {
    const raw = localStorage.getItem(AUTH_PROFILE_KEY)
    if (!raw) return null
    const value = JSON.parse(raw) as Partial<SavedAuthProfile>
    if (
      typeof value.uid !== 'string' ||
      typeof value.username !== 'string' ||
      typeof value.avatarUrl !== 'string'
    ) {
      localStorage.removeItem(AUTH_PROFILE_KEY)
      return null
    }
    return { uid: value.uid, username: value.username, avatarUrl: value.avatarUrl }
  } catch {
    localStorage.removeItem(AUTH_PROFILE_KEY)
    return null
  }
}

const savedProfile = ref<SavedAuthProfile | null>(readSavedAuthProfile())
/** 认证展示状态由所有需要显示登录进度的页面共享。 */
const authStatus = ref<AuthStatus>(savedProfile.value ? 'loading' : 'idle')

function saveProfile(info: UserInfo) {
  const profile = { uid: info.uid, username: info.username, avatarUrl: info.avatarUrl }
  localStorage.setItem(AUTH_PROFILE_KEY, JSON.stringify(profile))
  savedProfile.value = profile
}

function clearSavedProfile() {
  localStorage.removeItem(AUTH_PROFILE_KEY)
  savedProfile.value = null
}

function updateUserInfo(next: UserInfo | null) {
  const previousId = userInfo.value?.uid
  if (previousId && next?.uid !== previousId) {
    clearFavoriteFolderStore()
    clearFavoritePageCache()
  }
  userInfo.value = next
}

export function useAuth() {
  /** 启动认证只负责恢复状态；网络失败和凭据失败都留给用户通过头像再次触发。 */
  async function initAuth(
    canCommit: () => boolean = () => true,
  ): Promise<AuthInitializationResult> {
    if (canCommit()) authStatus.value = 'loading'
    try {
      const result = await JmcomicService.checkLoginState()
      if (result.loggedIn && result.userInfo) {
        if (canCommit()) {
          updateUserInfo(result.userInfo)
          if (result.hasSavedCredentials === false) clearSavedProfile()
          else saveProfile(result.userInfo)
          authStatus.value = 'authenticated'
        }
        return 'authenticated'
      }
    } catch {
      if (canCommit()) authStatus.value = 'retryable-error'
      return 'retryable-error'
    }

    try {
      const autoResult = await JmcomicService.autoLogin()
      if (autoResult.userInfo) {
        if (canCommit()) {
          updateUserInfo(autoResult.userInfo)
          saveProfile(autoResult.userInfo)
          authStatus.value = 'authenticated'
        }
        return 'authenticated'
      }
    } catch (error) {
      const normalized = normalizeRuntimeError(error, '自动登录失败')
      // 启动阶段的首次失败统一保留为可重试；只有用户再次点击并确认失败原因后才细分提示。
      if (canCommit()) authStatus.value = 'retryable-error'
      if (normalized.code !== 'not-found' && normalized.code !== 'permission-denied') {
        return 'retryable-error'
      }
    }

    if (canCommit()) {
      updateUserInfo(null)
      clearFavoriteFolderStore()
      clearFavoritePageCache()
      authStatus.value = 'retryable-error'
    }
    return 'unauthenticated'
  }

  async function reauthenticate(
    canCommit: () => boolean = () => true,
  ): Promise<AuthInitializationResult> {
    if (canCommit()) authStatus.value = 'loading'
    try {
      const result = await JmcomicService.autoLogin()
      if (result.userInfo) {
        if (canCommit()) {
          updateUserInfo(result.userInfo)
          saveProfile(result.userInfo)
          authStatus.value = 'authenticated'
        }
        return 'authenticated'
      }
      if (canCommit()) updateUserInfo(null)
      return 'unauthenticated'
    } catch (error) {
      const normalized = normalizeRuntimeError(error, '自动登录失败')
      if (normalized.code === 'not-found' || normalized.code === 'permission-denied') {
        if (canCommit()) {
          updateUserInfo(null)
          authStatus.value =
            normalized.code === 'not-found' ? 'no-credentials' : 'credentials-invalid'
        }
        return 'unauthenticated'
      }
      if (canCommit()) authStatus.value = 'retryable-error'
      return 'retryable-error'
    }
  }

  async function retryLogin(): Promise<AuthInitializationResult> {
    if (authStatus.value === 'loading') return 'retryable-error'
    authStatus.value = 'loading'
    // 先读取权威登录态，避免后端已经登录而前端缓存未同步时重复登录。
    try {
      const state = await JmcomicService.checkLoginState()
      if (state.loggedIn && state.userInfo) {
        updateUserInfo(state.userInfo)
        if (state.hasSavedCredentials === false) clearSavedProfile()
        else saveProfile(state.userInfo)
        authStatus.value = 'authenticated'
        return 'authenticated'
      }
    } catch {
      authStatus.value = 'state-check-error'
      return 'retryable-error'
    }
    return reauthenticate()
  }

  async function login(username: string, password: string, rememberMe = true): Promise<UserInfo> {
    authStatus.value = 'loading'
    let info: UserInfo
    try {
      info = await JmcomicService.login(username, password, rememberMe)
    } catch (error) {
      authStatus.value = savedProfile.value ? 'retryable-error' : 'idle'
      throw error
    }
    updateUserInfo(info)
    if (rememberMe) saveProfile(info)
    else clearSavedProfile()
    authStatus.value = 'authenticated'
    return info
  }

  async function logout(): Promise<void> {
    try {
      await JmcomicService.logout()
    } finally {
      updateUserInfo(null)
      clearSavedProfile()
      authStatus.value = 'idle'
    }
  }

  return {
    userInfo,
    isLoggedIn,
    savedProfile,
    authStatus,
    initAuth,
    reauthenticate,
    retryLogin,
    login,
    logout,
  }
}
