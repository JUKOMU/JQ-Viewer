import { computed, ref } from 'vue'
import type { UserInfo } from '@/services/JmcomicTypes'
import { JmcomicService } from '@/services/JmcomicService'
import { clearFavoriteFolderStore } from '@/composables/favoriteFolderStore'
import { clearFavoritePageCache } from '@/composables/favoritePageCache'
import { normalizeRuntimeError } from '@/runtime/errors'

const userInfo = ref<UserInfo | null>(null)
const isLoggedIn = computed(() => userInfo.value !== null)

export type AuthInitializationResult = 'authenticated' | 'unauthenticated' | 'retryable-error'

function updateUserInfo(next: UserInfo | null) {
  const previousId = userInfo.value?.uid
  if (previousId && next?.uid !== previousId) {
    clearFavoriteFolderStore()
    clearFavoritePageCache()
  }
  userInfo.value = next
}

export function useAuth() {
  /** 启动时调用，先检查本地登录态，如无则尝试自动登录（仅 App.vue onMounted 调用） */
  async function initAuth(
    canCommit: () => boolean = () => true,
  ): Promise<AuthInitializationResult> {
    try {
      const result = await JmcomicService.checkLoginState()
      if (result.loggedIn && result.userInfo) {
        if (canCommit()) updateUserInfo(result.userInfo)
        return 'authenticated'
      }
    } catch {
      return 'retryable-error'
    }

    try {
      const autoResult = await JmcomicService.autoLogin()
      if (autoResult.userInfo) {
        if (canCommit()) updateUserInfo(autoResult.userInfo)
        return 'authenticated'
      }
    } catch (error) {
      const normalized = normalizeRuntimeError(error, '自动登录失败')
      if (normalized.code !== 'not-found' && normalized.code !== 'permission-denied') {
        return 'retryable-error'
      }
    }

    if (canCommit()) {
      updateUserInfo(null)
      clearFavoriteFolderStore()
      clearFavoritePageCache()
    }
    return 'unauthenticated'
  }

  /** 登录并更新本地状态 */
  async function login(username: string, password: string): Promise<UserInfo> {
    const info = await JmcomicService.login(username, password)
    updateUserInfo(info)
    return info
  }

  /** 登出并清除本地状态 */
  async function logout(): Promise<void> {
    try {
      await JmcomicService.logout()
    } finally {
      updateUserInfo(null)
    }
  }

  return { userInfo, isLoggedIn, initAuth, login, logout }
}
