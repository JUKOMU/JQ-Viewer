import type { BackendClient } from './BackendClient'
import type { BackendEvents } from './BackendEvents'
import type { PlatformServices } from './PlatformServices'
import type { ResourceResolver } from './ResourceResolver'

export type RuntimePlatform = 'android' | 'windows' | 'macos' | 'linux'

export interface FrontendRuntime {
  platform: RuntimePlatform
  backend: BackendClient
  events: BackendEvents
  resources: ResourceResolver
  services: PlatformServices
}
