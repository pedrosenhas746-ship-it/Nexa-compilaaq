package com.nexa.xr;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.Surface;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.concurrent.*;
import org.freedesktop.monado.ipc.Client;
import org.freedesktop.monado.ipc.IMonado;
import org.junit.Test;
import static org.junit.Assert.*;

/** Executes the repaired Client on Android, using real kernel sockets and controlled callbacks.
 * Does not replace or emulate the PhoneXR native compositor. */
public final class PhoneXrClientTest {
    private static final String PACKAGE = RuntimeConnectionCheck.PACKAGE;
    private static final int OK=0, REFUSED=1, NULL=2, DEAD=3, DISCONNECTED=4, SILENT=5, SERVER_FAIL=6, MISSING=7, DENIED=8;
    private static final class FakeContext extends ContextWrapper implements Closeable {
        final int mode;
        volatile int unbindCount;
        final CountDownLatch released=new CountDownLatch(1);
        volatile ServiceConnection connection;
        volatile ParcelFileDescriptor sent, remote;
        volatile int flags;
        final IMonado.Stub binder = new IMonado.Stub() {
            @Override public void connect(ParcelFileDescriptor descriptor) throws RemoteException {
                sent = descriptor;
                if (mode == SERVER_FAIL) throw new IllegalStateException("server not available");
                try { remote = descriptor.dup(); }
                catch (IOException e) { throw new RemoteException(e.toString()); }
            }
            @Override public void passAppSurface(Surface surface) { throw new AssertionError("not an Activity"); }
            @Override public boolean canDrawOverOtherApps() { return true; }
        };
        FakeContext(int mode) { super(InstrumentationRegistry.getInstrumentation().getTargetContext()); this.mode=mode; }
        @Override public Context getApplicationContext() { return this; }
        @Override public Context createPackageContext(String name, int flags) throws PackageManager.NameNotFoundException {
            if(mode==MISSING)throw new PackageManager.NameNotFoundException(name);
            assertEquals(PACKAGE,name); return this;
        }
        private boolean start(Intent intent, int flags, Executor executor, ServiceConnection connection) {
            this.flags=flags; this.connection=connection;
            assertEquals(new ComponentName(PACKAGE,"org.freedesktop.monado.ipc.MonadoService"), intent.getComponent());
            if(mode==REFUSED)return false;
            if(mode==DENIED)throw new SecurityException("binding denied");
            if(mode!=SILENT)executor.execute(() -> {
                ComponentName name=intent.getComponent();
                if(mode==NULL)connection.onNullBinding(name);
                else if(mode==DEAD)connection.onBindingDied(name);
                else if(mode==DISCONNECTED)connection.onServiceDisconnected(name);
                else connection.onServiceConnected(name,binder);
            });
            return true;
        }
        @Override public boolean bindService(Intent intent, int flags, Executor executor, ServiceConnection connection) { return start(intent,flags,executor,connection); }
        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) { return start(intent,flags,r -> new Thread(r).start(),connection); }
        @Override public void unbindService(ServiceConnection connection) {
            assertSame(this.connection,connection);
            if(++unbindCount>1)throw new IllegalArgumentException("double unbind");
            released.countDown();
        }
        @Override public void close() throws IOException { if(remote!=null)remote.close(); }
    }
    private Client connectFailure(int mode) throws Exception {
        try(FakeContext context=new FakeContext(mode)) {
            Client client=new Client(1L);
            long start=SystemClock.elapsedRealtime();
            assertEquals(-1,client.blockingConnect(context,PACKAGE));
            assertTrue(client.failed); assertNull(client.monado);
            client.markAsDiscardedByNative();
            // The callback wakes the waiting native thread before it unbinds outside the monitor.
            // Wait for that asynchronous cleanup, rather than racing its count increment.
            if(mode!=MISSING)assertTrue("binding must be released",context.released.await(1,TimeUnit.SECONDS));
            assertEquals(mode==MISSING?0:1,context.unbindCount);
            if(mode!=SILENT)assertTrue("callback must wake waiter",SystemClock.elapsedRealtime()-start<2000);
            return client;
        }
    }
    @Test public void refusedBindingReleasesRegisteredDispatcherOnce() throws Exception { connectFailure(REFUSED); }
    @Test public void deniedBindingReleasesRegisteredDispatcherOnce() throws Exception { connectFailure(DENIED); }
    @Test public void missingPackageDoesNotAttemptBinding() throws Exception { connectFailure(MISSING); }
    @Test public void nullBindingWakesWaiterAndUnbindsOnce() throws Exception { connectFailure(NULL); }
    @Test public void bindingDeathWakesWaiterAndUnbindsOnce() throws Exception { connectFailure(DEAD); }
    @Test public void disconnectWakesWaiterAndUnbindsOnce() throws Exception { connectFailure(DISCONNECTED); }
    @Test public void silentServiceTimesOutAndIgnoresLateCallback() throws Exception {
        try(FakeContext context=new FakeContext(SILENT)) {
            Client client=new Client(1L); long start=SystemClock.elapsedRealtime();
            assertEquals(-1,client.blockingConnect(context,PACKAGE));
            assertTrue(SystemClock.elapsedRealtime()-start>=4800);
            assertTrue(SystemClock.elapsedRealtime()-start<8000);
            context.connection.onServiceConnected(new ComponentName(PACKAGE,"unused"),context.binder);
            assertNull(client.monado); assertTrue(client.failed); assertEquals(1,context.unbindCount);
            client.markAsDiscardedByNative(); assertEquals(1,context.unbindCount);
        }
    }
    @Test public void unavailableNativeServerClosesBothLocalSockets() throws Exception {
        try(FakeContext context=new FakeContext(SERVER_FAIL)) {
            Client client=new Client(1L);
            assertEquals(-1,client.blockingConnect(context,PACKAGE));
            assertNotNull(context.sent);
            try { context.sent.getFd(); fail("sending FD leaked"); } catch(IllegalStateException expected) { }
            assertTrue(client.failed); assertEquals(1,context.unbindCount);
            client.markAsDiscardedByNative(); assertEquals(1,context.unbindCount);
        }
    }
    @Test public void successfulHandoffTransmitsBytesAndReleasesOwnedDescriptors() throws Exception {
        try(FakeContext context=new FakeContext(OK)) {
            Client client=new Client(1L); int fd=client.blockingConnect(context,PACKAGE);
            assertTrue(fd>=0); assertFalse(client.failed); assertNotNull(client.monado);
            assertTrue((context.flags & Context.BIND_ABOVE_CLIENT)!=0);
            try { context.sent.getFd(); fail("sending FD leaked"); } catch(IllegalStateException expected) { }
            try(OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.fromFd(fd));
                InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(context.remote.dup())) {
                output.write(73); assertEquals(73,input.read());
            }
            client.markAsDiscardedByNative(); assertNull(client.monado); assertEquals(1,context.unbindCount);
            try { ParcelFileDescriptor duplicate=ParcelFileDescriptor.fromFd(fd);duplicate.close();fail("owned FD leaked"); }
            catch(IOException expected) { }
        }
    }
    @Test public void interruptionIsPreservedAndBindingReleased() throws Exception {
        try(FakeContext context=new FakeContext(SILENT)) {
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try {
                assertTrue(executor.submit(() -> {
                    Client client=new Client(1L); Thread.currentThread().interrupt();
                    int fd=client.blockingConnect(context,PACKAGE);
                    boolean interrupted=Thread.currentThread().isInterrupted();
                    client.markAsDiscardedByNative();
                    return fd==-1 && interrupted && client.failed && context.unbindCount==1;
                }).get(2,TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); }
        }
    }
    @Test public void nativeFieldsAndEntryPointsKeepOriginalDescriptors() throws Exception {
        assertEquals(IMonado.class,Client.class.getField("monado").getType());
        assertEquals(boolean.class,Client.class.getField("failed").getType());
        assertNotNull(Client.class.getConstructor(long.class));
        assertEquals(int.class,Client.class.getMethod("blockingConnect",Context.class,String.class).getReturnType());
        assertEquals(void.class,Client.class.getMethod("markAsDiscardedByNative").getReturnType());
    }
}
