package com.shilapi.xcertplay.hud;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Detached shell helper: one fixed package/op, sequenced commands, ten-second recovery lease. */
public final class BydCallPopupTool {
    static final String DIRECTORY = "/data/local/tmp/diplay-call-popup";
    static final String PACKAGE = "com.byd.bluetoothcall";
    private final String base;
    private BydCallPopupLease lease;
    private String lastStatus = "READY";

    private BydCallPopupTool(String token) { base = DIRECTORY + "/" + token; }

    public static void main(String[] args) {
        if (args.length != 1 || !args[0].matches("[a-f0-9]{32}") ||
                android.os.Process.myUid() != 2000 || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        BydCallPopupTool tool = new BydCallPopupTool(args[0]);
        try {
            Os.umask(0077);
            if (!new File(DIRECTORY).exists()) Os.mkdir(DIRECTORY, 0700);
            if (Os.lstat(DIRECTORY).st_uid != 2000 || !OsConstants.S_ISDIR(Os.lstat(DIRECTORY).st_mode) ||
                    (Os.lstat(DIRECTORY).st_mode & 0077) != 0) throw new IOException();
            FileDescriptor fd = Os.open(DIRECTORY + "/active.lock", OsConstants.O_CREAT |
                    OsConstants.O_RDWR | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
            try (FileOutputStream file = new FileOutputStream(fd)) {
                if (Os.fstat(fd).st_uid != 2000 || !OsConstants.S_ISREG(Os.fstat(fd).st_mode)) throw new IOException();
                try (FileLock lock = file.getChannel().tryLock()) {
                    if (lock == null) tool.report("ERROR BUSY");
                    else tool.run();
                }
            }
        } catch (Exception | LinkageError error) {
            if (!tool.lastStatus.equals("RESTORED") && !tool.lastStatus.equals("ERROR RESTORE_FAILED"))
                try { tool.report("ERROR HELPER_FAILED"); } catch (Exception ignored) { }
        }
        System.exit(0); // ActivityThread leaves threads behind after the lease ends.
    }

    private void report(String status) {
        lastStatus = status;
        String line = (lease == null ? 0 : lease.sequence()) + " " + status + "\n";
        try {
            FileDescriptor fd = Os.open(base + ".status.tmp", OsConstants.O_CREAT | OsConstants.O_TRUNC |
                    OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
            try (FileOutputStream out = new FileOutputStream(fd)) { out.write(line.getBytes(StandardCharsets.US_ASCII)); }
            Os.rename(base + ".status.tmp", base + ".status");
        } catch (Exception error) { throw new IllegalStateException("STATUS_WRITE_FAILED", error); }
    }

    private void run() throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context shell = system.createPackageContext("com.android.shell", 0);
        lease = new BydCallPopupLease(new PackageOps(shell), SystemClock::elapsedRealtime, this::report);
        Runnable restore = () -> {
            lease.stop();
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            while (!lease.finished() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100);
                lease.tick();
            }
        };
        Thread hook = new Thread(restore, "diplay-call-popup-restore");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            report("READY"); // Published before the launching ADB shell exits.
            while (!lease.finished()) {
                lease.tick();
                File command = new File(base + ".control");
                if (!lease.finished() && command.exists()) {
                    if (Os.lstat(command.getPath()).st_uid != 2000 ||
                            !OsConstants.S_ISREG(Os.lstat(command.getPath()).st_mode)) throw new IOException();
                    FileDescriptor fd = Os.open(command.getPath(), OsConstants.O_RDONLY |
                            OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
                    try (FileInputStream in = new FileInputStream(fd)) {
                        StringBuilder line = new StringBuilder();
                        int c;
                        while ((c = in.read()) != -1 && c != '\n' && line.length() <= 128) line.append((char) c);
                        if (line.length() > 128 || c != '\n' || in.read() != -1) throw new IOException();
                        lease.accept(line.toString());
                    }
                }
                if (!lease.finished()) SystemClock.sleep(250);
            }
        } finally {
            restore.run();
            Runtime.getRuntime().removeShutdownHook(hook);
        }
    }

    /** Reads raw package modes; an absent entry is DEFAULT, not an inferred ALLOW. */
    private static final class PackageOps implements BydCallPopupLease.Ops {
        private final AppOpsManager manager;
        private final int uid, op;
        private final Method getOps, setMode;
        PackageOps(Context shell) throws Exception {
            uid = shell.getPackageManager().getApplicationInfo(PACKAGE, 0).uid;
            manager = shell.getSystemService(AppOpsManager.class);
            if (manager == null) throw new IOException();
            op = AppOpsManager.class.getField("OP_SYSTEM_ALERT_WINDOW").getInt(null);
            getOps = AppOpsManager.class.getMethod("getOpsForPackage", int.class, String.class, int[].class);
            setMode = AppOpsManager.class.getMethod("setMode", int.class, int.class, String.class, int.class);
        }
        public int query() throws Exception {
            List<?> packages = (List<?>) getOps.invoke(manager, uid, PACKAGE, new int[]{op});
            if (packages != null) for (Object entry : packages) {
                List<?> ops = (List<?>) entry.getClass().getMethod("getOps").invoke(entry);
                for (Object item : ops) {
                    if ((Integer) item.getClass().getMethod("getOp").invoke(item) == op)
                        return (Integer) item.getClass().getMethod("getMode").invoke(item);
                }
            }
            return AppOpsManager.MODE_DEFAULT;
        }
        public void set(int mode) throws Exception { setMode.invoke(manager, op, uid, PACKAGE, mode); }
    }
}
