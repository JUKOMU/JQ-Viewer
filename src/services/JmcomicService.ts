import {toastController} from '@ionic/vue'

export {JmcomicService} from './jmcomic/JmcomicServiceFacade'
export {getImageUrl} from './jmcomic/imageUrl'

/** 清洗错误消息，将技术性错误替换为用户友好提示 */
export function sanitizeError(error: unknown, fallback: string): string {
  const msg =
    error instanceof Error ? error.message : typeof error === 'string' && error ? error : null
  if (msg) {
    if (/mysql|database|syntax|sql|jdbc|JsonSyntax/i.test(msg)) return '服务器繁忙，请稍后重试'
    return msg
  }
  return fallback
}

/** 显示简短 toast 提示 */
export async function showToast(
  message: string,
  color: 'success' | 'danger' | 'medium' = 'medium',
  duration: number = 1500,
  position: 'top' | 'bottom' | 'middle' = 'bottom',
) {
  const toast = await toastController.create({
    message,
    duration,
    position,
    cssClass: `warm-toast toast-${color}`,
  })
  await toast.present()
  return toast
}
