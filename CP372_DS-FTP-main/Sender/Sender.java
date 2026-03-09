import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;


public class Sender {

    private static final int MAX_PAYLOAD = DSPacket.MAX_PAYLOAD_SIZE; // 124

    /**set to true to see sequence nums **/
    private static final boolean OUTPUT = true;

    public static void main(String[] args) {
        if (args.length != 5 && args.length != 6) {
            System.out.println("Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            return;
        }

        String rcvIpStr = args[0];
        int rcvDataPort = parsePort(args[1]);
        int senderAckPort = parsePort(args[2]);
        String inputFile = args[3];
        int timeoutMs = parseInt(args[4]);

        boolean useGBN = (args.length == 6);
        int windowSize = 0;

        if (useGBN) {
            windowSize = parseInt(args[5]);
            if (windowSize <= 0 || windowSize > 128 || (windowSize % 4 != 0)) {
                System.out.println("Invalid window_size (must be multiple of 4 and <= 128).");
                return;
            }
        }

        if (rcvDataPort < 1 || senderAckPort < 1 || timeoutMs <= 0) {
            System.out.println("Invalid arguments.");
            return;
        }

        DatagramSocket sock = null;
        FileInputStream fis = null;

        try {
            InetAddress rcvIp = InetAddress.getByName(rcvIpStr);
            sock = new DatagramSocket(senderAckPort);              // sender listens here for ACKs
            sock.setSoTimeout(timeoutMs);

            File f = new File(inputFile);
            if (!f.exists() || !f.isFile()) {
                System.out.println("Input file not found.");
                return;
            }

            // Build DATA packet list (abs index 0..dataCount-1), seq starts at 1
            List<DSPacket> dataPackets = buildDataPackets(inputFile);

            // Determine EOT seq
            int eotSeq;
            if (dataPackets.isEmpty()) {
                eotSeq = 1; // empty-file rule: EOT seq=1 after handshake
            } else {
                int lastDataSeq = dataPackets.get(dataPackets.size() - 1).getSeqNum() % 128;
                eotSeq = (lastDataSeq + 1) % 128;
            }

            // Start timer from FIRST SOT send to EOT ACK receive
            long startNs = System.nanoTime();

            // Handshake: send SOT(0), wait for ACK(0) with retries / failure rule
            if (OUTPUT) System.out.println("[Sender] --- Handshake ---");
            if (!sendControlAndAwaitAck(sock, rcvIp, rcvDataPort, DSPacket.TYPE_SOT, 0, timeoutMs, "SOT")) {
                // sendControlAndAwaitAck already prints error message on critical failure
                return;
            }

            // Transfer data
            if (OUTPUT) System.out.println("[Sender] --- Data transfer (" + (useGBN ? "GBN, window=" + windowSize : "Stop-and-Wait") + ") ---");
            if (!useGBN) {
                if (!stopAndWaitTransfer(sock, rcvIp, rcvDataPort, dataPackets)) {
                    return;
                }
            } else {
                if (!goBackNTransfer(sock, rcvIp, rcvDataPort, dataPackets, windowSize)) {
                    return;
                }
            }

            // Teardown: send EOT(eotSeq), wait for ACK(eotSeq)
            if (OUTPUT) System.out.println("[Sender] --- Teardown ---");
            if (!sendControlAndAwaitAck(sock, rcvIp, rcvDataPort, DSPacket.TYPE_EOT, eotSeq, timeoutMs, "EOT")) {
                return;
            }

            long endNs = System.nanoTime();
            double seconds = (endNs - startNs) / 1_000_000_000.0;
            System.out.printf("Total Transmission Time: %.2f seconds%n", seconds);

        } catch (Exception e) {
            System.out.println("Sender error: " + e.getMessage());
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
            if (sock != null) {
                sock.close();
            }
        }
    }

    // -------------------------
    // Stop-and-Wait
    // -------------------------
    private static boolean stopAndWaitTransfer(DatagramSocket sock, InetAddress rcvIp, int rcvDataPort, List<DSPacket> dataPackets)
            throws IOException {

        int timeoutsForCurrent = 0;

        for (int i = 0; i < dataPackets.size(); i++) {
            DSPacket p = dataPackets.get(i);
            boolean acked = false;

            while (!acked) {
                if (OUTPUT) System.out.println("[Sender] Sent DATA (seq=" + (p.getSeqNum() % 128) + ")");
                sendPacket(sock, rcvIp, rcvDataPort, p);

                try {
                    int ackSeq = receiveAckSeq(sock);
                    if ((ackSeq % 128) == (p.getSeqNum() % 128)) {
                        if (OUTPUT) System.out.println("[Sender] Received ACK(" + ackSeq + ")");
                        acked = true;
                        timeoutsForCurrent = 0; // progress
                    }
                    // else ignore stray ACKs
                } catch (SocketTimeoutException te) {
                    timeoutsForCurrent++;
                    if (OUTPUT) System.out.println("[Sender] Timeout, retransmitting DATA (seq=" + (p.getSeqNum() % 128) + ") [attempt " + timeoutsForCurrent + "/3]");
                    if (timeoutsForCurrent >= 3) {
                        System.out.println("Unable to transfer file.");
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // -------------------------
    // Go-Back-N
    // -------------------------
    private static boolean goBackNTransfer(DatagramSocket sock, InetAddress rcvIp, int rcvDataPort,
                                          List<DSPacket> dataPackets, int windowSize)
            throws IOException {

        int total = dataPackets.size();
        int baseAbs = 0;
        int nextAbs = 0;

        int timeoutsForBase = 0;

        // We will send “new” packets as we can, permuting every group of 4 newly-sent packets
        // according to ChaosEngine.permutePackets(...) (GBN only).
        while (baseAbs < total) {

            // Send new packets while window allows
            while (nextAbs < total && (nextAbs - baseAbs) < windowSize) {
                // Send in groups of 4 consecutive packets (if available), permuted:
                int remainingWindow = windowSize - (nextAbs - baseAbs);
                int remainingTotal = total - nextAbs;
                int group = Math.min(4, Math.min(remainingWindow, remainingTotal));

                if (group == 4) {
                    List<DSPacket> groupList = new ArrayList<>(4);
                    groupList.add(dataPackets.get(nextAbs));
                    groupList.add(dataPackets.get(nextAbs + 1));
                    groupList.add(dataPackets.get(nextAbs + 2));
                    groupList.add(dataPackets.get(nextAbs + 3));

                    List<DSPacket> perm = ChaosEngine.permutePackets(groupList);
                    for (int k = 0; k < perm.size(); k++) {
                        DSPacket pk = perm.get(k);
                        if (OUTPUT) System.out.println("[Sender] Sent DATA (seq=" + (pk.getSeqNum() % 128) + ")");
                        sendPacket(sock, rcvIp, rcvDataPort, pk);
                    }
                    nextAbs += 4;
                } else {
                    // Fewer than 4 left -> send in normal order
                    for (int k = 0; k < group; k++) {
                        DSPacket pk = dataPackets.get(nextAbs + k);
                        if (OUTPUT) System.out.println("[Sender] Sent DATA (seq=" + (pk.getSeqNum() % 128) + ")");
                        sendPacket(sock, rcvIp, rcvDataPort, pk);
                    }
                    nextAbs += group;
                }
            }

            // Wait for ACKs; timeout => retransmit from baseAbs
            try {
                int ackSeq = receiveAckSeq(sock);
                if (ackSeq < 0) continue; // non-ACK packet ignored
                ackSeq = ackSeq % 128;

                // Compute how far ahead this ACK is from baseSeq (mod 128), then convert to abs
                int baseSeq = dataPackets.get(baseAbs).getSeqNum() % 128;
                int dist = (ackSeq - baseSeq + 128) % 128;

                // dist must be within current outstanding range to count
                int ackedAbs = baseAbs + dist;

                if (ackedAbs >= baseAbs && ackedAbs < nextAbs) {
                    int newBase = ackedAbs + 1;
                    if (newBase > baseAbs) {
                        if (OUTPUT) System.out.println("[Sender] Received ACK(" + ackSeq + ") (window advances)");
                        baseAbs = newBase;
                        timeoutsForBase = 0; // progress resets timeout counter
                    }
                }
                // else ignore stray ACKs

            } catch (SocketTimeoutException te) {
                timeoutsForBase++;
                int baseSeq = dataPackets.get(baseAbs).getSeqNum() % 128;
                if (OUTPUT) System.out.println("[Sender] Timeout, retransmitting window from seq=" + baseSeq + " [attempt " + timeoutsForBase + "/3]");
                if (timeoutsForBase >= 3) {
                    System.out.println("Unable to transfer file.");
                    return false;
                }

                // Retransmit entire window from baseAbs up to nextAbs-1 (or baseAbs+windowSize-1)
                int end = Math.min(nextAbs, baseAbs + windowSize);
                int abs = baseAbs;

                while (abs < end) {
                    int remaining = end - abs;
                    int group = Math.min(4, remaining);

                    if (group == 4) {
                        List<DSPacket> groupList = new ArrayList<>(4);
                        groupList.add(dataPackets.get(abs));
                        groupList.add(dataPackets.get(abs + 1));
                        groupList.add(dataPackets.get(abs + 2));
                        groupList.add(dataPackets.get(abs + 3));

                        List<DSPacket> perm = ChaosEngine.permutePackets(groupList);
                        for (int k = 0; k < perm.size(); k++) {
                            sendPacket(sock, rcvIp, rcvDataPort, perm.get(k));
                        }
                        abs += 4;
                    } else {
                        for (int k = 0; k < group; k++) {
                            sendPacket(sock, rcvIp, rcvDataPort, dataPackets.get(abs + k));
                        }
                        abs += group;
                    }
                }
            }
        }

        return true;
    }

    // -------------------------
    // Handshake/Teardown helper
    // -------------------------
    private static boolean sendControlAndAwaitAck(DatagramSocket sock, InetAddress rcvIp, int rcvDataPort,
                                                 byte type, int seq, int timeoutMs, String label)
            throws IOException {

        DSPacket ctrl = new DSPacket(type, seq, null);

        int timeouts = 0;
        boolean acked = false;

        while (!acked) {
            if (OUTPUT) System.out.println("[Sender] Sent " + label + " (seq=" + (seq % 128) + ")");
            sendPacket(sock, rcvIp, rcvDataPort, ctrl);

            try {
                int ackSeq = receiveAckSeq(sock);
                if ((ackSeq % 128) == (seq % 128)) {
                    if (OUTPUT) System.out.println("[Sender] Received ACK(" + (seq % 128) + ")");
                    acked = true;
                }
            } catch (SocketTimeoutException te) {
                timeouts++;
                if (OUTPUT) System.out.println("[Sender] Timeout, retransmitting " + label + " [attempt " + timeouts + "/3]");
                if (timeouts >= 3) {
                    System.out.println("Unable to transfer file.");
                    return false;
                }
            }
        }
        return true;
    }

    // -------------------------
    // Packet IO helpers
    // -------------------------
    private static void sendPacket(DatagramSocket sock, InetAddress rcvIp, int rcvDataPort, DSPacket p)
            throws IOException {
        byte[] bytes = p.toBytes(); // always 128 bytes
        DatagramPacket dp = new DatagramPacket(bytes, bytes.length, rcvIp, rcvDataPort);
        sock.send(dp);
    }

    private static int receiveAckSeq(DatagramSocket sock)
            throws IOException, SocketTimeoutException {

        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        sock.receive(dp);

        byte[] raw = new byte[DSPacket.MAX_PACKET_SIZE];
        System.arraycopy(dp.getData(), dp.getOffset(), raw, 0, DSPacket.MAX_PACKET_SIZE);

        DSPacket pkt = new DSPacket(raw);
        if (pkt.getType() != DSPacket.TYPE_ACK) {
            // ignore non-ACKs by throwing (caller will just loop)
            // but to keep it simple: treat as "not useful ACK"
            return -1;
        }
        return pkt.getSeqNum() % 128;
    }

    // -------------------------
    // Build DATA packets
    // -------------------------
    private static List<DSPacket> buildDataPackets(String inputFile) throws IOException {
        List<DSPacket> packets = new ArrayList<>();

        FileInputStream fis = new FileInputStream(inputFile);
        try {
            int seq = 1;
            byte[] buf = new byte[MAX_PAYLOAD];

            int read;
            while ((read = fis.read(buf)) != -1) {
                if (read == 0) {
                    // should not happen for FileInputStream, but safe
                } else {
                    byte[] payload = new byte[read];
                    System.arraycopy(buf, 0, payload, 0, read);
                    packets.add(new DSPacket(DSPacket.TYPE_DATA, seq, payload));
                    seq = (seq + 1) % 128;
                }
            }
        } finally {
            fis.close();
        }

        return packets;
    }

    // -------------------------
    // Parse helpers
    // -------------------------
    private static int parsePort(String s) {
        int v = parseInt(s);
        if (v < 1 || v > 65535) return -1;
        return v;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return -1; }
    }
}