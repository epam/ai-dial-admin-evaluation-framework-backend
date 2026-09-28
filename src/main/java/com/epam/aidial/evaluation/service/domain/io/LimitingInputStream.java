package com.epam.aidial.evaluation.service.domain.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.LongFunction;

/**
 * Enforces a byte cap on the bytes actually read from a delegate stream, throwing an exception built by
 * {@code limitExceededException} as soon as the running count exceeds {@code maxBytes} — before a caller
 * that reads the whole stream into memory (e.g. {@link
 * com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient#upload}) can finish reading it and send
 * a request. A caller-declared size (a {@code MultipartFile} size, or a ZIP entry's central-directory
 * header) cannot be trusted instead, since either can understate the real, decompressed size.
 *
 * <p>Shared by {@link com.epam.aidial.evaluation.service.domain.FileService} (dataset file uploads) and
 * {@link com.epam.aidial.evaluation.service.domain.zip.ZipArchiveReader} (ZIP entry reads: {@code
 * test-cases.csv}, {@code manifest.json} and {@code files/…} entries), each supplying its own cap and
 * exception message/factory via {@code limitExceededException} rather than this class hard-coding one.
 */
public final class LimitingInputStream extends FilterInputStream {

    private final long maxBytes;
    private final LongFunction<? extends RuntimeException> limitExceededException;
    private long bytesRead;

    /**
     * @param in delegate stream to read from
     * @param maxBytes the cap on bytes actually read; exceeding it throws
     * @param limitExceededException builds the exception to throw once {@code maxBytes} is exceeded,
     *     given {@code maxBytes} itself (so the message can name the limit)
     */
    public LimitingInputStream(
            InputStream in, long maxBytes, LongFunction<? extends RuntimeException> limitExceededException) {
        super(in);
        this.maxBytes = maxBytes;
        this.limitExceededException = limitExceededException;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            checkLimit(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            checkLimit(n);
        }
        return n;
    }

    private void checkLimit(int n) {
        bytesRead += n;
        if (bytesRead > maxBytes) {
            throw limitExceededException.apply(maxBytes);
        }
    }
}
