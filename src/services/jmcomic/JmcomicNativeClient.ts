import {registerPlugin} from '@capacitor/core'
import type {JmcomicClient} from './JmcomicClient'

export const jmcomicNativeClient = registerPlugin<JmcomicClient>('Jmcomic')
