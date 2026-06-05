import {isDesktopRuntime} from './runtime'
import {androidCapabilities} from './androidCapabilities'
import {desktopCapabilities} from './desktopCapabilities'

export const platformCapabilities = isDesktopRuntime ? desktopCapabilities : androidCapabilities
