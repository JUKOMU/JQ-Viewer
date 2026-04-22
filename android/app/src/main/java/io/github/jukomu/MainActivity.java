package io.github.jukomu;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import io.github.jukomu.plugin.JmcomicPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(JmcomicPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
