import { ref } from 'vue'
import { JmcomicService, showToast } from '@/services/JmcomicService'
import { SettingsStore } from '@/services/SettingsService'
import type { DomainStates } from '@/services/JmcomicTypes'

export type ApiRouteMode = 'auto' | 'manual'

const mode = ref<ApiRouteMode>('auto')
const selectedDomain = ref('')
const currentDomain = ref<string | null>(null)
const domains = ref<DomainStates['domains']>([])
const loading = ref(false)

export function useApiRoute() {
  async function applyRoutePreference(nextMode: ApiRouteMode, domain: string) {
    if (nextMode === 'auto') {
      const current = await JmcomicService.getUsedDomain()
      if (current.domain) {
        await JmcomicService.applyApiRoute({ mode: 'manual', domain: current.domain })
      }
      return JmcomicService.applyApiRoute({ mode: 'auto' })
    }
    return JmcomicService.applyApiRoute({ mode: 'manual', domain })
  }

  async function refresh() {
    mode.value = SettingsStore.getApiRouteMode()
    selectedDomain.value = SettingsStore.getApiRouteDomain()
    const [states, used] = await Promise.all([
      JmcomicService.getDomainStates(),
      JmcomicService.getUsedDomain(),
    ])
    domains.value = states.domains
    currentDomain.value = used.domain || null
  }

  async function applySavedPreference() {
    mode.value = SettingsStore.getApiRouteMode()
    selectedDomain.value = SettingsStore.getApiRouteDomain()
    const result = await applyRoutePreference(mode.value, selectedDomain.value)
    currentDomain.value = result.domain || null
    return currentDomain.value
  }

  async function select(nextMode: ApiRouteMode, domain = '') {
    if (loading.value) return
    loading.value = true
    let statusToast: Awaited<ReturnType<typeof showToast>> | null = null

    const dismissStatusToast = async () => {
      await statusToast?.dismiss()
      statusToast = null
    }

    try {
      statusToast = await showToast('正在切换线路', 'medium')
      const result = await applyRoutePreference(nextMode, domain)
      await JmcomicService.setApiRoutePreference({
        mode: nextMode,
        domain: nextMode === 'manual' ? domain : undefined,
      })
      SettingsStore.setApiRoute(nextMode, nextMode === 'manual' ? domain : '')
      mode.value = nextMode
      selectedDomain.value = nextMode === 'manual' ? domain : ''
      currentDomain.value = result.domain || null
      await refresh().catch(() => undefined)
      await dismissStatusToast()
    } catch (error) {
      await dismissStatusToast()
      await showToast('线路切换失败', 'danger')
      throw error
    } finally {
      loading.value = false
    }
    await showToast('线路切换成功', 'success')
  }

  return {
    mode,
    selectedDomain,
    currentDomain,
    domains,
    loading,
    refresh,
    applySavedPreference,
    select,
  }
}
