import {computed, ref} from 'vue'
import type {UserInfo} from '@/services/JmcomicTypes'
import {JmcomicService} from '@/services/JmcomicService'

const userInfo = ref<UserInfo | null>(null)
const isLoggedIn = computed(() => userInfo.value !== null)

export function useAuth() {
  /** 启动时调用，先检查本地登录态，如无则尝试自动登录（仅 App.vue onMounted 调用） */
  async function initAuth(): Promise<boolean> {
    try {
      const result = await JmcomicService.checkLoginState()
      if (result.loggedIn && result.userInfo) {
        userInfo.value = result.userInfo
        return true
      }
      try {
        const autoResult = await JmcomicService.autoLogin()
        if (autoResult.userInfo) {
          userInfo.value = autoResult.userInfo
          return true
        }
      } catch {
        // 自动登录失败（无凭据或凭据无效）
      }
    } catch {
      // 检查失败视为未登录
    }
    return false
  }

  /** 登录并更新本地状态 */
  async function login(username: string, password: string): Promise<UserInfo> {
    const info = await JmcomicService.login(username, password)
    userInfo.value = info
    return info
  }

  /** 登出并清除本地状态 */
  async function logout(): Promise<void> {
    await JmcomicService.logout()
    userInfo.value = null
  }

  return {userInfo, isLoggedIn, initAuth, login, logout}
}
