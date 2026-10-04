// Copyright 2020, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
// NEXA changes: bounded connection, terminal callbacks and descriptor/binding ownership.
package org.freedesktop.monado.ipc;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import androidx.annotation.Keep;
import java.io.IOException;
import java.util.concurrent.*;
import org.freedesktop.monado.auxiliary.MonadoView;
import org.freedesktop.monado.auxiliary.NativeCounterpart;
import org.freedesktop.monado.auxiliary.SystemUiController;

/** Same JNI/AIDL contract as PhoneXR 1.1.0. An IPC connection is not an XR session. */
@Keep
public class Client implements ServiceConnection {
    private static final String TAG = "monado-ipc-client";
    private static final long BIND_TIMEOUT_MS = 5000;
    private final Object binderSync = new Object();
    private final NativeCounterpart nativeCounterpart;
    @Keep public volatile IMonado monado = null;
    @Keep public volatile boolean failed = false;
    private ParcelFileDescriptor fd;
    private Context context;
    // Retain the package context, as in the original client, for the lifetime of the binding.
    private Context runtimePackageContext;
    private ExecutorService callbackExecutor;
    private boolean bindingAttempted, attempted, closed;

    @Keep public Client(long nativePointer) {
        nativeCounterpart = new NativeCounterpart(nativePointer);
        nativeCounterpart.markAsUsedByNativeCode();
    }

    private static void closeFd(ParcelFileDescriptor descriptor) {
        if (descriptor != null) try { descriptor.close(); }
        catch (IOException e) { Log.w(TAG, "Closing socket descriptor", e); }
    }

    /** Terminal, idempotent cleanup; wake the waiter before unbinding outside its monitor. */
    private void shutdown(boolean failure, String reason) {
        Context releaseContext;
        ParcelFileDescriptor releaseFd;
        ExecutorService releaseExecutor;
        synchronized (binderSync) {
            failed |= failure;
            closed = true;
            monado = null;
            releaseContext = bindingAttempted ? context : null;
            bindingAttempted = false;
            releaseFd = fd;
            fd = null;
            releaseExecutor = callbackExecutor;
            callbackExecutor = null;
            runtimePackageContext = null;
            binderSync.notifyAll();
        }
        if (failure) Log.e(TAG, "NEXA IPC repair: " + reason);
        if (releaseContext != null) try { releaseContext.unbindService(this); }
        catch (RuntimeException e) { Log.w(TAG, "Unbinding service", e); }
        closeFd(releaseFd);
        if (releaseExecutor != null) releaseExecutor.shutdown();
    }

    @Keep public void markAsDiscardedByNative() {
        nativeCounterpart.markAsDiscardedByNative(TAG);
        shutdown(false, "discarded by native");
    }

    @Keep public int blockingConnect(Context caller, String packageName) {
        // Android dispatches callbacks/surface work on the UI thread. Never block it here.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            shutdown(true, "blockingConnect called on UI thread");
            return -1;
        }
        if (!bind(caller, packageName)) {
            shutdown(true, "bind failed immediately");
            return -1;
        }
        final IMonado service;
        boolean interrupted = false;
        synchronized (binderSync) {
            long deadline = SystemClock.elapsedRealtime() + BIND_TIMEOUT_MS;
            while (monado == null && !failed && !closed) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                try { binderSync.wait(remaining); }
                catch (InterruptedException e) { interrupted = true; break; }
            }
            service = (!failed && !closed && !interrupted) ? monado : null;
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (service == null) {
            shutdown(true, interrupted ? "binding interrupted" : "binding failed or timed out");
            return -1;
        }
        // The native compositor may need this surface while its server is still starting.
        // Preserve the original ordering: publish the surface before the connect RPC can wait.
        startSurfaceWorker(caller, service);
        ParcelFileDescriptor ours = null, theirs = null;
        try {
            ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
            ours = pair[0];
            theirs = pair[1];
            service.connect(theirs);
            synchronized (binderSync) {
                if (closed || failed || monado != service) return -1;
                fd = ours;
                ours = null; // Ownership transferred to this Client.
                int result = fd.getFd();
                Log.i(TAG, "NEXA IPC repair: connected socket fd " + result);
                return result;
            }
        } catch (IOException | RemoteException | RuntimeException e) {
            // Binder also propagates IllegalStateException("server not available") from MonadoImpl.
            Log.e(TAG, "Socket handoff failed", e);
            shutdown(true, "socket handoff failed: " + e);
            return -1;
        } finally {
            // Binder transfers a duplicate; the local sending end must always be closed.
            closeFd(theirs);
            closeFd(ours);
        }
    }

    public boolean bind(Context caller, String packageName) {
        synchronized (binderSync) {
            if (attempted || closed) return false;
            attempted = true;
            context = caller.getApplicationContext();
            if (context == null) context = caller;
            try {
                runtimePackageContext = context.createPackageContext(packageName,
                        Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
                Intent intent = new Intent("org.freedesktop.monado.ipc.CONNECT")
                        .setComponent(new ComponentName(packageName, "org.freedesktop.monado.ipc.MonadoService"));
                int flags = Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT | Context.BIND_ABOVE_CLIENT;
                // Android tracks the ServiceConnection even on false/SecurityException.
                // Release it once whenever bindService was attempted, not just when accepted.
                bindingAttempted = true;
                boolean accepted;
                if (Build.VERSION.SDK_INT >= 29) {
                    callbackExecutor = Executors.newSingleThreadExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "PhoneXR-binder");
                        thread.setDaemon(true);
                        return thread;
                    });
                    if (Build.VERSION.SDK_INT >= 30) flags |= Context.BIND_INCLUDE_CAPABILITIES;
                    accepted = context.bindService(intent, flags, callbackExecutor, this);
                } else accepted = context.bindService(intent, this, flags);
                return accepted;
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                Log.e(TAG, "Cannot bind PhoneXR package/service", e);
                return false;
            }
        }
    }

    private void startSurfaceWorker(Context caller, IMonado service) {
        if (!(caller instanceof Activity)) return;
        final Activity activity = (Activity) caller;
        new Thread(() -> {
            try {
                synchronized (binderSync) { if (closed || monado != service) return; }
                if (!service.canDrawOverOtherApps()) {
                    CompletableFuture<MonadoView> attachment = new CompletableFuture<>();
                    activity.runOnUiThread(() -> {
                        synchronized (binderSync) { if (closed) { attachment.complete(null); return; } }
                        try { attachment.complete(MonadoView.attachToActivity(activity)); }
                        catch (RuntimeException e) { attachment.completeExceptionally(e); }
                    });
                    MonadoView view = attachment.get(2000, TimeUnit.MILLISECONDS);
                    SurfaceHolder holder = view == null ? null : view.waitGetSurfaceHolder(2000);
                    Surface surface = holder == null ? null : holder.getSurface();
                    if (surface == null || !surface.isValid()) {
                        shutdown(true, "no valid application surface"); return;
                    }
                    synchronized (binderSync) { if (closed || monado != service) return; }
                    service.passAppSurface(surface);
                }
                activity.runOnUiThread(() -> {
                    synchronized (binderSync) { if (closed) return; }
                    try { new SystemUiController(activity.getWindow().getDecorView()).hide(); }
                    catch (RuntimeException e) { Log.w(TAG, "Fullscreen UI", e); }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); shutdown(true, "surface wait interrupted");
            } catch (RemoteException | RuntimeException | ExecutionException | TimeoutException e) {
                Log.e(TAG, "Application surface failed", e); shutdown(true, "surface failed: " + e);
            }
        }, "PhoneXR-surface").start();
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        synchronized (binderSync) {
            if (closed) return;
            monado = IMonado.Stub.asInterface(binder);
            if (monado != null) { binderSync.notifyAll(); return; }
        }
        shutdown(true, "invalid service binder");
    }
    @Override public void onServiceDisconnected(ComponentName name) { shutdown(true, "service disconnected"); }
    @Override public void onNullBinding(ComponentName name) { shutdown(true, "service returned null binder"); }
    @Override public void onBindingDied(ComponentName name) { shutdown(true, "service binding died"); }
}
