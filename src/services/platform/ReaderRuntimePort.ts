export interface ReaderRuntimePort {
  setDisplayMode(mode: string): Promise<{ success: boolean }>
  setScreenOrientation(orientation: string): Promise<{ success: boolean }>
  setBrightness(brightness: number): Promise<{ success: boolean }>
  setKeepScreenOn(enabled: boolean): Promise<{ success: boolean }>
  setFullscreen(enabled: boolean): Promise<{ success: boolean }>
  setVolumeNavigation(enabled: boolean): Promise<{ success: boolean }>
  setState(isActive: boolean, isVertical: boolean): Promise<{ success: boolean }>
}
