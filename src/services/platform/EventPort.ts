import type {
  DownloadProgressEvent,
  NetworkProbeEvent,
  RelocationProgress,
} from '../JmcomicTypes'
import type {JmcomicListenerHandle} from '../jmcomic/JmcomicClient'

export type VolumeNavigationDirection = 'up' | 'down'
export type PlatformListenerHandle = JmcomicListenerHandle

export interface EventPort {
  addImageReadyListener(
    photoId: string,
    handler: (sortOrder: number) => void,
  ): Promise<PlatformListenerHandle>
  addDownloadProgressListener(
    handler: (data: DownloadProgressEvent) => void,
  ): Promise<PlatformListenerHandle>
  addRelocationProgressListener(
    handler: (data: RelocationProgress) => void,
  ): Promise<PlatformListenerHandle>
  addNetworkProbeListener(handler: (data: NetworkProbeEvent) => void): Promise<PlatformListenerHandle>
  addLaunchRouteListener(handler: (data: { route: string }) => void): Promise<PlatformListenerHandle>
  addVolumeNavigationListener(
    handler: (direction: VolumeNavigationDirection) => void,
  ): Promise<PlatformListenerHandle>
  consumeLaunchRoute(): Promise<{ route?: string }>
}
