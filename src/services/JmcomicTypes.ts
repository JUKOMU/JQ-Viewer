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
    authors: string[]
    tags: string[]
}

export interface SearchResult {
    currentPage: number
    totalItems: number
    totalPages: number
    content: SearchResultItem[]
}
