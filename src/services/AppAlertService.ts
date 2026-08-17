import { modalController } from '@ionic/vue'
import type { AlertButton, AlertInput, AlertOptions } from '@ionic/vue'
import AppAlert from '@/components/common/AppAlert.vue'

export type AppAlertLayout = 'confirm' | 'input' | 'choice' | 'message'
export type AppAlertTone = 'default' | 'danger' | 'info'

export interface AppAlertOptions extends AlertOptions {
  layout?: AppAlertLayout
  tone?: AppAlertTone
}

export interface AppAlertRuntime {
  isButtonHandled: () => boolean
  setButtonHandled: (handled: boolean) => void
}

let alertSequence = 0

function normalizedButtons(buttons: AppAlertOptions['buttons']): AlertButton[] {
  return (buttons ?? []).map((button) =>
    typeof button === 'string'
      ? { text: button, role: button.toLowerCase() === 'cancel' ? 'cancel' : undefined }
      : button,
  )
}

function inferLayout(options: AppAlertOptions): AppAlertLayout {
  if (options.layout) return options.layout

  const inputs = options.inputs ?? []
  if (inputs.some((input) => input.type === 'textarea')) return 'message'
  if (inputs.some((input) => input.type === 'radio')) return 'choice'
  if (inputs.length > 0) return 'input'

  const message = typeof options.message === 'string' ? options.message : ''
  return message.length > 120 ? 'message' : 'confirm'
}

function inferTone(options: AppAlertOptions, buttons: AlertButton[]): AppAlertTone {
  if (options.tone) return options.tone
  return buttons.some((button) => button.role === 'destructive') ? 'danger' : 'default'
}

function cssClasses(value: string | string[] | undefined): string[] {
  if (!value) return []
  return Array.isArray(value) ? value : [value]
}

function isCancelDismiss(role: string | undefined): boolean {
  return role == null || role === 'backdrop' || role === 'cancel' || role === 'gesture'
}

export async function createAppAlert(options: AppAlertOptions): Promise<HTMLIonModalElement> {
  const buttons = normalizedButtons(options.buttons)
  const layout = inferLayout(options)
  const tone = inferTone(options, buttons)
  const modalId = options.id ?? `app-alert-${++alertSequence}`
  let buttonHandled = false
  const runtime: AppAlertRuntime = {
    isButtonHandled: () => buttonHandled,
    setButtonHandled: (handled) => {
      buttonHandled = handled
    },
  }
  const normalizedOptions: AppAlertOptions = {
    ...options,
    buttons,
    layout,
    tone,
  }

  const modal = await modalController.create({
    id: modalId,
    component: AppAlert,
    componentProps: {
      options: normalizedOptions,
      modalId,
      runtime,
    },
    cssClass: [
      'app-alert-modal',
      `app-alert-modal--${layout}`,
      `app-alert-modal--${tone}`,
      ...cssClasses(options.cssClass),
    ],
    backdropDismiss: options.backdropDismiss ?? true,
    keyboardClose: options.keyboardClose ?? true,
    animated: options.animated ?? true,
  })

  void modal.onWillDismiss().then(async ({ role }) => {
    if (runtime.isButtonHandled() || !isCancelDismiss(role)) return
    const cancelButton = buttons.find((button) => button.role === 'cancel')
    await cancelButton?.handler?.(undefined)
  })

  return modal
}

export type { AlertButton, AlertInput }
