package aegis.networklab;

/** Advisory hint callback. Implementations must treat hints as untrusted triggers, not content. */
@FunctionalInterface
public interface HintListener {
  void onHint(AlertHint hint);
}
