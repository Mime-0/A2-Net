import java.util.ArrayList;
import java.util.List;

/**
 * ChaosEngine Utility
 * -------------------
 * Standardizes the "Chaos Factor" (unreliability) requirements for CP 372.
 *
 * IMPORTANT:
 * - This class MUST be used as provided.
 * - Do NOT modify the logic or permutation order.
 */
public class ChaosEngine {

    /**
     * GBN SENDER LOGIC
     * ----------------
     * Permutes a group of 4 consecutive packets.
     *
     * Rule:
     *   (i, i+1, i+2, i+3)  -->  (i+2, i, i+3, i+1)
     *
     * Notes:
     * - This method should ONLY be applied to groups of exactly 4 packets.
     * - If fewer than 4 packets remain (e.g., at end of file), the list is
     *   returned unchanged and sent in normal order.
     *
     * @param windowGroup A list containing the next packets to send
     * @return A permuted list following the chaos rule
     */
    public static List<DSPacket> permutePackets(List<DSPacket> windowGroup) {

        // Defensive: Only permute when exactly 4 packets are present
        if (windowGroup.size() != 4) {
            return windowGroup;
        }

        List<DSPacket> shuffled = new ArrayList<>(4);

        // Required permutation order
        shuffled.add(windowGroup.get(2)); // i+2
        shuffled.add(windowGroup.get(0)); // i
        shuffled.add(windowGroup.get(3)); // i+3
        shuffled.add(windowGroup.get(1)); // i+1

        return shuffled;
    }

    /**
     * RECEIVER LOGIC
     * --------------
     * Determines whether an ACK should be dropped.
     *
     * ACK DROP RULE:
     * - The counter is 1-indexed.
     * - Applies to ALL ACKs (SOT, DATA, and EOT).
     * - If RN = X, then every Xth ACK is dropped.
     *
     * @param ackCount Total number of ACKs the receiver has INTENDED to send so far
     *                 (1-indexed, includes SOT and EOT ACKs).
     * @param rn Reliability Number from the command line.
     * @return true if the ACK should be dropped (simulated loss).
     */
    public static boolean shouldDrop(int ackCount, int rn) {

        // RN <= 0 means no ACKs are lost
        if (rn <= 0) {
            return false;
        }

        // Drop every rn-th ACK
        return (ackCount % rn == 0);
    }
}
