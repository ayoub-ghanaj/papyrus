package papyrus;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Shared by running instances; restore requires exclusive access to this library folder. */
final class ProfileLock implements AutoCloseable {
    private static final Map<Path, Shared> OPEN = new HashMap<>();
    private static final class Shared {
        final FileChannel channel; FileLock lock; int references; boolean restoring;
        Shared(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
    }
    private final Path path;
    private final Shared shared;
    private boolean closed;
    private ProfileLock(Path path, Shared shared) { this.path = path; this.shared = shared; shared.references++; }

    static synchronized ProfileLock open(Path directory) throws IOException {
        Path path = directory.toRealPath().resolve(".papyrus.lock");
        Shared shared = OPEN.get(path);
        if (shared != null && shared.restoring) throw new IOException("Library restore in progress");
        if (shared == null) {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock(0, Long.MAX_VALUE, true);
                if (lock == null) throw new IOException("Library restore in progress");
                shared = new Shared(channel, lock); OPEN.put(path, shared);
            } catch (IOException | RuntimeException ex) { channel.close(); throw ex; }
        }
        return new ProfileLock(path, shared);
    }
    AutoCloseable exclusive() throws IOException {
        synchronized (ProfileLock.class) {
            if (closed || shared.references != 1) throw new Store.Rule("closeOtherWindows");
            shared.lock.release();
            try {
                shared.lock = shared.channel.tryLock();
                if (shared.lock == null) throw new Store.Rule("closeOtherWindows");
                shared.restoring = true;
            } catch (IOException | RuntimeException ex) {
                shared.lock = shared.channel.lock(0, Long.MAX_VALUE, true); throw ex;
            }
            return () -> {
                synchronized (ProfileLock.class) {
                    shared.lock.release(); shared.lock = shared.channel.lock(0, Long.MAX_VALUE, true); shared.restoring = false;
                }
            };
        }
    }
    @Override public void close() throws IOException {
        synchronized (ProfileLock.class) {
            if (closed) return; closed = true;
            if (--shared.references == 0) { shared.lock.release(); shared.channel.close(); OPEN.remove(path); }
        }
    }
}
