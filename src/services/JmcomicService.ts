import {registerPlugin} from '@capacitor/core'
import type {SearchQuery, SearchResult} from './JmcomicTypes'

interface JmcomicPlugin {
    search(options: { query: SearchQuery }): Promise<SearchResult>
    categories(options: { query: SearchQuery }): Promise<SearchResult>
}

const native = registerPlugin<JmcomicPlugin>('Jmcomic')

export const JmcomicService = {
    native,
    search(query: SearchQuery) {
        return native.search({query})
    },
    categories(query: SearchQuery) {
        return native.categories({query})
    },
}
