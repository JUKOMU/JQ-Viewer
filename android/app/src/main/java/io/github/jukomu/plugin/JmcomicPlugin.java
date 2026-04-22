package io.github.jukomu.plugin;

import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import io.github.jukomu.jmcomic.api.enums.Category;

/**
 * @author JUKOMU
 * @Description:
 * @Project: jq-viewer
 * @Date: 2026/4/22
 */
@CapacitorPlugin(name = "Jmcomic")
public class JmcomicPlugin extends Plugin {

    @PluginMethod
    public void getCategoryList(PluginCall call) {
        JSArray categoryList = new JSArray();

        for (Category category : Category.values()) {
            JSObject item = new JSObject();
            item.put("value", category.getValue());
            item.put("description", category.getDescription());
            categoryList.put(item);
        }

        JSObject ret = new JSObject();
        ret.put("categoryList", categoryList);
        call.resolve(ret);
    }
}
