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
    @Implementation
    protected static void rename(File source, File target) {
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
