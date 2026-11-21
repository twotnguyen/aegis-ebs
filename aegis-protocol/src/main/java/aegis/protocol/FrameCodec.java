package aegis.protocol;
import java.io.InputStream;
import java.io.OutputStream;
/**
 * Wire contract: uint32 big-endian length L (1..65536) + L bytes strict UTF-8 JSON.
 * Implementations MUST reject oversized frames before allocation, handle partial reads
 * (readFully) and coalesced frames, and validate UTF-8/JSON/version/enum.
 */
public interface FrameCodec {
  byte[] encode(Envelope e) throws FrameException;
  Envelope decode(byte[] frame) throws FrameException;
  Envelope readFrame(InputStream in) throws FrameException, java.io.IOException;
  void writeFrame(OutputStream out, Envelope e) throws FrameException, java.io.IOException;
}
