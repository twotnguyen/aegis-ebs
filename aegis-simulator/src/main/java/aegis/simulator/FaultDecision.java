package aegis.simulator;

/**
 * Per-delivery fault decision. {@code ackCopies} counts total ACK_RECEIVED frames
 * (1 = normal, 2+ = duplicate injection; each copy gets a fresh messageId per the
 * wire contract). {@code disconnectAfter} drops the connection after this delivery,
 * forcing the agent to reconnect + SYNC.
 */
public record FaultDecision(boolean dropAck, long delayMs, int ackCopies, boolean disconnectAfter) {}
