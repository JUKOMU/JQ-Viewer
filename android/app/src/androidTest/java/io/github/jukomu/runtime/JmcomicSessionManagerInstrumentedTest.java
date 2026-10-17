package io.github.jukomu.runtime;

import android.net.ProxyInfo;
import android.net.Uri;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JmcomicSessionManagerInstrumentedTest {
    @Test
    public void normalizesDirectAndPacProxyFingerprints() {
        assertEquals("direct", JmcomicSessionManager.proxyFingerprint(null));
        assertEquals("http:proxy.example:8080",
            JmcomicSessionManager.proxyFingerprint(
                ProxyInfo.buildDirectProxy("proxy.example", 8080)));
        assertEquals("pac:https://proxy.example/config.pac",
            JmcomicSessionManager.proxyFingerprint(
                ProxyInfo.buildPacProxy(Uri.parse("https://proxy.example/config.pac"))));
    }
}
