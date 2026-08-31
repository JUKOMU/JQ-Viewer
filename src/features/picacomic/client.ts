import { registerPlugin } from '@capacitor/core'
import type { PicacomicPluginClient } from './types'

export const picacomicNativeClient = registerPlugin<PicacomicPluginClient>('Picacomic')
