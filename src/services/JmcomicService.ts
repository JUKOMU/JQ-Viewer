import {registerPlugin} from '@capacitor/core'
import type {
    AlbumDetail,
    CommentList,
    ForumQuery,
    ImageInfo,
    PhotoDetail,
    SearchQuery,
    SearchResult,
} from './JmcomicTypes'

interface JmcomicPlugin {
    search(options: { query: SearchQuery }): Promise<SearchResult>
    categories(options: { query: SearchQuery }): Promise<SearchResult>
    getAlbum(options: { id: string }): Promise<AlbumDetail>
    getPhoto(options: { id: string }): Promise<PhotoDetail>
    getComments(options: ForumQuery): Promise<CommentList>
    toggleAlbumLike(options: { id: string }): Promise<{ success: boolean }>
    toggleAlbumFavorite(options: { id: string; folderId: string }): Promise<{ success: boolean }>
    decryptImageUrl(options: ImageInfo): Promise<{ dataUrl: string }>
    decryptImageUrls(options: { images: ImageInfo[] }): Promise<{ results: { sortOrder: number; dataUrl: string }[] }>
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
    getAlbum(id: string) {
        return native.getAlbum({id})
    },
    getPhoto(id: string) {
        return native.getPhoto({id})
    },
    getComments(query: ForumQuery) {
        return native.getComments(query)
    },
    toggleAlbumLike(id: string) {
        return native.toggleAlbumLike({id})
    },
    toggleAlbumFavorite(id: string, folderId: string = '0') {
        return native.toggleAlbumFavorite({id, folderId})
    },
    decryptImageUrl(image: ImageInfo) {
        return native.decryptImageUrl(image)
    },
    decryptImageUrls(images: ImageInfo[]) {
        return native.decryptImageUrls({ images })
    },
}
