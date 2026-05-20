import { ref, computed } from 'vue'
import type { UserInfo } from '@/services/JmcomicTypes'
import { JmcomicService } from '@/services/JmcomicService'

const userInfo = ref<UserInfo | null>(null)
const isLoggedIn = computed(() => userInfo.value !== null)

export function useAuth() {
    /** 启动时调用，恢复登录态并填充 userInfo（仅 App.vue onMounted 调用） */
    async function initAuth(): Promise<void> {
        try {
            const result = await JmcomicService.checkLoginState()
            if (result.loggedIn && result.userInfo) {
                userInfo.value = result.userInfo
            }
        } catch {
            // 检查失败视为未登录，保持 userInfo 为 null
        }
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

    return { userInfo, isLoggedIn, initAuth, login, logout }
}
