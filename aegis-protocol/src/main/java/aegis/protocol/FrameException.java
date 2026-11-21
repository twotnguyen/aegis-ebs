package aegis.protocol;
public class FrameException extends Exception {
  public FrameException(String m){ super(m); }
  public FrameException(String m, Throwable c){ super(m,c); }
}
