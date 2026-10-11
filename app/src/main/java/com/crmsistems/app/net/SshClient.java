package com.crmsistems.app.net;

import android.util.Base64;

import androidx.annotation.Nullable;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.jcraft.jsch.UserInfo;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Подключение к VPS по SSH: выполнить команду, залить файл.
 * <p>
 * Вход по паролю. Ключ сервера проверяется по принципу «доверяй при первом
 * подключении»: отпечаток запоминается, и если в следующий раз сервер
 * покажет другой ключ, пароль ему уже не уйдёт — подключение оборвётся
 * до входа.
 */
public final class SshClient implements Closeable {

    /** Ошибка с понятным человеку текстом. */
    public static final class SshException extends Exception {
        public SshException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    public interface LineListener {
        void onLine(String line);
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    private final Session session;
    private final String user;
    private final String password;
    private final String fingerprint;

    private SshClient(Session session, String user, String password, String fingerprint) {
        this.session = session;
        this.user = user;
        this.password = password;
        this.fingerprint = fingerprint;
    }

    /**
     * Подключиться. {@code knownFingerprint} — запомненный отпечаток или
     * пустая строка, если сервер видим впервые.
     */
    public static SshClient connect(String host, int port, String user, String password,
                                    String knownFingerprint) throws SshException {
        JSch jsch = new JSch();
        PinnedKeys keys = new PinnedKeys(knownFingerprint);
        jsch.setHostKeyRepository(keys);
        try {
            Session s = jsch.getSession(user, host, port);
            s.setPassword(password);
            s.setUserInfo(new SilentUserInfo(password));
            // Новый сервер — принимаем ключ и запоминаем; знакомый — только тот же ключ.
            s.setConfig("StrictHostKeyChecking", knownFingerprint.isEmpty() ? "no" : "yes");
            s.setConfig("PreferredAuthentications", "password,keyboard-interactive");
            s.setServerAliveInterval(15_000);
            s.connect(20_000);
            return new SshClient(s, user, password, keys.seen);
        } catch (JSchException e) {
            android.util.Log.w("CrmSsh", "подключение к " + host + ":" + port, e);
            // Понятное объяснение — для человека, исходная ошибка — для того,
            // кто будет разбираться, если объяснение не подошло.
            Throwable root = e.getCause() != null ? e.getCause() : e;
            throw new SshException(explain(e, keys) + "\n\n(" + root + ")", e);
        }
    }

    /** Отпечаток ключа сервера в виде SHA256:… — как печатает ssh-keygen -l. */
    public String fingerprint() {
        return fingerprint;
    }

    public boolean isRoot() {
        return "root".equals(user);
    }

    /**
     * Выполнить команду и вернуть её код выхода. Вывод (и stdout, и stderr)
     * отдаётся построчно. {@code asRoot} — для не-root пользователя команда
     * пойдёт через sudo, пароль подаётся в sudo сам.
     */
    public int exec(String command, boolean asRoot, @Nullable LineListener out)
            throws SshException {
        boolean sudo = asRoot && !isRoot();
        String cmd = sudo ? "sudo -S -p '' sh -c " + quote(command) + " 2>&1" : "(" + command + ") 2>&1";
        ChannelExec ch = null;
        try {
            ch = (ChannelExec) session.openChannel("exec");
            ch.setCommand(cmd);
            InputStream in = ch.getInputStream();
            OutputStream stdin = ch.getOutputStream();
            ch.connect(15_000);
            if (sudo) {
                stdin.write((password + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (out != null) out.onLine(line);
            }
            // Поток закрылся — ждём, пока сервер пришлёт код выхода.
            for (int i = 0; i < 50 && !ch.isClosed(); i++) Thread.sleep(100);
            return ch.getExitStatus();
        } catch (JSchException | IOException e) {
            throw new SshException("Связь с сервером оборвалась: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SshException("Прервано", e);
        } finally {
            if (ch != null) ch.disconnect();
        }
    }

    /** Выполнить команду и вернуть весь вывод одной строкой. */
    public String run(String command) throws SshException {
        StringBuilder sb = new StringBuilder();
        exec(command, false, line -> sb.append(line).append('\n'));
        return sb.toString().trim();
    }

    /** Залить файл по SFTP с правами {@code mode} (восьмерично, например 0600). */
    public void upload(InputStream data, long size, String remotePath, int mode,
                       @Nullable Progress progress) throws SshException {
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(15_000);
            sftp.put(data, remotePath, new SftpProgressMonitor() {
                long done;

                @Override
                public void init(int op, String src, String dest, long max) {
                }

                @Override
                public boolean count(long count) {
                    done += count;
                    if (progress != null) progress.onProgress(done, size);
                    return true;
                }

                @Override
                public void end() {
                }
            }, ChannelSftp.OVERWRITE);
            sftp.chmod(mode, remotePath);
        } catch (JSchException | SftpException e) {
            throw new SshException("Не удалось залить " + remotePath + ": " + e.getMessage(), e);
        } finally {
            if (sftp != null) sftp.disconnect();
        }
    }

    public void upload(String text, String remotePath, int mode) throws SshException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        upload(new ByteArrayInputStream(bytes), bytes.length, remotePath, mode, null);
    }

    @Override
    public void close() {
        session.disconnect();
    }

    /** Строка в одинарных кавычках для shell. */
    public static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------ ошибки

    private static String explain(JSchException e, PinnedKeys keys) {
        String m = String.valueOf(e.getMessage());
        if (keys.changed) {
            return "Сервер предъявил ДРУГОЙ ключ, чем при прошлом подключении.\n"
                    + "Было: " + keys.known + "\nСейчас: " + keys.seen + "\n"
                    + "Если сервер переустанавливали — нажмите «Забыть ключ сервера». "
                    + "Если нет — возможно, подключение перехвачено, пароль не отправлен.";
        }
        if (m.contains("Auth fail") || m.contains("Auth cancel")) {
            return "Сервер не принял логин или пароль. Проверьте их; если входите как root — "
                    + "у некоторых VPS вход root по паролю выключен, тогда нужен другой пользователь с sudo.";
        }
        if (m.contains("UnknownHost")) return "Нет такого адреса: проверьте IP или имя сервера.";
        if (m.contains("timeout") || m.contains("timed out")) {
            return "Сервер не отвечает: проверьте IP, порт SSH и что VPS включён.";
        }
        if (m.contains("Connection refused")) return "Сервер отказал в подключении: неверный порт SSH?";
        return "Не удалось подключиться: " + m;
    }

    // ------------------------------------------------------ ключ сервера

    static String fingerprintOf(byte[] key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key);
            return "SHA256:" + Base64.encodeToString(d, Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    /** Хранилище из одного запомненного отпечатка. */
    private static final class PinnedKeys implements HostKeyRepository {
        final String known;
        String seen = "";
        boolean changed;

        PinnedKeys(String known) {
            this.known = known == null ? "" : known;
        }

        @Override
        public int check(String host, byte[] key) {
            seen = fingerprintOf(key);
            if (known.isEmpty()) return NOT_INCLUDED;
            if (known.equals(seen)) return OK;
            changed = true;
            return CHANGED;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
        }

        @Override
        public void remove(String host, String type) {
        }

        @Override
        public void remove(String host, String type, byte[] key) {
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "crm-pinned";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }

    /** Отвечает на вопросы JSch без участия человека: пароль — есть, «да» — нет. */
    private static final class SilentUserInfo implements UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
        private final String password;

        SilentUserInfo(String password) {
            this.password = password;
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public boolean promptPassword(String message) {
            return true;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptYesNo(String message) {
            return false;
        }

        @Override
        public void showMessage(String message) {
        }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name,
                                                  String instruction, String[] prompt, boolean[] echo) {
            String[] answers = new String[prompt.length];
            for (int i = 0; i < prompt.length; i++) answers[i] = password;
            return answers;
        }
    }
}
