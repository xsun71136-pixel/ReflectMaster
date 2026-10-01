package formatfa.reflectmaster.hook.bridge;

import android.os.SystemClock;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import formatfa.reflectmaster.core.RmLog;
import formatfa.reflectmaster.hook.RemoteChannel;
import formatfa.reflectmaster.hook.ScriptState;
import formatfa.reflectmaster.reflect.Values;
import kotlin.Pair;

/**
 * IO / encoding bridge exposed to FakeScript as {@code io.*}.
 *
 * The 1.x version wrote captures straight to `/sdcard/Reflect.data` and
 * `/sdcard/<class><ts>.png`. Android 11 scoped storage makes both paths
 * unwritable from an ordinary app process, so the primary sink is now
 * [RemoteChannel.pushFile], which stores the bytes inside the module's own
 * storage through the ContentProvider and shows up in the module UI. Raw path
 * access is kept as an escape hatch for rooted setups.
 *
 * Java for the same reason as {@link rf}: {@code fk.regclass} binds
 * {@code getSimpleName()} + static methods.
 */
public class io {

    /** Block for [millis] milliseconds. Returns 1, or 0 when interrupted. */
    public static int sleep(int millis) {
        if (millis <= 0) return 1;
        try {
            Thread.sleep(millis);
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /** Write into the LSPosed / Xposed log. */
    public static int xplog(Object o) {
        try {
            XposedBridge.log(String.valueOf(o));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Write into the module log shown in the console and in the module UI. */
    public static int log(Object o) {
        RmLog.INSTANCE.i("[script] " + String.valueOf(o));
        ScriptState.INSTANCE.emit(String.valueOf(o));
        return 1;
    }

    /** 1 when the path exists. */
    public static int exists(String path) {
        try {
            return new File(path).exists() ? 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Read a text file; returns "" on failure. */
    public static String readString(String path) {
        byte[] b = readBytes(path);
        return b == null ? "" : new String(b, java.nio.charset.Charset.forName("UTF-8"));
    }

    /** Read a binary file; returns null on failure. */
    public static byte[] readBytes(String path) {
        FileInputStream in = null;
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            in = new FileInputStream(f);
            long len = f.length();
            int size = (len > Integer.MAX_VALUE || len <= 0) ? 8192 : (int) len;
            byte[] buf = new byte[size];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            if (read == buf.length) return buf;
            byte[] out = new byte[read];
            System.arraycopy(buf, 0, out, 0, read);
            return out;
        } catch (Throwable t) {
            ScriptState.INSTANCE.fail("读取失败 " + path + ": " + t.getMessage());
            return null;
        } finally {
            close(in);
        }
    }

    /**
     * Write to an absolute path. On Android 11 this only succeeds for paths the
     * host application may write (its own data dir, or `/sdcard` with
     * MANAGE_EXTERNAL_STORAGE). Prefer {@link #saveInbox(String, Object)}.
     */
    public static int writeFile(String path, Object data) {
        OutputStream os = null;
        try {
            byte[] bytes = toBytes(data);
            if (bytes == null) return 0;
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            os = new FileOutputStream(f);
            os.write(bytes);
            os.flush();
            ScriptState.INSTANCE.emit("[写入] " + f.getAbsolutePath() + " (" + bytes.length + " 字节)");
            return 1;
        } catch (Throwable t) {
            ScriptState.INSTANCE.fail("写入失败 " + path + ": " + t.getMessage());
            return 0;
        } finally {
            close(os);
        }
    }

    /**
     * Store bytes in the module's private inbox through the ContentProvider.
     * Works on every Android version regardless of storage permissions, and the
     * result can be exported again from the module UI.
     */
    public static int saveInbox(String name, Object data) {
        byte[] bytes = toBytes(data);
        if (bytes == null) return 0;
        boolean ok = RemoteChannel.INSTANCE.pushFile(name, bytes);
        ScriptState.INSTANCE.emit((ok ? "[已存入模块收件箱] " : "[收件箱写入失败] ") + name
                + " (" + bytes.length + " 字节)");
        return ok ? 1 : 0;
    }

    /** List the module inbox as text. Blocking - avoid on the main thread. */
    public static String inboxList() {
        List<kotlin.Pair<String, Long>> files = RemoteChannel.INSTANCE.listInbox();
        if (files == null) return "";
        StringBuilder sb = new StringBuilder();
        for (kotlin.Pair<String, Long> p : files) {
            sb.append(p.getFirst()).append("  ").append(p.getSecond()).append('\n');
        }
        return sb.toString();
    }

    /** Current wall clock in milliseconds. */
    public static long now() {
        return System.currentTimeMillis();
    }

    /** Milliseconds since boot - monotonic, use it for timing. */
    public static long uptime() {
        return SystemClock.uptimeMillis();
    }

    /** Identity hash, handy for telling two equal-looking objects apart. */
    public static String id(Object o) {
        return o == null ? "null" : ("0x" + Integer.toHexString(System.identityHashCode(o)));
    }

    /** Same rendering the console uses for list rows. */
    public static String describe(Object o) {
        return Values.INSTANCE.describe(o);
    }

    /** Lower case hex dump of a byte array. */
    public static String toHex(Object o) {
        byte[] b = toBytes(o);
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            String h = Integer.toHexString(v & 0xFF);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }

    /** Parse a hex string (spaces ignored) into bytes; null on bad input. */
    public static byte[] fromHex(String s) {
        if (s == null) return null;
        String clean = s.replace(" ", "").replace("\n", "").replace("\r", "");
        if (clean.length() % 2 != 0) return null;
        try {
            byte[] out = new byte[clean.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Base64 encode (NO_WRAP). Accepts byte[] or any object's toString(). */
    public static String base64Encode(Object o) {
        byte[] b = toBytes(o);
        if (b == null) return "";
        try {
            return Base64.encodeToString(b, Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Base64 decode; null on bad input. */
    public static byte[] base64Decode(String s) {
        if (s == null) return null;
        try {
            return Base64.decode(s, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- helpers

    private static byte[] toBytes(Object data) {
        if (data == null) return null;
        if (data instanceof byte[]) return (byte[]) data;
        if (data instanceof String) {
            return ((String) data).getBytes(java.nio.charset.Charset.forName("UTF-8"));
        }
        return Values.INSTANCE.describe(data).getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
