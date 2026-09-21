import { registerPlugin } from '@capacitor/core'
import type { JmcomicClient } from './JmcomicClient'

const onlineClient = registerPlugin<JmcomicClient>('Jmcomic')
const localClient = registerPlugin<JmcomicClient>('JqViewer')

const ONLINE_METHODS = new Set<keyof JmcomicClient>([
  'search',
  'categories',
  'getAlbum',
  'getPhoto',
  'getComments',
  'getFavorites',
  'toggleAlbumLike',
  'toggleAlbumFavorite',
  'manageFavoriteFolder',
  'getDomainStates',
  'reprobeDomains',
  'measureLatency',
  'getInitStatus',
  'getClientState',
  'login',
  'logout',
  'checkLoginState',
  'autoLogin',
  'getUserProfile',
])

const ONLINE_EVENTS = new Set(['networkProbe', 'clientStateChanged'])

/** Combines the online JMComic plugin and the always-on local application plugin. */
export const jmcomicNativeClient = new Proxy({} as JmcomicClient, {
  get(_target, property: string | symbol) {
    if (typeof property !== 'string') return undefined
    if (property === 'addListener') {
      return (event: string, handler: never) =>
        (ONLINE_EVENTS.has(event) ? onlineClient : localClient).addListener(event as never, handler)
    }
    const owner = ONLINE_METHODS.has(property as keyof JmcomicClient) ? onlineClient : localClient
    const value = owner[property as keyof JmcomicClient]
    return typeof value === 'function' ? value.bind(owner) : value
  },
})
