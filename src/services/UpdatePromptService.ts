import { modalController } from '@ionic/vue'
import UpdatePrompt from '@/components/update/UpdatePrompt.vue'
import type { UpdateManifest } from './JmcomicTypes'

type UpdatePromptOptions = {
  cancelText?: string
  confirmText?: string
}

/** 展示统一的更新说明弹窗，并返回用户是否确认继续。 */
export async function presentUpdatePrompt(
  manifest: UpdateManifest,
  options: UpdatePromptOptions = {},
): Promise<boolean> {
  const modal = await modalController.create({
    component: UpdatePrompt,
    componentProps: {
      manifest,
      ...options,
    },
    cssClass: 'update-prompt-modal',
    backdropDismiss: true,
  })
  await modal.present()
  const dismissed = await modal.onDidDismiss()
  return dismissed.role === 'confirm'
}
