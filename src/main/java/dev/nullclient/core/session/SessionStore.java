package dev.nullclient.core.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Persists profiles and routes. With a master password set, the whole store (including proxy
 * credentials) is encrypted at rest: PBKDF2-HMAC-SHA256 key derivation, AES-256-GCM with a fresh IV per save.
 * The password itself is never stored; a wrong password fails GCM authentication.
 */
public final class SessionStore {
    public enum VaultState { PLAIN, LOCKED, UNLOCKED }

    public static final class Data {
        public int version = 1;
        public List<Profile> profiles = new ArrayList<>();
        public List<Route> routes = new ArrayList<>();
        public String activeProfileId;
    }

    private static final Logger LOG = LoggerFactory.getLogger("NullClient/Session");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RNG = new SecureRandom();
    private static final int ITERATIONS = 310_000;
    private static final byte[] AAD = "nullclient-session-v1".getBytes(StandardCharsets.UTF_8);

    private final Path plainFile;
    private final Path vaultFile;

    private Data data = new Data();
    private VaultState state = VaultState.PLAIN;
    private SecretKey key;
    private byte[] salt;
    private int iterations = ITERATIONS;

    public SessionStore(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create " + dir, e);
        }
        this.plainFile = dir.resolve("session.json");
        this.vaultFile = dir.resolve("session.vault");
    }

    public synchronized Data data() {
        return data;
    }

    public synchronized VaultState state() {
        return state;
    }

    public synchronized boolean isLocked() {
        return state == VaultState.LOCKED;
    }

    public synchronized boolean isProtected() {
        return state != VaultState.PLAIN;
    }

    public synchronized void load() {
        if (Files.exists(vaultFile)) {
            state = VaultState.LOCKED;
            data = new Data();
            return;
        }
        state = VaultState.PLAIN;
        data = new Data();
        if (Files.exists(plainFile)) {
            try (Reader r = Files.newBufferedReader(plainFile)) {
                Data d = GSON.fromJson(r, Data.class);
                if (d != null) {
                    data = d;
                }
            } catch (IOException | JsonParseException e) {
                LOG.error("Failed to read {}", plainFile, e);
            }
        }
    }

    public synchronized boolean unlock(char[] password) {
        if (state != VaultState.LOCKED) {
            return true;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(vaultFile)).getAsJsonObject();
            byte[] s = Base64.getDecoder().decode(o.get("salt").getAsString());
            int it = o.get("iter").getAsInt();
            byte[] iv = Base64.getDecoder().decode(o.get("iv").getAsString());
            byte[] ct = Base64.getDecoder().decode(o.get("ct").getAsString());
            SecretKey k = derive(password, s, it);
            byte[] plain = crypt(Cipher.DECRYPT_MODE, k, iv, ct);
            Data d = GSON.fromJson(new String(plain, StandardCharsets.UTF_8), Data.class);
            Arrays.fill(plain, (byte) 0);
            this.salt = s;
            this.iterations = it;
            this.key = k;
            this.data = d == null ? new Data() : d;
            this.state = VaultState.UNLOCKED;
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            LOG.error("Failed to read vault", e);
            return false;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public synchronized void setMasterPassword(char[] password) throws GeneralSecurityException {
        if (state == VaultState.LOCKED) {
            throw new IllegalStateException("Vault is locked");
        }
        try {
            byte[] s = new byte[16];
            RNG.nextBytes(s);
            this.salt = s;
            this.iterations = ITERATIONS;
            this.key = derive(password, s, ITERATIONS);
            this.state = VaultState.UNLOCKED;
            save();
            wipeAndDelete(plainFile);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public synchronized void removeMasterPassword() {
        if (state != VaultState.UNLOCKED) {
            return;
        }
        this.key = null;
        this.salt = null;
        this.state = VaultState.PLAIN;
        save();
        try {
            Files.deleteIfExists(vaultFile);
        } catch (IOException e) {
            LOG.error("Failed to delete vault", e);
        }
    }

    public synchronized void save() {
        if (state == VaultState.LOCKED) {
            return;
        }
        try {
            if (state == VaultState.PLAIN) {
                writeAtomic(plainFile, GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
            } else {
                byte[] iv = new byte[12];
                RNG.nextBytes(iv);
                byte[] ct = crypt(Cipher.ENCRYPT_MODE, key, iv, GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
                JsonObject o = new JsonObject();
                o.addProperty("v", 1);
                o.addProperty("kdf", "PBKDF2WithHmacSHA256");
                o.addProperty("iter", iterations);
                o.addProperty("salt", Base64.getEncoder().encodeToString(salt));
                o.addProperty("iv", Base64.getEncoder().encodeToString(iv));
                o.addProperty("ct", Base64.getEncoder().encodeToString(ct));
                writeAtomic(vaultFile, GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | GeneralSecurityException e) {
            LOG.error("Failed to save session store", e);
        }
    }

    private static SecretKey derive(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] crypt(int mode, SecretKey key, byte[] iv, byte[] input) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(128, iv));
        c.updateAAD(AAD);
        return c.doFinal(input);
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void wipeAndDelete(Path p) {
        try {
            if (Files.exists(p)) {
                Files.write(p, new byte[(int) Math.min(Files.size(p), Integer.MAX_VALUE)]);
                Files.delete(p);
            }
        } catch (IOException e) {
            LOG.warn("Could not wipe {}", p, e);
        }
    }
}