package io.github.jukomu.desktop.feature.auth;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.exception.NetworkException;
import io.github.jukomu.jmcomic.api.exception.ResponseException;
import io.github.jukomu.jmcomic.api.model.JmUserInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {
    @Test
    void restoresSessionFromSavedCredentialsAfterRestart() {
        MemoryCredentialStore credentials = new MemoryCredentialStore(true);
        AuthService firstProcess = new AuthService(client(null), credentials);
        firstProcess.login("alice", "secret");

        AuthService restartedProcess = new AuthService(client(null), credentials);

        assertFalse(restartedProcess.state().loggedIn());
        assertTrue(restartedProcess.autoLogin().success());
        assertTrue(restartedProcess.state().loggedIn());
        assertEquals("alice", restartedProcess.state().username());
    }

    @Test
    void keepsManualLoginAvailableWhenSecureStorageIsUnavailable() {
        MemoryCredentialStore credentials = new MemoryCredentialStore(false);
        AuthService service = new AuthService(client(null), credentials);

        assertEquals("alice", service.login("alice", "secret").username());
        ApiException failure = assertThrows(ApiException.class, service::autoLogin);
        assertEquals("unavailable", failure.code());
        assertNull(credentials.loadDirectly());
    }

    @Test
    void clearsOnlyConfirmedInvalidCredentials() {
        MemoryCredentialStore credentials = new MemoryCredentialStore(true);
        credentials.save("alice", "secret");

        AuthService networkFailure = new AuthService(
                client(new NetworkException("network unavailable")), credentials);
        ApiException network = assertThrows(ApiException.class, networkFailure::autoLogin);
        assertEquals("network", network.code());
        assertEquals("alice", credentials.loadDirectly().username());

        AuthService authFailure = new AuthService(
                client(new ResponseException("unauthorized", 401)), credentials);
        ApiException denied = assertThrows(ApiException.class, authFailure::autoLogin);
        assertEquals("permission-denied", denied.code());
        assertNull(credentials.loadDirectly());

        credentials.save("alice", "secret");
        AuthService serverFailure = new AuthService(
                client(new ResponseException("service unavailable", 503)), credentials);
        ApiException unavailable = assertThrows(ApiException.class, serverFailure::autoLogin);
        assertEquals("permission-denied", unavailable.code());
        assertEquals("alice", credentials.loadDirectly().username());
    }

    @Test
    void logoutClearsSessionAndSavedCredentials() {
        MemoryCredentialStore credentials = new MemoryCredentialStore(true);
        AuthService service = new AuthService(client(null), credentials);
        service.login("alice", "secret");

        service.logout();

        assertFalse(service.state().loggedIn());
        assertNull(credentials.loadDirectly());
    }

    @Test
    void logoutClearsSavedCredentialsWhenOnlineClientIsUnavailable() {
        MemoryCredentialStore credentials = new MemoryCredentialStore(true);
        credentials.save("alice", "secret");
        AuthService service = new AuthService(() -> null, credentials);

        service.logout();

        assertNull(credentials.loadDirectly());
    }

    @Test
    void logoutClearsLocalStateWhenRemoteLogoutFails() {
        MemoryCredentialStore networkCredentials = new MemoryCredentialStore(true);
        AuthService networkFailure = new AuthService(
                client(null, new NetworkException("network unavailable")), networkCredentials);
        networkFailure.login("alice", "secret");

        networkFailure.logout();

        assertFalse(networkFailure.state().loggedIn());
        assertNull(networkCredentials.loadDirectly());

        MemoryCredentialStore responseCredentials = new MemoryCredentialStore(true);
        AuthService responseFailure = new AuthService(
                client(null, new ResponseException("logout rejected", 403)), responseCredentials);
        responseFailure.login("alice", "secret");

        responseFailure.logout();

        assertFalse(responseFailure.state().loggedIn());
        assertNull(responseCredentials.loadDirectly());
    }

    @Test
    void logoutReturnsBeforeRemoteRequestRuns() {
        MemoryCredentialStore credentials = new MemoryCredentialStore(true);
        Queue<Runnable> remoteTasks = new ArrayDeque<>();
        AtomicBoolean remoteLogoutCalled = new AtomicBoolean();
        JmClient client = client(null, null, () -> remoteLogoutCalled.set(true));
        AuthService service = new AuthService(() -> client, credentials, remoteTasks::add);
        service.login("alice", "secret");

        service.logout();

        assertFalse(service.state().loggedIn());
        assertNull(credentials.loadDirectly());
        assertFalse(remoteLogoutCalled.get());
        assertEquals(1, remoteTasks.size());

        remoteTasks.remove().run();
        assertTrue(remoteLogoutCalled.get());
    }

    @Test
    void delayedRemoteLogoutDoesNotTerminateNewLoginSession() {
        MemoryCredentialStore credentials = new MemoryCredentialStore(true);
        Queue<Runnable> remoteTasks = new ArrayDeque<>();
        AtomicBoolean remoteLogoutCalled = new AtomicBoolean();
        JmClient client = client(null, null, () -> remoteLogoutCalled.set(true));
        AuthService service = new AuthService(() -> client, credentials, remoteTasks::add);
        service.login("alice", "old-secret");
        service.logout();

        service.login("alice", "new-secret");
        remoteTasks.remove().run();

        assertFalse(remoteLogoutCalled.get());
        assertTrue(service.state().loggedIn());
        assertEquals("new-secret", credentials.loadDirectly().password());
    }

    private static JmClient client(RuntimeException loginFailure) {
        return client(loginFailure, null);
    }

    private static JmClient client(RuntimeException loginFailure, RuntimeException logoutFailure) {
        return client(loginFailure, logoutFailure, () -> {
        });
    }

    private static JmClient client(
            RuntimeException loginFailure,
            RuntimeException logoutFailure,
            Runnable logoutAction
    ) {
        return (JmClient) Proxy.newProxyInstance(
                JmClient.class.getClassLoader(),
                new Class<?>[]{JmClient.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "AuthTestJmClient";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> throw new AssertionError(method.getName());
                        };
                    }
                    return switch (method.getName()) {
                        case "login" -> {
                            if (loginFailure != null) throw loginFailure;
                            yield userInfo();
                        }
                        case "logout" -> {
                            logoutAction.run();
                            if (logoutFailure != null) throw logoutFailure;
                            yield null;
                        }
                        default -> throw new AssertionError("未预期的客户端调用: " + method.getName());
                    };
                }
        );
    }

    private static JmUserInfo userInfo() {
        return new JmUserInfo(
                "user-1", "alice", "alice@example.invalid", true, "avatar.jpg", "Alice",
                "", "", 10, 2, 3, "Level 3", 100, 50, 0.5, 100);
    }

    private static final class MemoryCredentialStore implements CredentialStore {
        private final boolean available;
        private LoginCredentials credentials;

        private MemoryCredentialStore(boolean available) {
            this.available = available;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public LoginCredentials load() {
            if (!available) throw new IllegalStateException("unavailable");
            return credentials;
        }

        @Override
        public void save(String username, String password) {
            if (!available) throw new IllegalStateException("unavailable");
            credentials = new LoginCredentials(username, password);
        }

        @Override
        public void clear() {
            if (!available) throw new IllegalStateException("unavailable");
            credentials = null;
        }

        private LoginCredentials loadDirectly() {
            return credentials;
        }
    }
}
