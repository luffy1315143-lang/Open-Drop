# OpenDrop QR connection

This source update adds QR-based connection as an alternative to NSD discovery.

## How to use
1. Install the same build on both phones.
2. On the receiving phone, tap **Receive** and then **Show QR code for sender**.
3. On the sending phone, select files (optional before scanning), then tap **Scan receiver QR code** and allow camera access.
4. Scan the QR shown on the receiver. OpenDrop fills the receiver IP automatically.
5. Select files if not already selected, then tap **Send selected files**. The receiver still has to approve the incoming transfer.

The QR contains `OPENDROP1|IPv4_ADDRESS|45454`. Both devices must be able to reach each other on the same Wi-Fi/hotspot. This QR is not an internet relay and does not bypass hotspot/client isolation.

Build with `./gradlew assembleDebug`. The project needs internet access the first time Gradle downloads the ZXing dependency. Build success has not been verified in this environment because Gradle distribution download was blocked by unavailable network access.
