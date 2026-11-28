package com.sdv.platform.web.notifications;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.vault.VaultClient;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Task 0081: the secrets of notifications (the mail password or Microsoft 365 credential, Teams and Slack webhook
 * addresses, the approval link key) from the key service's KV store: {@code secret/data/platform/vero/<reference>} (task 0102), key
 * {@code value}, which Vero's login may read (policy of Setup-SDV-VaultTransit.ps1). Read at every send and never
 * kept. Nothing in the KV store under the reference: empty (a permanent failure, SECRET_NOT_FOUND). The key service
 * away, or not set up in this install: an exception (temporary, SECRET_UNAVAILABLE). The reference and the value never
 * go into a log here.
 * <p>
 * Task 0102 (VSEC-205): Vero's own place is {@code secret/platform/vero/<reference>}; its login may read nothing else once
 * the policy is narrowed. A secret of an install from before is still found at its old place
 * {@code secret/platform/<reference>} while the login may read it, with one WARN (never the reference or the value)
 * saying to move it.
 */
public class VaultSecrets implements SecretResolver {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VaultSecrets.class);
    public static final String MOUNT = "secret";
    /** Task 0102: Vero's own place. */
    public static final String PREFIX = "platform/vero/";
    /** Before task 0102: read only while Vero's login still may, to let an install move its secrets. */
    public static final String OLD_PREFIX = "platform/";
    public static final String KEY = "value";
    static final String MOVE_IT = "A notification secret was found at its old place in the key service (secret/platform/<path>). "
            + "Move it under secret/platform/vero/<path> (runbook, \"Keys for a customer\", narrowing the login).";

    /** One read of the KV store: the value under {@link #KEY}, null when it has none; a CryptoException when it can't. */
    interface Reader {
        String read(String mount, String path);
    }

    private final Supplier<Reader> reader;
    private final java.util.concurrent.atomic.AtomicBoolean warned = new java.util.concurrent.atomic.AtomicBoolean();

    public VaultSecrets(Supplier<VaultClient> vault) {
        this.reader = () -> {
            VaultClient client = vault.get();
            return client == null ? null : (mount, path) -> client.readSecret(mount, path).get(KEY);
        };
    }

    VaultSecrets(Reader reader) {
        this.reader = () -> reader;
    }

    @Override
    public Optional<Secret> resolve(SecretRef ref) {
        Reader r = reader.get();
        if (r == null) throw new IllegalStateException("No key service in this install");
        Optional<Secret> found = read(r, PREFIX + ref.value(), false);
        if (found.isPresent()) return found;
        Optional<Secret> old = read(r, OLD_PREFIX + ref.value(), true);
        if (old.isPresent() && warned.compareAndSet(false, true)) log.warn(MOVE_IT);
        return old;
    }

    /** Missing is empty; at the old place, so is a read the narrowed login may not do. Anything else: the key service is away. */
    private static Optional<Secret> read(Reader r, String path, boolean old) {
        try {
            String value = r.read(MOUNT, path);
            return value == null || value.isEmpty() ? Optional.empty() : Optional.of(Secret.of(value));
        } catch (CryptoException ex) {
            if (ex.code() == CryptoError.CRYPTO_SECRET_MISSING) return Optional.empty();
            if (old && ex.code() == CryptoError.CRYPTO_PERMISSION_DENIED) return Optional.empty();
            throw new IllegalStateException("Key service did not answer: " + ex.code());
        }
    }
}
