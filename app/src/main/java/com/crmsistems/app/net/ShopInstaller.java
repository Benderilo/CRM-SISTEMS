package com.crmsistems.app.net;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Ставит сервер магазина на VPS: заливает бинарь crmshop из APK вместе
 * с install.sh и запускает установку.
 * <p>
 * Всё, что делается на сервере, описано в server/install.sh — здесь только
 * доставка файлов и чтение хода установки: строки «@@STEP», «@@WARN»,
 * «@@RESULT ключ=значение», «@@DONE», «@@FAIL».
 */
public final class ShopInstaller {

    /** Каталог в APK, куда Gradle кладёт собранный сервер (см. app/build.gradle.kts). */
    private static final String ASSETS = "server/";

    /** Что вписали на экране установки. */
    public static final class Params {
        public String host = "";
        public int port = 22;
        public String user = "root";
        public String password = "";
        public String knownFingerprint = "";

        public String slug = "shop";
        public String domain = "";
        public String email = "";
        public String adminLogin = "admin";
        public String adminPass = "";
    }

    /** Что происходит — для экрана. Вызывается из фонового потока. */
    public interface Listener {
        void onStep(String text);

        void onLog(String line);

        void onWarn(String text);

        void onProgress(int percent);
    }

    /** Итог установки. */
    public static final class Result {
        public final Map<String, String> values = new HashMap<>();
        public String fingerprint = "";

        public String url() {
            return value("url");
        }

        public String key() {
            return value("key");
        }

        public String version() {
            return value("version");
        }

        /** Магазин поставлен с нуля — значит, пароль панели взят наш. */
        public boolean fresh() {
            return "1".equals(value("fresh"));
        }

        private String value(String k) {
            String v = values.get(k);
            return v == null ? "" : v;
        }
    }

    /** Сведения о сервере для кнопки «Проверить подключение». */
    public static final class ServerInfo {
        public String os = "";
        public String arch = "";
        public String disk = "";
        public String memory = "";
        public String fingerprint = "";
        /** Версия уже установленного магазина с этим SLUG, пусто — не установлен. */
        public String installed = "";
    }

    private final Context context;

    public ShopInstaller(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Версия сервера, лежащего в этом APK. */
    public String bundledVersion() {
        try (InputStream in = context.getAssets().open(ASSETS + "VERSION")) {
            return new String(readAll(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** Подключиться и посмотреть, что за сервер. */
    public ServerInfo check(Params p) throws SshClient.SshException {
        try (SshClient ssh = SshClient.connect(p.host, p.port, p.user, p.password, p.knownFingerprint)) {
            ServerInfo info = new ServerInfo();
            info.fingerprint = ssh.fingerprint();
            info.os = ssh.run(". /etc/os-release 2>/dev/null && echo \"$PRETTY_NAME\" || uname -s");
            info.arch = ssh.run("uname -m");
            info.disk = ssh.run("df -h / | awk 'NR==2{print $4}'");
            info.memory = ssh.run("free -m | awk '/^Mem:/{print $2\" МБ\"}'");
            info.installed = ssh.run("[ -x /opt/crm/" + p.slug + "/crmshop ] && /opt/crm/"
                    + p.slug + "/crmshop -version || true");
            return info;
        }
    }

    /** Полная установка (или переустановка с сохранением данных и ключа). */
    public Result install(Params p, Listener l) throws SshClient.SshException {
        return run(p, "install", l);
    }

    /** Только заменить бинарь и перезапустить — данные и настройки остаются. */
    public Result update(Params p, Listener l) throws SshClient.SshException {
        return run(p, "update", l);
    }

    private Result run(Params p, String mode, Listener l) throws SshClient.SshException {
        l.onStep("Подключаюсь к " + p.host);
        l.onProgress(2);
        try (SshClient ssh = SshClient.connect(p.host, p.port, p.user, p.password, p.knownFingerprint)) {
            Result result = new Result();
            result.fingerprint = ssh.fingerprint();
            l.onLog("Отпечаток ключа сервера: " + ssh.fingerprint());

            String arch = archOf(ssh.run("uname -m"));
            if (arch == null) {
                throw new SshClient.SshException("Процессор сервера не поддерживается: "
                        + ssh.run("uname -m") + ". Нужен x86_64 или aarch64.", null);
            }

            // Своя папка на каждый запуск: в /tmp у других пользователей нет к ней доступа.
            String dir = ssh.run("d=$(mktemp -d /tmp/crmshop-upload.XXXXXX) && chmod 700 \"$d\" && echo \"$d\"");
            if (!dir.startsWith("/tmp/crmshop-upload.")) {
                throw new SshClient.SshException("Не удалось создать временную папку на сервере: " + dir, null);
            }

            l.onStep("Загружаю сервер магазина (" + arch + ")");
            String bin = ASSETS + "crmshop-" + arch;
            long size = binarySize(bin);
            try (InputStream in = new GZIPInputStream(context.getAssets().open(bin + ".pack"), 64 * 1024)) {
                ssh.upload(in, size, dir + "/crmshop", 0755, (done, total) ->
                        l.onProgress(5 + (int) (40 * done / Math.max(1, total))));
            } catch (IOException e) {
                throw new SshClient.SshException("В приложении нет файла сервера — пересоберите APK", e);
            }
            ssh.upload(asset(ASSETS + "install.sh"), dir + "/install.sh", 0700);
            ssh.upload(asset(ASSETS + "backup.sh"), dir + "/backup.sh", 0700);
            ssh.upload(envFile(p, mode), dir + "/install.env", 0600);
            l.onProgress(47);

            l.onStep(mode.equals("update") ? "Обновляю магазин" : "Устанавливаю магазин");
            final String[] fail = {null};
            final boolean[] done = {false};
            final int[] steps = {0};
            int code = ssh.exec("bash " + dir + "/install.sh; rc=$?; rm -rf " + dir + "; exit $rc",
                    true, line -> {
                        if (line.startsWith("@@STEP ")) {
                            steps[0]++;
                            l.onStep(line.substring(7));
                            l.onProgress(Math.min(97, 47 + steps[0] * 4));
                        } else if (line.startsWith("@@WARN ")) {
                            l.onWarn(line.substring(7));
                        } else if (line.startsWith("@@RESULT ")) {
                            String kv = line.substring(9);
                            int eq = kv.indexOf('=');
                            if (eq > 0) result.values.put(kv.substring(0, eq), kv.substring(eq + 1));
                        } else if (line.startsWith("@@FAIL ")) {
                            fail[0] = line.substring(7);
                        } else if (line.equals("@@DONE")) {
                            done[0] = true;
                        } else {
                            l.onLog(line);
                        }
                    });
            if (fail[0] != null) throw new SshClient.SshException(fail[0], null);
            if (code != 0 || !done[0]) {
                throw new SshClient.SshException("Установка прервалась (код " + code
                        + "). Подробности — в журнале выше.", null);
            }
            l.onProgress(100);
            return result;
        }
    }

    /** install.env: настройки для install.sh, по строке на значение, в кавычках shell. */
    private static String envFile(Params p, String mode) {
        StringBuilder sb = new StringBuilder();
        env(sb, "MODE", mode);
        env(sb, "SLUG", p.slug);
        env(sb, "DOMAIN", p.domain);
        env(sb, "EMAIL", p.email);
        env(sb, "ADMIN_LOGIN", p.adminLogin);
        env(sb, "ADMIN_PASS", p.adminPass);
        env(sb, "SSH_PORT", String.valueOf(p.port));
        env(sb, "SERVER_IP", p.host);
        return sb.toString();
    }

    private static void env(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(SshClient.quote(value == null ? "" : value.trim())).append('\n');
    }

    @Nullable
    static String archOf(String uname) {
        switch (uname.trim()) {
            case "x86_64":
            case "amd64":
                return "amd64";
            case "aarch64":
            case "arm64":
                return "arm64";
            default:
                return null;
        }
    }

    private String asset(String name) throws SshClient.SshException {
        try (InputStream in = context.getAssets().open(name)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SshClient.SshException("В приложении нет файла " + name + " — пересоберите APK", e);
        }
    }

    /** Размер распакованного бинаря — Gradle пишет его рядом, для полоски прогресса. */
    private long binarySize(String name) {
        try {
            return Long.parseLong(asset(name + ".size").trim());
        } catch (SshClient.SshException | NumberFormatException e) {
            return 14_000_000L; // прогресс тогда будет приблизительным
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }
}
