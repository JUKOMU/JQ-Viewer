import type { BackendClient } from './BackendClient'
import type { BackendEvents } from './BackendEvents'
import type { PlatformServices } from './PlatformServices'
import type { ResourceResolver } from './ResourceResolver'

/** 当前运行平台。构建入口会显式选择其中一个适配器，未知平台应启动失败而非静默回落。 */
export type RuntimePlatform = 'android' | 'windows' | 'macos' | 'linux'

/**
 * 前端运行时根入口：页面与业务服务依赖的唯一平台抽象。
 * 它将结构化后端调用、事件订阅、资源 URL 与真实平台能力聚合成一个可注入对象，
 * 使上层无需感知 Capacitor、HTTP/SSE 等具体 transport。
 */
export interface FrontendRuntime {
  platform: RuntimePlatform
  backend: BackendClient
  events: BackendEvents
  resources: ResourceResolver
  services: PlatformServices
}
