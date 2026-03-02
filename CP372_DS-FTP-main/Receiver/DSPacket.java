import java.nio.ByteBuffer;

/**
 * DS-FTP Packet Wrapper
 * --------------------
 * Handles conversion between protocol fields and raw 128-byte UDP datagrams.
 *
 * All packets:
 * - Are exactly 128 bytes on the wire
 * - Use big-endian encoding for the payload length
 * - Follow the DS-FTP packet contract strictly
 */
public class DSPacket {

    // Fixed packet sizes
    public static final int MAX_PACKET_SIZE  = 128;
    public static final int MAX_PAYLOAD_SIZE = 124;

    // Packet types
    public static final byte TYPE_SOT  = 0;
    public static final byte TYPE_DATA = 1;
    public static final byte TYPE_ACK  = 2;
    public static final byte TYPE_EOT  = 3;

    // Header fields
    private byte type;
    private byte seqNum;
    private short length;   // Payload length in bytes (0–124)
    private byte[] payload;

    /**
     * Constructor for creating a packet to SEND.
     *
     * Usage:
     * - DATA packets: provide payload bytes (length 1–124)
     * - SOT / ACK / EOT packets: pass null or empty data
     *
     * @param type Packet type (TYPE_SOT, TYPE_DATA, TYPE_ACK, TYPE_EOT)
     * @param seqNum Sequence number (automatically modulo 128)
     * @param data Payload bytes (null or empty for control packets)
     */
    public DSPacket(byte type, int seqNum, byte[] data) {
        this.type = type;
        this.seqNum = (byte) (seqNum % 128);

        // Control packets should have no payload
        this.payload = (data != null) ? data : new byte[0];
        this.length = (short) this.payload.length;
    }

    /**
     * Constructor for parsing a RECEIVED 128-byte datagram.
     *
     * @param rawBytes The raw byte array from DatagramPacket
     * @throws IllegalArgumentException if payload length is invalid
     */
    public DSPacket(byte[] rawBytes) {

        ByteBuffer bb = ByteBuffer.wrap(rawBytes);

        this.type   = bb.get();
        this.seqNum = bb.get();
        this.length = bb.getShort();

        // Defensive validation of payload length
        if (this.length < 0 || this.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException(
                "Invalid payload length: " + this.length
            );
        }

        this.payload = new byte[this.length];
        bb.get(this.payload);
    }

    /**
     * Converts this packet into a fixed-size 128-byte array
     * suitable for DatagramPacket transmission.
     *
     * Note:
     * - ByteBuffer.allocate() zero-fills unused bytes automatically.
     *
     * @return 128-byte array representing the packet
     */
    public byte[] toBytes() {

        ByteBuffer bb = ByteBuffer.allocate(MAX_PACKET_SIZE);

        bb.put(type);
        bb.put(seqNum);
        bb.putShort(length);
        bb.put(payload);

        // Remaining bytes are automatically zero-filled
        return bb.array();
    }

    // ------------------------
    // Getters
    // ------------------------

    public byte getType() {
        return type;
    }

    /**
     * Returns the sequence number as an unsigned integer (0–255).
     * This avoids Java's signed byte interpretation.
     */
    public int getSeqNum() {
        return seqNum & 0xFF;
    }

    public int getLength() {
        return length;
    }

    public byte[] getPayload() {
        return payload;
    }
}
