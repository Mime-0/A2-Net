import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;


public class Receiver {

  
    private static final int RECEIVE_WINDOW_SIZE = 80; // multiple of 4, <= 128

    
    private static final boolean OUTPUT = true;

    public static void main(String[] args) {
        if (args.length != 5) {
            System.out.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
            return;
        }

        String senderIpStr = args[0];
        int senderAckPort = parsePort(args[1]);
        int rcvDataPort = parsePort(args[2]);
        String outputFile = args[3];
        int rn = parseInt(args[4]);

        if (senderAckPort < 1 || rcvDataPort < 1 || rn < 0) {
            System.out.println("Invalid arguments.");
            return;
        }

        DatagramSocket socket = null;
        FileOutputStream fos = null;

        // Receiver protocol state
        boolean connected = false;
        boolean dataPhaseLogged = false;
        int expectedSeq = 1;      // after SOT(0), first DATA is seq 1
        int lastInOrderAck = 0;   // cumulative ACK = last contiguous delivered seq
        int ackCount = 0;         // "intended ACKs so far" (1-indexed rule implemented by increment-before-send)

        // Out-of-order buffer (seq space is 0..127)
        boolean[] received = new boolean[128];
        byte[][] payloadBuf = new byte[128][];

        try {
            InetAddress senderIp = InetAddress.getByName(senderIpStr);
            socket = new DatagramSocket(rcvDataPort);

            byte[] inBuf = new byte[DSPacket.MAX_PACKET_SIZE];
            DatagramPacket inPkt = new DatagramPacket(inBuf, inBuf.length);

            while (true) {
                socket.receive(inPkt);

                // Defensive copy of exactly 128 bytes
                byte[] raw = new byte[DSPacket.MAX_PACKET_SIZE];
                System.arraycopy(inPkt.getData(), inPkt.getOffset(), raw, 0, DSPacket.MAX_PACKET_SIZE);

                DSPacket pkt;
                try {
                    pkt = new DSPacket(raw);
                } catch (IllegalArgumentException bad) {
                    // Bad length field -> ignore
                    continue;
                }

                int type = pkt.getType();
                int seq = pkt.getSeqNum() % 128;

                if (!connected) {
                    // Expect SOT (type 0, seq 0)
                    if (type == DSPacket.TYPE_SOT && seq == 0) {
                        if (OUTPUT) System.out.println("[Receiver] --- Handshake ---");
                        if (OUTPUT) System.out.println("[Receiver] Received SOT (seq=0)");
                        fos = new FileOutputStream(outputFile);

                        connected = true;
                        expectedSeq = 1;
                        lastInOrderAck = 0;
                        clearBuffer(received, payloadBuf);

                        // ACK SOT(0) (may be dropped by chaos)
                        ackCount = sendAck(socket, senderIp, senderAckPort, 0, rn, ackCount);
                    }
                    // ignore anything else before SOT
                    continue;
                }

                // If connected, handle packets
                if (type == DSPacket.TYPE_SOT) {
                    // If sender retransmits SOT because its ACK was dropped, re-ACK it
                    if (seq == 0) {
                        if (OUTPUT) System.out.println("[Receiver] Received SOT (seq=0) [retransmit]");
                        ackCount = sendAck(socket, senderIp, senderAckPort, 0, rn, ackCount);
                    }
                    continue;
                }

                if (type == DSPacket.TYPE_DATA) {
                    if (OUTPUT && !dataPhaseLogged) {
                        System.out.println("[Receiver] --- Data transfer ---");
                        dataPhaseLogged = true;
                    }
                    if (OUTPUT) System.out.println("[Receiver] Received DATA (seq=" + seq + ")");
                    if (inWindow(seq, expectedSeq, RECEIVE_WINDOW_SIZE)) {
                        // Buffer if new
                        if (!received[seq]) {
                            received[seq] = true;
                            payloadBuf[seq] = pkt.getPayload(); // payload length already validated by DSPacket
                        }

                        // Deliver in-order while possible
                        while (received[expectedSeq]) {
                            byte[] pl = payloadBuf[expectedSeq];
                            if (pl != null && pl.length > 0) {
                                fos.write(pl);
                            }
                            received[expectedSeq] = false;
                            payloadBuf[expectedSeq] = null;

                            expectedSeq = (expectedSeq + 1) % 128;
                            lastInOrderAck = (expectedSeq + 127) % 128; // expectedSeq - 1 (mod 128)
                        }

                        // Send cumulative ACK (last contiguous delivered)
                        ackCount = sendAck(socket, senderIp, senderAckPort, lastInOrderAck, rn, ackCount);

                    } else {
                        // Out of window -> discard & re-send last cumulative ACK
                        if (OUTPUT) System.out.println("[Receiver] Out of window, re-sending ACK(" + lastInOrderAck + ")");
                        ackCount = sendAck(socket, senderIp, senderAckPort, lastInOrderAck, rn, ackCount);
                    }
                    continue;
                }

                if (type == DSPacket.TYPE_EOT) {
                    if (OUTPUT) System.out.println("[Receiver] --- Teardown ---");
                    if (OUTPUT) System.out.println("[Receiver] Received EOT (seq=" + seq + ")");
                    // Only accept EOT if it's exactly the next expected sequence
                    if (seq == expectedSeq) {
                        ackCount = sendAck(socket, senderIp, senderAckPort, seq, rn, ackCount);

                        // Close and exit
                        fos.flush();
                        fos.close();
                        fos = null;
                        if (OUTPUT) System.out.println("[Receiver] Transfer complete. Output file closed.");
                        break;
                    } else {
                        // Not ready -> re-send cumulative ACK
                        if (OUTPUT) System.out.println("[Receiver] Not ready for EOT, re-sending ACK(" + lastInOrderAck + ")");
                        ackCount = sendAck(socket, senderIp, senderAckPort, lastInOrderAck, rn, ackCount);
                    }
                    continue;
                }

                // Ignore ACK packets or unknown types arriving at receiver
            }

        } catch (Exception e) {
            System.out.println("Receiver error: " + e.getMessage());
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
            if (socket != null) {
                socket.close();
            }
        }
    }

    // ---------------- Helpers ----------------

    private static int parsePort(String s) {
        int v = parseInt(s);
        if (v < 1 || v > 65535) return -1;
        return v;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return -1; }
    }

    private static void clearBuffer(boolean[] received, byte[][] payloadBuf) {
        for (int i = 0; i < 128; i++) {
            received[i] = false;
            payloadBuf[i] = null;
        }
    }

    /**
     * True if seq is within [expected, expected + windowSize) under modulo-128 arithmetic.
     */
    private static boolean inWindow(int seq, int expected, int windowSize) {
        int s = seq % 128;
        int e = expected % 128;
        int dist = (s - e + 128) % 128;
        return dist < windowSize;
    }

    /**
     * Sends an ACK unless ChaosEngine decides to drop it.
     * Returns updated ackCount.
     *
     * Chaos rule requires:
     * - counter is 1-indexed
     * - applies to ALL ACKs (SOT, DATA, EOT)
     * - if RN=X, every Xth intended ACK is dropped
     */
    private static int sendAck(
            DatagramSocket socket,
            InetAddress senderIp,
            int senderAckPort,
            int ackSeq,
            int rn,
            int ackCount
    ) throws IOException {

        int newCount = ackCount + 1; // "intended" to send next ACK

        boolean drop = ChaosEngine.shouldDrop(newCount, rn);
        if (!drop) {
            DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, ackSeq, null);
            byte[] out = ack.toBytes();
            DatagramPacket outPkt = new DatagramPacket(out, out.length, senderIp, senderAckPort);
            socket.send(outPkt);
            if (OUTPUT) System.out.println("[Receiver] Sent ACK(" + ackSeq + ")");
        } else {
            if (OUTPUT) System.out.println("[Receiver] ACK(" + ackSeq + ") dropped (simulated loss, RN=" + rn + ")");
        }

        return newCount;
    }
}