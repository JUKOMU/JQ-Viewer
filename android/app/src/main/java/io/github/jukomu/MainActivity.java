package io.github.jukomu;

import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.annotation.NonNull;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import io.github.jukomu.bridge.JmcomicPlugin;
import io.github.jukomu.data.ImageCache;
import io.github.jukomu.data.PdfServer;

public class MainActivity extends BridgeActivity {
    public static final String ACTION_OPEN_ROUTE = "io.github.jukomu.OPEN_ROUTE";
    public static final String EXTRA_ROUTE = "io.github.jukomu.extra.ROUTE";
    public static final String ROUTE_DOWNLOAD = "/download";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(JmcomicPlugin.class);
        captureLaunchRoute(getIntent());
        super.onCreate(savedInstanceState);

        WebView webView = this.getBridge().getWebView();
        webView.setWebViewClient(new BridgeWebViewClient(this.getBridge()) {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (PdfServer.isPdfUrl(url)) {
                    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                        return PdfServer.optionsResponse();
                    }
                    return PdfServer.handleRequest(url, getApplicationContext());
                }
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureLaunchRoute(intent);
    }

    private void captureLaunchRoute(Intent intent) {
        if (intent == null) return;
        String route = intent.getStringExtra(EXTRA_ROUTE);
        if (route == null || route.isEmpty()) return;
        JmcomicPlugin.setPendingLaunchRoute(route);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        JmcomicPlugin plugin = JmcomicPlugin.getInstance();
        if (plugin != null) plugin.onMemoryPressure(level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        JmcomicPlugin plugin = JmcomicPlugin.getInstance();
        if (plugin != null) plugin.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                JmcomicPlugin plugin = JmcomicPlugin.getInstance();
                if (plugin != null && plugin.isReaderActive() && plugin.isVolumeNavigationEnabled()) {
                    plugin.notifyVolumeKey(code == KeyEvent.KEYCODE_VOLUME_UP ? "up" : "down");
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
