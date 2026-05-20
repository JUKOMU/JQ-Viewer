package io.github.jukomu;

import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import io.github.jukomu.plugin.ImageRegistry;
import io.github.jukomu.plugin.JmcomicPlugin;

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
                if (ImageRegistry.isVirtualImageUrl(url)) {
                    WebResourceResponse resp = ImageRegistry.handleRequest(url);
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
}
