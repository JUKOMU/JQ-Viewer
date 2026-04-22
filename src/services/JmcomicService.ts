import {registerPlugin} from '@capacitor/core'
import type {GetCategoryListResult} from './JmcomicTypes'

interface JmcomicPlugin {
    getCategoryList(): Promise<GetCategoryListResult>
}

const native = registerPlugin<JmcomicPlugin>('Jmcomic')

export const JmcomicService = {
    getCategoryList() {
        return native.getCategoryList()
    },
}
