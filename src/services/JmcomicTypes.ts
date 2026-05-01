export interface SearchQuery {
    keyword?: string
    category?: string
    orderBy: string
    time: string
    searchMainTag: number
    page?: number
}

export interface SearchResultItem {
    id: string
    title: string
    coverUrl: string
    authors: string[]
    tags: string[]
}

export interface SearchResult {
    currentPage: number
    totalItems: number
    totalPages: number
    content: SearchResultItem[]
}

// --- 详情页类型 ---

export interface CategoryMeta {
    id: string
    title: string
}

export interface PhotoMeta {
    id: string
    title: string
    sortOrder: number
}

export interface ImageInfo {
    photoId: string
    scrambleId: string
    filename: string
    url: string
    queryParams: string
    sortOrder: number
}

export interface PhotoDetail {
    id: string
    title: string
    albumId: string
    sortOrder: number
    author: string
    tags: string[]
    images: ImageInfo[]
}

export interface AlbumDetail {
    id: string
    title: string
    description: string
    addTime: string
    pageCount: number
    likes: string
    views: string
    commentCount: number
    image: string
    category: CategoryMeta | null
    subCategory: CategoryMeta | null
    authors: string[]
    works: string[]
    actors: string[]
    tags: string[]
    relatedAlbums: AlbumMeta[]
    photoMetas: PhotoMeta[]
    seriesId: string
    isFavorite: boolean
    isLiked: boolean
    price: string
    purchased: string
}

export interface AlbumMeta {
    id: string
    title: string
    coverUrl: string
    authors: string[]
    tags: string[]
    description: string
    image: string
    category: CategoryMeta | null
    subCategory: CategoryMeta | null
}

export interface CommentItem {
    commentId: string
    userId: string
    username: string
    nickname: string
    content: string
    postDate: string
    photo: string
    expinfo: string
    aid: string
    name: string
    likes: number
    voteUp: number
    voteDown: number
    replys: CommentItem[]
}

export interface CommentList {
    total: number
    list: CommentItem[]
}

export interface ForumQuery {
    albumId: string
    page: number
}
