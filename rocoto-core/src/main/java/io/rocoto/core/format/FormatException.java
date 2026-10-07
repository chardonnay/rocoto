package io.rocoto.core.format;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Wraps I/O errors of the target {@link Appendable}. */
final class FormatException {

    private FormatException() {
    }

    static UncheckedIOException wrap(IOException e) {
        return new UncheckedIOException(e);
    }
}
