package awszqsedxzaw.cloudarchive.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

// Concatenated standard gzip members are supported by Minecraft's GZIPInputStream.
final class ParallelGzipOutputStream extends OutputStream {
    private static final int CHUNK_BYTES = 1024 * 1024;
    private final OutputStream output;
    private final int workers = Math.min(4, Runtime.getRuntime().availableProcessors());
    private final ExecutorService executor = Executors.newFixedThreadPool(workers);
    private final ArrayDeque<Future<byte[]>> pending = new ArrayDeque<>();
    private byte[] buffer = new byte[CHUNK_BYTES];
    private int position;
    private boolean closed;

    ParallelGzipOutputStream(OutputStream output) {
        this.output = output;
    }

    @Override
    public void write(int value) throws IOException {
        ensureOpen();
        buffer[position++] = (byte) value;
        if (position == buffer.length) {
            submit();
        }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureOpen();
        while (length > 0) {
            int count = Math.min(length, buffer.length - position);
            System.arraycopy(bytes, offset, buffer, position, count);
            position += count;
            offset += count;
            length -= count;
            if (position == buffer.length) {
                submit();
            }
        }
    }

    private void submit() throws IOException {
        if (pending.size() >= workers) {
            drainOne();
        }
        byte[] chunk = position == buffer.length ? buffer : Arrays.copyOf(buffer, position);
        pending.add(executor.submit(() -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(chunk.length);
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes, 65536)) {
                gzip.write(chunk);
            }
            return bytes.toByteArray();
        }));
        buffer = new byte[CHUNK_BYTES];
        position = 0;
    }

    private void drainOne() throws IOException {
        try {
            output.write(pending.remove().get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Archive compression interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Archive compression failed", exception.getCause());
        }
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        if (position > 0) {
            submit();
        }
        while (!pending.isEmpty()) {
            drainOne();
        }
        output.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            flush();
        } finally {
            closed = true;
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    throw new IOException("Archive compression workers did not terminate");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Archive compression interrupted", exception);
            } finally {
                output.close();
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Archive compression stream is closed");
        }
    }
}
