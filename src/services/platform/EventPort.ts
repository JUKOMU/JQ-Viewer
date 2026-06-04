import type {
  DownloadProgressEvent,
  NetworkProbeEvent,
  RelocationProgress,
} from '../JmcomicTypes'
import type {JmcomicListenerHandle} from '../jmcomic/JmcomicClient'

export type VolumeNavigationDirection = 'up' | 'down'

export interface EventPort {
  addImageReadyListener(
    photoId: string,
    handler: (sortOrder: number) => void,
  ): Promise<JmcomicListenerHandle>
  addDownloadProgressListener(
    handler: (data: DownloadProgressEvent) => void,
  ): Promise<JmcomicListenerHandle>
  addRelocationProgressListener(
    handler: (data: RelocationProgress) => void,
  ): Promise<JmcomicListenerHandle>
  addNetworkProbeListener(handler: (data: NetworkProbeEvent) => void): Promise<JmcomicListenerHandle>
  addLaunchRouteListener(handler: (data: { route: string }) => void): Promise<JmcomicListenerHandle>
  addVolumeNavigationListener(
    handler: (direction: VolumeNavigationDirection) => void,
  ): Promise<JmcomicListenerHandle>
  consumeLaunchRoute(): Promise<{ route?: string }>
}
