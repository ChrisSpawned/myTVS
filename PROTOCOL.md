# Ntorq 150 TFT — BLE protocol (verified)

Reverse-engineered from the owner's own cluster (nRF Connect captures) and
cross-checked against the TVS Connect app's own unobfuscated BLE classes
(`com.tvs.bike.core.protocol.ntorq.*`). The codec in `NtorqCodec.kt` reproduces
every captured frame byte-for-byte.

## Link
- Bluetooth LE. **Bonding required** (PIN/consent, AES-128).
- The cluster drops a silent link after ~15 s; the phone must write periodically
  (keepalive) to hold it.

## GATT
| Role | UUID |
| :--- | :--- |
| Service | `5456534d-5647-5341-5342-454e544f5251` |
| Notify (cluster→phone) | `00005354-0000-1000-8000-00805f9b34fb` |
| Write (phone→cluster) | `00005352-0000-1000-8000-00805f9b34fb` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

## Frame (20 bytes): `5A | ID | D0..D15 | CHK | FF`
1. Plaintext = `0x5A`, ID, 16 data bytes, checksum slot, `0xFF`.
2. `CHK = (0xFF - (sum(plaintext[0..17]) & 0xFF)) & 0xFF`  (covers 5A, ID, 16 data).
3. XOR every byte at index 2..18 (the 16 data bytes **and** the checksum) with
   `0xEA`. The `0x5A`, the ID and the `0xFF` stay in clear.

## Frame IDs (from the app's NtorqFrameIdentifier)
| ID | Meaning |
| :-- | :-- |
| 0x10 | Speedometer frame 1 |
| 0x11 | Speedometer frame 2 / status |
| 0x12 | Speed |
| 0x16 | Sensor |
| 0x18 / 0x19 | Speedometer frames 4 / 3 |
| 0x29 | Wi-Fi AP credentials (ASCII password) — confirms the cluster hosts Wi-Fi |
| 0x37 | Calibration |
| 0x54 | ACK / keepalive (phone→cluster) |

Field dictionary (`FrameParameterType`): SPEED, ODOMETER, FUEL_LEVEL,
TURN_INDICATOR_STATUS, SCREEN_MATRIX, CAPTURE_SCREENSHOT, MUSIC_COMMAND,
INCOMING_CALL_STATE, and ~120 more. Exact byte positions per frame are being
mapped from the app's frame classes.

Navigation on the cluster is driven by the **Mappls (MapmyIndia)** SDK.
