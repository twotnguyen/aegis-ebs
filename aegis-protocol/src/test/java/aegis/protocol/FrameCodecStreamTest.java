package aegis.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Wire behaviour: fragmentation, coalescing, partial reads, oversize guard. */
class FrameCodecStreamTest {

  private final FrameCodec codec = new FrameCodecImpl();
  private static final ObjectMapper M = new ObjectMapper();

  static Envelope sample(String messageId) {
    return new Envelope(Protocol.VERSION, "HEARTBEAT", messageId, "corr-1",
        "2026-11-21T00:00:00Z", M.createObjectNode().put("seq", 1));
  }

  /** Delivers at most {@code chunk} bytes per read call (simulates TCP segmentation). */
  static final class ChunkedInputStream extends InputStream {
    private final byte[] data; private int pos; private final int chunk;
    ChunkedInputStream(byte[] data, int chunk) { this.data = data; this.chunk = chunk; }
    @Override public int read() {
      return pos >= data.length ? -1 : data[pos++] & 0xFF;
    }
    @Override public int read(byte[] b, int off, int len) {
      if (pos >= data.length) return -1;
      int n = Math.min(Math.min(len, chunk), data.length - pos);
      System.arraycopy(data, pos, b, off, n); pos += n; return n;
    }
  }

  /** Serves only the 4 header bytes; any body read is a contract violation. */
  static final class HeaderOnlyStream extends InputStream {
    private final byte[] header; private int pos; boolean bodyReadAttempted;
    HeaderOnlyStream(byte[] header) { this.header = header; }
    @Override public int read() { return pos >= header.length ? -1 : header[pos++] & 0xFF; }
    @Override public int read(byte[] b, int off, int len) {
      if (pos >= header.length) { bodyReadAttempted = true; throw new AssertionError("body read attempted"); }
      int n = Math.min(len, header.length - pos);
      System.arraycopy(header, pos, b, off, n); pos += n; return n;
    }
  }

  static byte[] be(int v) {
    return new byte[]{(byte)(v >>> 24), (byte)(v >>> 16), (byte)(v >>> 8), (byte)v};
  }

  @Test void headerArrivesInFourOneByteChunks() throws Exception {
    byte[] wire = codec.encode(sample("m-frag"));
    Envelope got = codec.readFrame(new ChunkedInputStream(wire, 1));
    assertEquals(sample("m-frag"), got);
  }

  @Test void bodyArrivesInSmallChunks() throws Exception {
    byte[] wire = codec.encode(sample("m-frag-body"));
    Envelope got = codec.readFrame(new ChunkedInputStream(wire, 3));
    assertEquals(sample("m-frag-body"), got);
  }

  @Test void singleByteReadsAcrossWholeFrame() throws Exception {
    byte[] wire = codec.encode(sample("m-single"));
    InputStream in = new ChunkedInputStream(wire, 1);
    assertEquals(sample("m-single"), codec.readFrame(in));
    assertEquals(-1, in.read(), "stream must be exactly consumed");
  }

  @Test void twoCoalescedFramesInOneBurst() throws Exception {
    byte[] a = codec.encode(sample("m-A"));
    byte[] b = codec.encode(sample("m-B"));
    byte[] burst = ByteBuffer.allocate(a.length + b.length).put(a).put(b).array();
    InputStream in = new ChunkedInputStream(burst, 7);
    assertEquals(sample("m-A"), codec.readFrame(in));
    assertEquals(sample("m-B"), codec.readFrame(in));
    assertEquals(-1, in.read());
  }

  @Test void fiveCoalescedFramesReadSequentially() throws Exception {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    for (int i = 0; i < 5; i++) buf.write(codec.encode(sample("m-" + i)));
    InputStream in = new ByteArrayInputStream(buf.toByteArray());
    for (int i = 0; i < 5; i++) assertEquals(sample("m-" + i), codec.readFrame(in));
    assertEquals(-1, in.read());
  }

  @Test void writeFrameThenReadFrameRoundTrip() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    codec.writeFrame(out, sample("m-rt"));
    Envelope got = codec.readFrame(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(sample("m-rt"), got);
  }

  @Test void writeFrameEmitsBigEndianLengthPrefix() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    codec.writeFrame(out, sample("m-prefix"));
    byte[] wire = out.toByteArray();
    int declared = ByteBuffer.wrap(wire, 0, 4).getInt();
    assertEquals(wire.length - Protocol.PREFIX_BYTES, declared);
    assertTrue(declared >= 1 && declared <= Protocol.MAX_FRAME_BYTES);
  }

  @Test void emptyStreamThrowsEof() {
    assertThrows(EOFException.class, () -> codec.readFrame(new ByteArrayInputStream(new byte[0])));
  }

  @Test void truncatedHeaderThrowsIo() {
    byte[] wire = new byte[]{0, 0};
    assertThrows(IOException.class, () -> codec.readFrame(new ByteArrayInputStream(wire)));
  }

  @Test void truncatedBodyThrowsIo() throws Exception {
    byte[] wire = codec.encode(sample("m-trunc"));
    byte[] cut = Arrays.copyOf(wire, wire.length - 5);
    assertThrows(IOException.class, () -> codec.readFrame(new ByteArrayInputStream(cut)));
  }

  @Test void oversizeDeclaredLengthRejectedBeforeBodyReadOrAllocation() {
    for (int bad : new int[]{Protocol.MAX_FRAME_BYTES + 1, 0x7FFFFFFF, 0xFFFFFFFF}) {
      HeaderOnlyStream in = new HeaderOnlyStream(be(bad));
      FrameException ex = assertThrows(FrameException.class, () -> codec.readFrame(in));
      assertNotNull(ex.getMessage());
      assertFalse(in.bodyReadAttempted, "must reject before reading/allocating body, len=" + bad);
    }
  }

  @Test void zeroLengthRejected() {
    assertThrows(FrameException.class,
        () -> codec.readFrame(new ByteArrayInputStream(be(0))));
  }

  @Test void negativeLengthRejected() {
    // 0xFFFFFFFF as signed int is -1; must be rejected as out of range, not allocated.
    HeaderOnlyStream in = new HeaderOnlyStream(be(0xFFFFFFFF));
    assertThrows(FrameException.class, () -> codec.readFrame(in));
    assertFalse(in.bodyReadAttempted);
  }
}
