package io.github.jukomu.bridge.handler;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.view.*;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import io.github.jukomu.feature.settings.SettingsService;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 负责阅读器设置、窗口状态、系统栏安全区和启动路由 Bridge 调用。
 *
 * <p>窗口监听属于当前插件会话，调用 {@link #destroy()} 时恢复并释放相关状态。
 */
public final class ReaderPluginHandler {

    private static volatile String pendingLaunchRoute;

    private final Supplier<Activity> activitySupplier;
    private final Supplier<WebView> webViewSupplier;
    private final Supplier<SettingsService> settingsServiceSupplier;
    private final Consumer<JSObject> volumeKeyConsumer;
    private final Consumer<JSObject> launchRouteConsumer;

    private volatile boolean readerActive;
    private volatile boolean readerVertical = true;
    private View readerInsetsContainer;
    private ViewTreeObserver.OnPreDrawListener readerInsetsPreDrawListener;
    private int readerSafeTop = -1;
    private int readerSafeBottom = -1;
    private Integer readerOriginalCutoutMode;

    public ReaderPluginHandler(Supplier<Activity> activitySupplier,
                               Supplier<WebView> webViewSupplier,
                               Supplier<SettingsService> settingsServiceSupplier,
                               Consumer<JSObject> volumeKeyConsumer,
                               Consumer<JSObject> launchRouteConsumer) {
        this.activitySupplier = activitySupplier;
        this.webViewSupplier = webViewSupplier;
        this.settingsServiceSupplier = settingsServiceSupplier;
        this.volumeKeyConsumer = volumeKeyConsumer;
        this.launchRouteConsumer = launchRouteConsumer;
    }

    /**
     * 保存后续可由前端消费的启动路由。
     */
    public static void setPendingLaunchRoute(String route) {
        pendingLaunchRoute = route;
    }

    /**
     * 保存阅读器的纵向或横向显示模式。
     */
    public void setReaderDisplayMode(PluginCall call) {
        try {
            String mode = call.getString("mode");
            if (mode == null || (!"vertical".equals(mode) && !"horizontal".equals(mode))) {
                call.reject("mode must be vertical or horizontal");
                return;
            }
            settingsService().setReaderDisplayMode(mode);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 保存并应用屏幕方向。
     */
    public void setReaderScreenOrientation(PluginCall call) {
        try {
            String orientation = call.getString("orientation");
            if (orientation == null) {
                call.reject("orientation is required");
                return;
            }
            settingsService().setReaderScreenOrientation(orientation);
            applyScreenOrientation(orientation);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 保存并应用窗口亮度；-1 表示跟随系统。
     */
    public void setReaderBrightness(PluginCall call) {
        try {
            Float brightness = call.getFloat("brightness");
            if (brightness == null) {
                call.reject("brightness is required");
                return;
            }
            settingsService().setReaderBrightness(brightness);
            applyBrightness(brightness);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 保存并应用屏幕常亮开关。
     */
    public void setReaderKeepScreenOn(PluginCall call) {
        try {
            Boolean enabled = call.getBoolean("enabled");
            if (enabled == null) {
                call.reject("enabled is required");
                return;
            }
            settingsService().setReaderKeepScreenOn(enabled);
            applyKeepScreenOn(enabled);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 保存音量键翻页开关。
     */
    public void setReaderVolumeNavigation(PluginCall call) {
        try {
            Boolean enabled = call.getBoolean("enabled");
            if (enabled == null) {
                call.reject("enabled is required");
                return;
            }
            settingsService().setReaderVolumeNavigation(enabled);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 保存阅读结束时自动显示工具栏的开关。
     */
    public void setReaderAutoShowToolbarAtEnd(PluginCall call) {
        try {
            Boolean enabled = call.getBoolean("enabled");
            if (enabled == null) {
                call.reject("enabled is required");
                return;
            }
            settingsService().setReaderAutoShowToolbarAtEnd(enabled);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 显示或隐藏系统栏，并刷新阅读器安全区。
     */
    public void setReaderFullscreen(PluginCall call) {
        try {
            Boolean enabled = call.getBoolean("enabled");
            if (enabled == null) {
                call.reject("enabled is required");
                return;
            }
            applyFullscreen(enabled);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 更新阅读器激活状态和页面方向，并切换 edge-to-edge 布局。
     */
    public void setReaderState(PluginCall call) {
        Boolean active = call.getBoolean("isActive");
        Boolean vertical = call.getBoolean("isVertical");
        if (active == null || vertical == null) {
            call.reject("isActive and isVertical are required");
            return;
        }
        readerActive = active;
        readerVertical = vertical;
        applyReaderEdgeToEdge(active);
        call.resolve(successResult());
    }

    /**
     * 返回并清除待处理的启动路由。
     */
    public void consumeLaunchRoute(PluginCall call) {
        JSObject result = new JSObject();
        String route = pendingLaunchRoute;
        pendingLaunchRoute = null;
        if (route != null && !route.isEmpty()) {
            result.put("route", route);
        }
        call.resolve(result);
    }

    public boolean isReaderActive() {
        return readerActive;
    }

    public boolean isVolumeNavigationEnabled() {
        return settingsService().getReaderVolumeNavigation();
    }

    public boolean isReaderVertical() {
        return readerVertical;
    }

    /**
     * 发布一次音量键方向事件。
     */
    public void notifyVolumeKey(String direction) {
        JSObject event = new JSObject();
        event.put("direction", direction);
        volumeKeyConsumer.accept(event);
    }

    /**
     * 发布一次启动路由事件。
     */
    public void notifyLaunchRoute(String route) {
        JSObject event = new JSObject();
        event.put("route", route);
        launchRouteConsumer.accept(event);
    }

    /**
     * 恢复非阅读状态并释放窗口监听。
     */
    public void destroy() {
        readerActive = false;
        applyReaderEdgeToEdge(false);
    }

    private SettingsService settingsService() {
        return settingsServiceSupplier.get();
    }

    private void applyBrightness(float brightness) {
        Activity activity = activitySupplier.get();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
            attributes.screenBrightness = brightness;
            activity.getWindow().setAttributes(attributes);
        });
    }

    private void applyKeepScreenOn(boolean enabled) {
        Activity activity = activitySupplier.get();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (enabled) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    private void applyFullscreen(boolean enabled) {
        Activity activity = activitySupplier.get();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Window window = activity.getWindow();
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    if (enabled) {
                        controller.hide(WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars());
                        controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    } else {
                        controller.show(WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars());
                    }
                }
            } else {
                View decorView = activity.getWindow().getDecorView();
                if (enabled) {
                    decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                } else {
                    int layoutFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                    decorView.setSystemUiVisibility(readerActive ? layoutFlags : 0);
                }
            }
            refreshReaderContainerInsets();
        });
    }

    private void applyReaderEdgeToEdge(boolean enabled) {
        Activity activity = activitySupplier.get();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            Window window = activity.getWindow();
            // 状态栏隐藏后需要允许内容绘制到短边挖孔区，避免出现黑色留边。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams attributes = window.getAttributes();
                if (enabled) {
                    if (readerOriginalCutoutMode == null) {
                        readerOriginalCutoutMode = attributes.layoutInDisplayCutoutMode;
                    }
                    if (attributes.layoutInDisplayCutoutMode
                        != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                        attributes.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                        window.setAttributes(attributes);
                    }
                } else if (readerOriginalCutoutMode != null) {
                    attributes.layoutInDisplayCutoutMode = readerOriginalCutoutMode;
                    window.setAttributes(attributes);
                    readerOriginalCutoutMode = null;
                }
            }
            WindowCompat.setDecorFitsSystemWindows(window, !enabled);

            if (enabled) {
                attachReaderInsetsOverride();
            } else {
                detachReaderInsetsOverride();
            }
        });
    }

    private void attachReaderInsetsOverride() {
        WebView webView = webViewSupplier.get();
        if (webView == null) return;
        View container = (View) webView.getParent();
        if (container == null) return;
        if (readerInsetsContainer != container) {
            detachReaderInsetsOverride();
            readerInsetsContainer = container;
            // SystemBars 可能重新写入父容器 padding，绘制前清理可防止页面位置跳动。
            readerInsetsPreDrawListener = () -> !updateReaderContainerInsets(container);
            container.getViewTreeObserver().addOnPreDrawListener(readerInsetsPreDrawListener);
        }
        ViewCompat.requestApplyInsets(container);
        updateReaderContainerInsets(container);
    }

    private void refreshReaderContainerInsets() {
        View container = readerInsetsContainer;
        if (!readerActive || container == null) return;
        ViewCompat.requestApplyInsets(container);
    }

    private boolean updateReaderContainerInsets(View container) {
        if (!readerActive || container == null) return false;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(container);
        int imeBottom = 0;
        int safeTop = 0;
        int safeBottom = 0;
        if (insets != null) {
            Insets bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars());
            Insets cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            safeTop = Math.max(bars.top, cutout.top);
            safeBottom = Math.max(bars.bottom, cutout.bottom);
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            }
        }
        boolean paddingChanged = container.getPaddingLeft() != 0
            || container.getPaddingTop() != 0
            || container.getPaddingRight() != 0
            || container.getPaddingBottom() != imeBottom;
        if (paddingChanged) {
            container.setPadding(0, 0, 0, imeBottom);
        }
        updateReaderSafeAreaCss(safeTop, safeBottom);
        return paddingChanged;
    }

    private void updateReaderSafeAreaCss(int top, int bottom) {
        if (top == readerSafeTop && bottom == readerSafeBottom) return;
        readerSafeTop = top;
        readerSafeBottom = bottom;
        WebView webView = webViewSupplier.get();
        Activity activity = activitySupplier.get();
        if (webView == null || activity == null) return;
        float density = activity.getResources().getDisplayMetrics().density;
        // WindowInsets 使用物理像素，WebView CSS 变量使用 CSS 像素。
        String script = "document.documentElement.style.setProperty('--jq-reader-safe-area-top','"
            + (top / density) + "px');"
            + "document.documentElement.style.setProperty('--jq-reader-safe-area-bottom','"
            + (bottom / density) + "px');";
        webView.evaluateJavascript(script, null);
    }

    private void detachReaderInsetsOverride() {
        View container = readerInsetsContainer;
        if (container != null && readerInsetsPreDrawListener != null) {
            ViewTreeObserver observer = container.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(readerInsetsPreDrawListener);
            }
        }
        readerInsetsContainer = null;
        readerInsetsPreDrawListener = null;
        readerSafeTop = -1;
        readerSafeBottom = -1;
        WebView webView = webViewSupplier.get();
        if (webView != null) {
            webView.evaluateJavascript(
                "document.documentElement.style.removeProperty('--jq-reader-safe-area-top');"
                    + "document.documentElement.style.removeProperty('--jq-reader-safe-area-bottom');",
                null);
        }
        if (container != null) {
            ViewCompat.requestApplyInsets(container);
        }
    }

    private void applyScreenOrientation(String orientation) {
        Activity activity = activitySupplier.get();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            switch (orientation) {
                case "portrait":
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    break;
                case "landscape":
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    break;
                default:
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    break;
            }
        });
    }

    private static JSObject successResult() {
        JSObject result = new JSObject();
        result.put("success", true);
        return result;
    }
}
