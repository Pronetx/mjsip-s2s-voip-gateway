package com.example.s2s.voipgateway.nova.io;

import com.example.s2s.voipgateway.nova.transcode.PcmToULawTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An InputStream backed by a queue for sending outbound μ-law audio in exact 160-byte frames (20ms @ 8kHz).
 * Supports barge-in via clearQueue(), and clean turn finalization via endOfTurn().
 */
public class QueuedUlawInputStream extends InputStream {
    private static final Logger log = LoggerFactory.getLogger(QueuedUlawInputStream.class);
    private static final byte SILENCE = (byte)0x7F; // μ-law silence
    private static final int FRAME_BYTES = 160;     // 20ms @ 8kHz μ-law

    // Frame-queue model: produce exact 160B frames; consumer reads smoothly every 20ms.
    private final LinkedBlockingQueue<byte[]> frameQueue = new LinkedBlockingQueue<>(400); // ~8s @ 50fps
    private final ByteArrayOutputStream accumulator = new ByteArrayOutputStream(4096);

    private byte[] currentFrame = null;
    private int currentIndex = 0;
    private boolean open = true;
    private OutputStream testOutput;
    private boolean debugAudioSent = System.getenv().getOrDefault("DEBUG_AUDIO_SENT", "false").equalsIgnoreCase("true");
    private volatile long squelchUntilMs = 0L;

    /**
     * Appends PCM16 audio (8kHz, mono) and enqueues μ-law frames of exactly 160 bytes each.
     *
     * @param data PCM16 little-endian mono @ 8kHz
     * @throws InterruptedException if interrupted during enqueue
     */
    public void append(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) return;

        byte[] ulaw = PcmToULawTranscoder.transcodeBytes(data);

        synchronized (accumulator) {
            try {
                // accumulate then drain in 160B frames
                accumulator.write(ulaw);
                byte[] accBytes = accumulator.toByteArray();
                int offset = 0;

                while (accBytes.length - offset >= FRAME_BYTES) {
                    byte[] frame = new byte[FRAME_BYTES];
                    System.arraycopy(accBytes, offset, frame, 0, FRAME_BYTES);

                    if (!frameQueue.offer(frame)) {
                        // backpressure: drop oldest to keep latency low
                        frameQueue.poll();
                        frameQueue.offer(frame);
                        log.debug("Dropped oldest μ-law frame due to backpressure");
                    }
                    offset += FRAME_BYTES;
                }

                // keep remainder
                accumulator.reset();
                if (offset < accBytes.length) {
                    accumulator.write(accBytes, offset, accBytes.length - offset);
                }
            } catch (IOException e) {
                log.warn("ULaw accumulator error", e);
            }
        }

        if (debugAudioSent) {
            // Transcoded audio will be written to a .raw file for debugging purposes.  This can be opened
            // with an audio editor like Audacity (File -> Import -> Raw Data, then use U-Law encoding,
            // 8000 khz sample rate, 1 channel).
            //
            try {
                OutputStream testOutput = new FileOutputStream("bedrock.raw", true);
                testOutput.write(ulaw);
                testOutput.close();
            } catch (IOException e) {
                log.warn("Failed to write debugging audio output", e);
            }
        }
    }

    @Override
    public int read() throws IOException {
        if (!open) {
            throw new IOException("Stream is closed!");
        }
        if (testOutput == null && debugAudioSent) {
            testOutput = new FileOutputStream("sent.raw");
        }

        // Fast-path squelch check
        if (System.currentTimeMillis() < squelchUntilMs) {
            if (testOutput != null) testOutput.write(SILENCE);
            return SILENCE;
        }

        // advance to next frame if needed
        if (currentFrame == null || currentIndex >= currentFrame.length) {
            try {
                currentFrame = frameQueue.poll(10, TimeUnit.MILLISECONDS);
                currentIndex = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SILENCE;
            }
            if (currentFrame == null) {
                // no frame: emit μ-law silence
                if (testOutput != null) testOutput.write(SILENCE);
                return SILENCE;
            }
        }

        byte readByte = currentFrame[currentIndex];
        currentIndex++;
        if (testOutput != null) {
            testOutput.write(readByte);
        }
        return readByte & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (!open) throw new IOException("Stream is closed!");
        if (b == null) throw new NullPointerException();
        if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
        if (len == 0) return 0;

        int totalRead = 0;
        while (totalRead < len) {
            if (currentFrame == null || currentIndex >= currentFrame.length) {
                try {
                    currentFrame = frameQueue.poll(10, TimeUnit.MILLISECONDS);
                    currentIndex = 0;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (currentFrame == null) {
                    // fill remainder with μ-law silence
                    int remaining = len - totalRead;
                    for (int i = 0; i < remaining; i++) {
                        b[off + totalRead + i] = SILENCE;
                        if (testOutput != null) testOutput.write(SILENCE);
                    }
                    totalRead += remaining;
                    break;
                }
            }
            int available = currentFrame.length - currentIndex;
            int toCopy = Math.min(available, len - totalRead);
            System.arraycopy(currentFrame, currentIndex, b, off + totalRead, toCopy);
            if (testOutput != null) testOutput.write(currentFrame, currentIndex, toCopy);
            currentIndex += toCopy;
            totalRead += toCopy;
        }
        return totalRead;
    }

    @Override
    public void close() throws IOException {
        this.open = false;
        if (testOutput != null) {
            testOutput.close();
            testOutput = null;
        }
    }

    @Override
    public synchronized void reset() throws IOException {
    }

    /**
     * Clears queued audio immediately (used for barge-in).
     */
    public void clearQueue() {
        int discarded = frameQueue.size();
        frameQueue.clear();
        currentFrame = null;
        currentIndex = 0;
        synchronized (accumulator) {
            accumulator.reset();
        }
        log.info("Cleared μ-law downlink queue due to barge-in ({} frames discarded)", discarded);
    }

    /**
     * Interrupt immediately: clear queue and force silence for N ms.
     * This prevents audio glitches from frames already in flight.
     */
    public void interruptNow(int ms) {
        clearQueue();
        squelchUntilMs = System.currentTimeMillis() + Math.max(50, ms);
    }

    /**
     * Finalizes the current assistant turn:
     * - Pads any partial frame to 160B and enqueues it
     * - Adds one extra 20ms of μ-law silence as comfort pause
     */
    public void endOfTurn() {
        synchronized (accumulator) {
            byte[] rem = accumulator.toByteArray();
            if (rem.length > 0) {
                byte[] padded = new byte[FRAME_BYTES];
                int copy = Math.min(rem.length, FRAME_BYTES);
                System.arraycopy(rem, 0, padded, 0, copy);
                for (int i = copy; i < FRAME_BYTES; i++) padded[i] = SILENCE;
                frameQueue.offer(padded);
                accumulator.reset();
            }
        }
        // comfort silence
        byte[] comfort = new byte[FRAME_BYTES];
        for (int i = 0; i < FRAME_BYTES; i++) comfort[i] = SILENCE;
        frameQueue.offer(comfort);
        log.debug("endOfTurn(): padded remainder + comfort silence enqueued");
    }
}
