package io.github.jukomu;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.annotation.NonNull;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import io.github.jukomu.bridge.JmcomicPlugin;
import io.github.jukomu.data.ImageCache;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(JmcomicPlugin.class);
        super.onCreate(savedInstanceState);

        WebView webView = this.getBridge().getWebView();
        webView.setWebViewClient(new BridgeWebViewClient(this.getBridge()) {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (ImageCache.isVirtualImageUrl(url)) {
                    WebResourceResponse resp = ImageCache.handleRequest(url);
                    if (resp != null) return resp;
                    return null;
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        JmcomicPlugin plugin = JmcomicPlugin.getInstance();
        if (plugin != null) {
            plugin.handlePermissionResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        JmcomicPlugin plugin = JmcomicPlugin.getInstance();
        if (plugin != null) {
            plugin.handleActivityResult(requestCode, resultCode, data);
        }
    }
}
