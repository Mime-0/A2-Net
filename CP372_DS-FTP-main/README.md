# CP372 – Computer Networks (Winter 2026)

## Assignment 2: The Deep Space File Transfer Protocol (DS-FTP)

**Due:** **Friday, March 6, 2026 at 11:59 PM**  
**Language:** Java (UDP – `DatagramSocket` API)  
**Late Policy:** As stated in the course syllabus

* * *

## 1\. Mission Briefing

Standard TCP is too *chatty* and inefficient for high-latency, unreliable deep-space links.  
As an engineer at the **Wilfrid Laurier Space Agency**, your mission is to design and implement a **custom Reliable Data Transfer (RDT) protocol over UDP** capable of transferring files from a **Mars Rover (Sender)** to an **Earth Station (Receiver)**.

You will implement **two protocol variants**:

1.  **Stop-and-Wait RDT 3.0** – simple, reliable, one-packet-at-a-time delivery
    
2.  **Go-Back-N (GBN)** – pipelined, window-based delivery with buffering and cumulative acknowledgments
    

This assignment focuses on **protocol correctness**, **fault tolerance**, and **clean protocol design**, not raw throughput alone.

* * *

## 2\. Technical Contract: DS-FTP Packet Format

All communication occurs over **UDP**, but reliability is provided **entirely by your protocol**.

### 2.1 Packet Size Requirement

- **Every UDP datagram must be exactly 128 bytes**
    
- You **must use the provided `DSPacket.java` starter class**
    
- No deviation from the packet layout is allowed
    

### 2.2 Packet Header Layout

| Offset (Bytes) | Field | Description |
| --- | --- | --- |
| 0   | Type | `0=SOT`, `1=DATA`, `2=ACK`, `3=EOT` |
| 1   | SeqNum | Sequence number (Modulo 128) |
| 2–3 | Length | Payload length (big-endian `short`) |
| 4–127 | Payload | File data (max 124 bytes) |

### 2.3 Control Packet Rules

- **SOT, ACK, and EOT packets**
    
    - `Length = 0`
        
    - Payload is ignored (recommended: zero-filled)
        
- **DATA packets**
    
    - `Length = 1…124`
        
    - Payload contains raw file bytes
        
- All packets **remain 128 bytes regardless of content**
    

* * *

## 3\. Sequence Numbers

- Sequence numbers are **Modulo 128**  
    `0, 1, 2, …, 127, 0, 1, …`
    
- **SOT always uses Seq = 0**
    
- **First DATA packet uses Seq = 1**
    
- **EOT uses (last DATA seq + 1) mod 128**
    
- All sequence comparisons must respect **wrap-around arithmetic**
    

* * *

## 4\. Protocol Operation

### Phase 1: Handshake

1.  Receiver starts and listens on `<rcv_data_port>`
    
2.  Sender sends **SOT** packet (Type 0, Seq 0)
    
3.  Receiver replies with **ACK** (Type 2, Seq 0)
    
4.  Connection is established
    

* * *

### Phase 2: Data Transfer

#### Data Limits

- Each DATA packet carries **exactly 124 bytes**, except the final packet
    
- Files are transferred **as raw binary** (no character streams)
    

#### Empty File Case

If the input file is **0 bytes**:

- Sender sends **EOT with Seq = 1** immediately after handshake
    
- Receiver ACKs and closes
    

* * *

## 5\. Stop-and-Wait RDT 3.0

### Sender Behavior

- Send **one packet**
    
- Wait for its **specific ACK**
    
- On timeout → retransmit
    
- Increment sequence only after correct ACK
    

### Receiver Behavior

- Maintain `expectedSeq`
    
- If received `Seq == expectedSeq`:
    
    - Write payload
        
    - Send ACK for that Seq
        
    - Increment `expectedSeq`
        
- If duplicate or out-of-order:
    
    - Do **not** write
        
    - Re-send ACK for **last in-order packet**
        

* * *

## 6\. Go-Back-N (GBN)

### Window Rules

- Sender window size `N`:
    
    - Must be a **multiple of 4**
        
    - `N ≤ 128`
        
- Receiver window matches sender window
    
- Sequence numbers wrap modulo 128
    

### Sender Behavior

- Maintains:
    
    - `base` (oldest unACKed packet)
        
    - `nextSeq`
        
- Sends packets while `nextSeq < base + N`
    
- Retransmits **entire window from `base`** on timeout
    

### Receiver Behavior (Buffered + Cumulative ACKs)

The receiver uses **buffering** (required).

- Maintain:
    
    - `expectedSeq`
        
    - Buffer for out-of-order packets within window
        
- On packet arrival:
    
    - If within receive window:
        
        - Buffer if not already received
            
        - Deliver **in order** while possible
            
    - Send **one cumulative ACK**:
        
        - ACK = largest contiguous in-order Seq delivered
- Packets below or above window:
    
    - Discard
        
    - Re-send cumulative ACK
        

#### Example

Received packets: `1, 2, 4` → ACK 2  
Then packet 3 arrives → deliver 3 and 4 → ACK 4

* * *

## 7\. Timeout and Critical Failure

- Timeout uses `DatagramSocket.setSoTimeout(timeout_ms)`
    
- **Timeout counter applies to the same base packet**
    
- Counter resets when **any ACK advances the window**
    

### Critical Failure Rule

If **3 consecutive timeouts occur for the same packet** without progress:

`Unable to transfer file.`

Sender must immediately terminate.

* * *

## 8\. Phase 3: Teardown

1.  Sender sends **EOT**
    
2.  Receiver ACKs EOT
    
3.  Receiver closes file and exits
    
4.  Sender prints:
    

`Total Transmission Time: X.XX seconds`

(Time measured from sending SOT to receiving EOT ACK)

* * *

## 9\. Chaos Factor: Unreliability Simulation

You **must use `ChaosEngine.java`** exactly as provided.

### 9.1 Lost ACKs (Receiver Side)

Reliability Number `RN`:

- `RN = 0` → No ACKs lost
    
- `RN = X` → Every **Xth ACK** is dropped
    
- Applies to **all ACKs**, including SOT and EOT
    

### 9.2 Out-of-Order Delivery (Sender Side – GBN Only)

For every group of **4 consecutive packets**  
`(i, i+1, i+2, i+3)`

Transmit in this order:

`i+2 → i → i+3 → i+1`

- Sequence numbers remain unchanged
    
- If fewer than 4 packets remain at the end, send in normal order
    

* * *

## 10\. Command-Line Interface (Exact Syntax Required)

### Receiver

`java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>`

### Sender

`java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]`

- Omit `window_size` → Stop-and-Wait
    
- Provide `window_size` → Go-Back-N
    

### Port Roles (Important)

- Receiver listens on `<rcv_data_port>`
    
- Sender listens on `<sender_ack_port>`
    
- Receiver sends ACKs to `<sender_ip>:<sender_ack_port>`
    
- Sender sends data to `<rcv_ip>:<rcv_data_port>`
    

* * *

## 11\. Provided Starter Code

To ensure consistency across implementations and grading, the following starter utilities are **provided and must be used without modification**:

- `DSPacket.java` — Enforces the 128-byte packet contract and sequence handling
    
- `ChaosEngine.java` — Standardizes ACK loss and packet permutation behavior
    

These files are available at:  
**https://github.com/MustafaDaraghmeh/CP372_DS-FTP**

Students must include these files **unchanged** in their Sender and Receiver directories. Any deviation from the provided logic may result in incorrect protocol behavior and grading penalties.

* * *

## 12\. Submission Requirements

### Code (ZIP)

- File name: `CP372_A2_Group_#ID.zip`
    
- Two directories:
    
    ```
    /Sender/
    ├── DSPacket.java
    ├── ChaosEngine.java
    └── Sender.java
    /Receiver/
    ├── DSPacket.java
    ├── ChaosEngine.java
    └── Receiver.java
    ```
    
- **Only `.java` files**
    
- **Default package only**
    
- Must compile via:
    
    `cd Sender && javac *.javacd Receiver && javac *.java`
    

* * *

### 1-Page Report (PDF)

Include:

1.  Brief description of **SOT and EOT packet formats**
    
2.  **Performance Table** comparing:
    
    - Stop-and-Wait
        
    - GBN with window sizes **20, 40, 80**
        
    - File sizes:
        
        - Small (< 4 KB)
            
        - Large (0.2–2 MB)
            
    - Reliability numbers: `RN = 0, 5, 100`
        
3.  Each result averaged over **3 runs**
    
***

### Video Demonstration (Mandatory)

Each group must submit a **screen-recorded video demo (maximum 5 minutes)** showing their DS-FTP implementation in action.

The video **must clearly demonstrate**:

1.  Successful handshake (SOT → ACK)
    
2.  File transfer using:
    
    - Stop-and-Wait
        
    - Go-Back-N
        
3.  Correct handling of at least **one ACK loss** (RN > 0)
    
4.  Proper connection teardown (EOT → ACK)
    
The demo should include:

- Running the **Receiver** and **Sender** from the command line
    
- Console output showing sequence numbers and ACK behavior
    
- Confirmation that the output file matches the input file
    

**Time limit:** 5 minutes (strict).  
**Format:** MP4 or a shareable link (e.g., OneDrive, Google Drive).  
**Submission:** Upload the video or link via **MyLS → Assignment 2 Dropbox**.

**Important:** Failure to submit a video demo will result in a **−20% penalty** on the Assignment 2 grade, regardless of code or report quality.

* * *

## 13\. Academic Integrity

- All submitted code must be **your own**
    
- Discussion of concepts is allowed
    
- Sharing or copying implementations is **not permitted**
    
- Violations will be handled per university policy