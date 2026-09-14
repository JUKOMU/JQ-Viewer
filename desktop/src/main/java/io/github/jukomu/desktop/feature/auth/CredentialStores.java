package io.github.jukomu.desktop.feature.auth;

import com.microsoft.credentialstorage.SecretStore;
import com.microsoft.credentialstorage.StorageProvider;
import com.microsoft.credentialstorage.model.StoredCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 创建由操作系统凭据管理器承载的登录凭据存储。 */
public final class CredentialStores {
    private static final Logger LOGGER = LoggerFactory.getLogger(CredentialStores.class);
    private static final String CREDENTIAL_KEY = "io.github.jukomu.JQ-Viewer.login";
    private static final CredentialStore UNAVAILABLE = new CredentialStore() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public LoginCredentials load() {
            throw new IllegalStateException("操作系统安全凭据存储不可用");
        }

        @Override
        public void save(String username, String password) {
            throw new IllegalStateException("操作系统安全凭据存储不可用");
        }

        @Override
        public void clear() {
            throw new IllegalStateException("操作系统安全凭据存储不可用");
        }
    };

    private CredentialStores() {
    }

    public static CredentialStore system() {
        try {
            SecretStore<StoredCredential> store = StorageProvider.getCredentialStorage(
                    true, StorageProvider.SecureOption.REQUIRED);
            if (store == null || !store.isSecure()) {
                LOGGER.warn("操作系统安全凭据存储不可用，自动登录已禁用");
                return UNAVAILABLE;
            }
            return new SystemCredentialStore(store);
        } catch (Throwable failure) {
            rethrowFatal(failure);
            LOGGER.warn("无法初始化操作系统安全凭据存储，自动登录已禁用", failure);
            return UNAVAILABLE;
        }
    }

    public static CredentialStore unavailable() {
        return UNAVAILABLE;
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
    }

    private static final class SystemCredentialStore implements CredentialStore {
        private final SecretStore<StoredCredential> store;
        private volatile boolean available = true;

        private SystemCredentialStore(SecretStore<StoredCredential> store) {
            this.store = store;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public LoginCredentials load() {
            requireAvailable();
            try {
                StoredCredential stored = store.get(CREDENTIAL_KEY);
                if (stored == null) return null;
                try {
                    return new LoginCredentials(stored.getUsername(), new String(stored.getPassword()));
                } finally {
                    stored.clear();
                }
            } catch (Throwable failure) {
                throw disable(failure, "无法读取操作系统安全凭据");
            }
        }

        @Override
        public void save(String username, String password) {
            requireAvailable();
            StoredCredential credential = new StoredCredential(username, password.toCharArray());
            try {
                if (!store.add(CREDENTIAL_KEY, credential)) {
                    throw new IllegalStateException("操作系统拒绝保存安全凭据");
                }
            } catch (Throwable failure) {
                throw disable(failure, "无法保存操作系统安全凭据");
            } finally {
                credential.clear();
            }
        }

        @Override
        public void clear() {
            requireAvailable();
            try {
                StoredCredential stored = store.get(CREDENTIAL_KEY);
                if (stored == null) return;
                stored.clear();
                if (!store.delete(CREDENTIAL_KEY)) {
                    throw new IllegalStateException("操作系统拒绝删除安全凭据");
                }
            } catch (Throwable failure) {
                throw disable(failure, "无法删除操作系统安全凭据");
            }
        }

        private void requireAvailable() {
            if (!available) throw new IllegalStateException("操作系统安全凭据存储不可用");
        }

        private IllegalStateException disable(Throwable failure, String message) {
            rethrowFatal(failure);
            available = false;
            return failure instanceof IllegalStateException exception
                    ? exception
                    : new IllegalStateException(message, failure);
        }
    }
}
