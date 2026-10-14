package cn.wordwatch.app.render;

import android.util.AtomicFile;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Preserves Android POSIX rename-over-existing semantics when Robolectric runs on Windows. */
@Implements(AtomicFile.class)
public final class ShadowAtomicFile {
    public static final java.util.concurrent.atomic.AtomicLong mainWrites = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong writtenBytes = new java.util.concurrent.atomic.AtomicLong();
    public static volatile boolean measure;
    public static volatile java.util.function.Consumer<File> beforeRename;
    @Implementation
    protected static void rename(File source, File target) {
        if (beforeRename != null) beforeRename.accept(target);
        if (measure) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) mainWrites.incrementAndGet();
            writtenBytes.addAndGet(source.length());
        }
        try {
            try {
                Files.move(source.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Robolectric AtomicFile rename failed", failure);
        }
    }
}
