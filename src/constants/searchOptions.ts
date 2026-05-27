export interface SearchOption<T> {
  value: T
  label: string
}

export const SEARCH_MAIN_TAG_OPTIONS: SearchOption<number>[] = [
  {value: 0, label: '站内搜索'},
  {value: 1, label: '作品'},
  {value: 2, label: '作者'},
  {value: 3, label: '标签'},
  {value: 4, label: '登场人物'},
]

export const ORDER_BY_OPTIONS: SearchOption<string>[] = [
  {value: 'mr', label: '最新'},
  {value: 'mv', label: '最多观看'},
  {value: 'mp', label: '图片最多'},
  {value: 'tf', label: '最多喜欢'},
]

export const TIME_OPTIONS: SearchOption<string>[] = [
  {value: 'a', label: '全部时间'},
  {value: 't', label: '今日'},
  {value: 'w', label: '本周'},
  {value: 'm', label: '本月'},
]

export const CATEGORY_OPTIONS: SearchOption<string>[] = [
  {value: 'doujin', label: '同人'},
  {value: 'single', label: '单本'},
  {value: 'short', label: '短篇'},
  {value: 'another', label: '其他'},
  {value: 'hanman', label: '韩漫'},
  {value: 'meiman', label: '美漫'},
  {value: 'doujin_cosplay', label: 'cosplay'},
  {value: '3D', label: '3D'},
]
