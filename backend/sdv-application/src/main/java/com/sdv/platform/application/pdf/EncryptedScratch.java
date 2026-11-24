package com.sdv.platform.application.pdf;

import org.apache.pdfbox.io.RandomAccess;
import org.apache.pdfbox.io.RandomAccessReadView;
import org.apache.pdfbox.io.RandomAccessStreamCache;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Task 0063: working space for one PDF. The first bytes stay in memory; the rest goes to a temporary file that only holds
 * ciphertext (AES-256 in counter mode, a random key that never leaves memory, a fresh counter for every page written).
 * The file is deleted when it is closed, and is useless without the key if the process dies first. Not thread-safe: one
 * PDF, one thread, as PDFBox itself.
 */
public final class EncryptedScratch implements RandomAccessStreamCache {
    static final int PAGE = 16 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final long memoryLimit;
    private final long totalLimit;
    private final Path folder;
    private final byte[] key = new byte[32];
    private final Deque<Long> freeSlots = new ArrayDeque<>();
    private final List<Buffer> buffers = new ArrayList<>();
    private long inMemory;
    private long total;
    private long writes;
    private long slots;
    private Path file;
    private FileChannel channel;
    private volatile boolean closed;

    public EncryptedScratch(Path folder, long memoryLimit, long totalLimit) {
        this.folder = folder;
        this.memoryLimit = memoryLimit;
        this.totalLimit = totalLimit;
        RANDOM.nextBytes(key);
    }

    @Override
    public Buffer createBuffer() throws IOException {
        if (closed) throw new IOException("The scratch space is closed.");
        Buffer buffer = new Buffer();
        buffers.add(buffer);
        return buffer;
    }

    /** The bytes that have gone to the file (tests). */
    long slotsUsed() {
        return slots;
    }

    Path file() {
        return file;
    }

    /** What the file holds (tests), read through the open handle: on Linux the file has no name once it is open. */
    byte[] fileBytes() throws IOException {
        ByteBuffer all = ByteBuffer.allocate(Math.toIntExact(channel.size()));
        while (all.hasRemaining() && channel.read(all, all.position()) >= 0) { }
        return all.array();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        for (Buffer buffer : List.copyOf(buffers)) buffer.close();
        Arrays.fill(key, (byte) 0);
        if (channel != null) channel.close();
        if (file != null) Files.deleteIfExists(file);
    }

    private FileChannel channel() throws IOException {
        if (channel == null) {
            file = folder == null ? Files.createTempFile("sdv-scratch-", ".tmp") : Files.createTempFile(folder, "sdv-scratch-", ".tmp");
            channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
        }
        return channel;
    }

    private byte[] crypt(byte[] data, long counter) throws IOException {
        if (closed) throw new IOException("The scratch space is closed.");
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(ByteBuffer.allocate(16).putLong(counter).putLong(0).array()));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IOException("The scratch space can't be encrypted.", ex);
        }
    }

    /** A page: in memory ({@code data}), or in the file at {@code slot}, encrypted with {@code counter}. */
    private static final class PageRef {
        byte[] data;
        long slot = -1;
        long counter = -1;
    }

    public final class Buffer implements RandomAccess {
        private final List<PageRef> pages = new ArrayList<>();
        private final byte[] current = new byte[PAGE];
        private int currentIndex = -1;
        private boolean dirty;
        private long position;
        private long length;
        private boolean bufferClosed;

        private Buffer() { }

        private void open() throws IOException {
            if (bufferClosed) throw new IOException("The scratch buffer is closed.");
        }

        private void load(int index) throws IOException {
            if (index == currentIndex) return;
            flush();
            while (pages.size() <= index) pages.add(new PageRef());
            PageRef ref = pages.get(index);
            if (ref.data != null) {
                System.arraycopy(ref.data, 0, current, 0, PAGE);
            } else if (ref.counter >= 0) {
                ByteBuffer read = ByteBuffer.allocate(PAGE);
                while (read.hasRemaining()) {
                    if (channel().read(read, ref.slot * PAGE + read.position()) < 0) throw new IOException("The scratch file is shorter than expected.");
                }
                System.arraycopy(crypt(read.array(), ref.counter), 0, current, 0, PAGE);
            } else {
                Arrays.fill(current, (byte) 0);
            }
            currentIndex = index;
        }

        private void flush() throws IOException {
            if (!dirty) return;
            PageRef ref = pages.get(currentIndex);
            if (ref.data == null && ref.slot < 0) {
                if (total + PAGE > totalLimit) throw new IOException("The PDF needs more working space than Vero allows.");
                total += PAGE;
                if (inMemory + PAGE <= memoryLimit) {
                    inMemory += PAGE;
                    ref.data = new byte[PAGE];
                } else {
                    Long free = freeSlots.poll();
                    ref.slot = free == null ? slots++ : free;
                }
            }
            if (ref.data != null) {
                System.arraycopy(current, 0, ref.data, 0, PAGE);
            } else {
                // A new counter for every write: the same key stream is never used twice.
                ref.counter = writes++;
                ByteBuffer out = ByteBuffer.wrap(crypt(current, ref.counter));
                long at = ref.slot * PAGE;
                while (out.hasRemaining()) at += channel().write(out, at);
            }
            dirty = false;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b) throws IOException {
            write(b, 0, b.length);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            open();
            while (len > 0) {
                load(Math.toIntExact(position / PAGE));
                int at = (int) (position % PAGE);
                int n = Math.min(len, PAGE - at);
                System.arraycopy(b, off, current, at, n);
                dirty = true;
                position += n;
                off += n;
                len -= n;
                if (position > length) length = position;
            }
        }

        @Override
        public void clear() throws IOException {
            open();
            release();
            position = 0;
            length = 0;
        }

        private void release() {
            for (PageRef ref : pages) {
                if (ref.data != null) {
                    Arrays.fill(ref.data, (byte) 0);
                    inMemory -= PAGE;
                    total -= PAGE;
                } else if (ref.slot >= 0) {
                    freeSlots.add(ref.slot);
                    total -= PAGE;
                }
            }
            pages.clear();
            Arrays.fill(current, (byte) 0);
            currentIndex = -1;
            dirty = false;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            open();
            if (len == 0) return 0;
            if (position >= length) return -1;
            int wanted = (int) Math.min(len, length - position);
            int done = 0;
            while (done < wanted) {
                load(Math.toIntExact(position / PAGE));
                int at = (int) (position % PAGE);
                int n = Math.min(wanted - done, PAGE - at);
                System.arraycopy(current, at, b, off + done, n);
                done += n;
                position += n;
            }
            return done;
        }

        @Override
        public long getPosition() throws IOException {
            open();
            return position;
        }

        @Override
        public void seek(long to) throws IOException {
            open();
            if (to < 0) throw new IOException("Invalid position " + to);
            position = Math.min(to, length);
        }

        @Override
        public long length() throws IOException {
            open();
            return length;
        }

        @Override
        public boolean isClosed() {
            return bufferClosed;
        }

        @Override
        public boolean isEOF() throws IOException {
            open();
            return position >= length;
        }

        @Override
        public RandomAccessReadView createView(long start, long viewLength) throws IOException {
            open();
            return new RandomAccessReadView(this, start, viewLength);
        }

        @Override
        public void close() {
            if (bufferClosed) return;
            release();
            bufferClosed = true;
            buffers.remove(this);
        }
    }
}
