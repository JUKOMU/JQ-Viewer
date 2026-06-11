import {toastController} from '@ionic/vue'

export {JmcomicService} from './jmcomic/JmcomicServiceFacade'
export {getImageUrl} from './jmcomic/imageUrl'

/** 清洗错误消息，将技术性错误替换为用户友好提示 */
export function sanitizeError(error: unknown, fallback: string): string {
  const msg =
    error instanceof Error ? error.message : typeof error === 'string' && error ? error : null
  if (msg) {
    if (/Missing desktop token/i.test(msg)) return '本机服务凭据缺失，请从桌面应用入口重新打开页面'
    if (/Desktop API failed:\s*401/i.test(msg)) return '本机服务凭据无效，请从桌面应用入口重新打开页面'
    if (/Origin is not allowed/i.test(msg)) return '当前页面来源未被本机服务允许，请从桌面应用入口打开'
    if (/directory picker is not available|Unsupported directory purpose/i.test(msg)) return '当前环境无法打开桌面文件夹选择器'
    if (/Cannot change download directory while downloads are running/i.test(msg)) return '请先暂停或等待下载任务结束，再切换下载目录'
    if (/Failed to fetch|NetworkError|Load failed/i.test(msg)) return '本机服务未连接，请确认桌面应用仍在运行'
    if (/not available in desktop/i.test(msg)) return '此功能暂未在桌面版开放'
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
