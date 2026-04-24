package io.github.jukomu.plugin;

import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.enums.Category;
import io.github.jukomu.jmcomic.api.enums.OrderBy;
import io.github.jukomu.jmcomic.api.enums.SearchMainTag;
import io.github.jukomu.jmcomic.api.enums.TimeOption;
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta;
import io.github.jukomu.jmcomic.api.model.JmSearchPage;
import io.github.jukomu.jmcomic.api.model.SearchQuery;
import io.github.jukomu.jmcomic.core.JmComic;
import io.github.jukomu.jmcomic.core.client.AbstractJmClient;
import io.github.jukomu.jmcomic.core.config.JmConfiguration;

import java.util.List;

/**
 * @author JUKOMU
 * @Description:
 * @Project: jq-viewer
 * @Date: 2026/4/22
 */
@CapacitorPlugin(name = "Jmcomic")
public class JmcomicPlugin extends Plugin {
    private static final String SEARCH_COVER_SIZE = "_3x4";

    @PluginMethod
    public void search(PluginCall call) {
        try {
            JSObject queryObject = call.getObject("query");
            if (queryObject == null) {
                call.reject("query is required");
                return;
            }

            SearchQuery query = buildQuery(queryObject);

            try (JmClient client = createClient(call)) {
                if (client == null) {
                    return;
                }
                call.resolve(toSearchPage(client.search(query), (AbstractJmClient) client));
            }
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    @PluginMethod
    public void categories(PluginCall call) {
        try {
            JSObject queryObject = call.getObject("query");
            if (queryObject == null) {
                call.reject("query is required");
                return;
            }

            SearchQuery query = buildQuery(queryObject);

            try (JmClient client = createClient(call)) {
                if (client == null) {
                    return;
                }
                call.resolve(toSearchPage(client.getCategories(query), (AbstractJmClient) client));
            }
        } catch (Exception e) {
            call.reject(e.getMessage(), e);
        }
    }

    private JmClient createClient(PluginCall call) {
        JmClient client = JmComic.newApiClient(new JmConfiguration.Builder().build());
        if (!(client instanceof AbstractJmClient)) {
            call.reject("unsupported jm client");
            try {
                client.close();
            } catch (Exception ignored) {
            }
            return null;
        }
        return client;
    }

    private SearchQuery buildQuery(JSObject queryObject) {
        String keyword = queryObject.getString("keyword", "");
        String category = queryObject.getString("category", Category.ALL.getValue());
        String orderBy = queryObject.getString("orderBy", OrderBy.LATEST.getValue());
        String time = queryObject.getString("time", TimeOption.ALL.getValue());
        Integer searchMainTagValue = queryObject.getInteger("searchMainTag", SearchMainTag.SITE_SEARCH.getValue());
        Integer page = queryObject.getInteger("page", 1);

        return new SearchQuery.Builder()
                .text(keyword == null ? "" : keyword)
                .category(findCategory(category))
                .orderBy(findOrderBy(orderBy))
                .time(findTimeOption(time))
                .mainTag(findSearchMainTag(searchMainTagValue))
                .page(page == null ? 1 : page)
                .build();
    }

    private JSObject toSearchPage(JmSearchPage page, AbstractJmClient client) {
        JSObject ret = new JSObject();
        ret.put("currentPage", page.getCurrentPage());
        ret.put("totalItems", page.getTotalItems());
        ret.put("totalPages", page.getTotalPages());

        JSArray content = new JSArray();
        List<JmAlbumMeta> items = page.getContent();
        for (JmAlbumMeta item : items) {
            JSObject row = new JSObject();
            row.put("id", item.getId());
            row.put("title", item.getTitle());
            row.put("coverUrl", client.getAlbumCoverUrl(item.getId(), SEARCH_COVER_SIZE));
            row.put("authors", new JSArray(item.getAuthors()));
            row.put("tags", new JSArray(item.getTags()));
            content.put(row);
        }
        ret.put("content", content);
        return ret;
    }

    private Category findCategory(String value) {
        for (Category category : Category.values()) {
            if (category.getValue().equals(value)) {
                return category;
            }
        }
        return Category.ALL;
    }

    private OrderBy findOrderBy(String value) {
        for (OrderBy orderBy : OrderBy.values()) {
            if (orderBy.getValue().equals(value)) {
                return orderBy;
            }
        }
        return OrderBy.LATEST;
    }

    private TimeOption findTimeOption(String value) {
        for (TimeOption timeOption : TimeOption.values()) {
            if (timeOption.getValue().equals(value)) {
                return timeOption;
            }
        }
        return TimeOption.ALL;
    }

    private SearchMainTag findSearchMainTag(Integer value) {
        for (SearchMainTag mainTag : SearchMainTag.values()) {
            if (mainTag.getValue() == value) {
                return mainTag;
            }
        }
        return SearchMainTag.SITE_SEARCH;
    }
}
