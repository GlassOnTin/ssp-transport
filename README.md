# mosh-kotlin

Pure Kotlin implementation of the [mosh](https://mosh.org/) (Mobile Shell) client-side transport protocol for Android and JVM.

This library implements the mosh State Synchronization Protocol (SSP) — the UDP-based protocol that gives mosh its roaming and resilience properties. It handles encryption, framing, compression, and state tracking, delivering terminal output (VT100 sequences) via a callback.

Extracted from [Haven](https://github.com/GlassOnTin/Haven), an Android SSH/mosh client.

## What it does

- **AES-128-OCB encryption** — Authenticated packet encryption matching the [mosh protocol spec](https://mosh.org/mosh-paper.pdf), using Bouncy Castle
- **UDP transport** — Packet framing with timestamps, fragment reassembly, and zlib compression
- **SSP state machine** — Keepalive, retransmit with exponential backoff, ack tracking
- **Protobuf wire format** — Minimal encoder/decoder for mosh's protobuf messages (no protobuf dependency)
- **Coroutine-based** — Send/receive loops run on `Dispatchers.IO`, instant wake on input via conflated channel

## What it doesn't do

This is a **client-side transport only**. It does not include:

- SSH bootstrapping (connecting to the server, running `mosh-server new`, parsing `MOSH CONNECT`)
- Terminal emulation (use [termlib](https://github.com/connectbot/termlib), [termux-terminal-emulator](https://github.com/nickoala/nickoala-terminal), etc.)
- A mosh server implementation
- Local echo / prediction (the C++ mosh client's prediction engine)

You need an SSH client to start `mosh-server` on the remote host, parse the `MOSH CONNECT <port> <key>` response, then pass those to `MoshTransport`.

## Usage

```kotlin
// 1. Bootstrap via SSH (use your SSH library of choice)
//    ssh user@host "mosh-server new -s -c 256 -l LANG=en_US.UTF-8"
//    Parse: MOSH CONNECT <port> <key>

// 2. Create and start the transport
val transport = MoshTransport(
    serverIp = "192.168.1.100",
    port = 60001,           // from MOSH CONNECT
    key = "base64key==",    // from MOSH CONNECT
    onOutput = { data, offset, length ->
        // VT100 terminal output — feed to your terminal emulator
        emulator.writeInput(data, offset, length)
    },
    onDisconnect = { cleanExit ->
        // Connection lost or server exited
    },
    logger = object : MoshLogger {
        override fun d(tag: String, msg: String) = Log.d(tag, msg)
        override fun e(tag: String, msg: String, t: Throwable?) = Log.e(tag, msg, t)
    },
)
transport.start(coroutineScope)

// 3. Send user input
transport.sendInput("ls\n".toByteArray())

// 4. Handle terminal resize
transport.resize(cols = 80, rows = 24)

// 5. Clean up
transport.close()
```

## Architecture

```
MoshTransport          SSP state machine, send/receive coroutine loops
  ├── MoshConnection   UDP socket, packet encryption, zlib, fragmentation
  │     └── MoshCrypto AES-128-OCB encrypt/decrypt
  ├── UserStream       Client input state tracking and diff computation
  └── WireFormat       Protobuf encode/decode for mosh messages
```

| Component | File | Description |
|-----------|------|-------------|
| `MoshTransport` | `transport/MoshTransport.kt` | Top-level API. Manages coroutine loops, SSP state, keepalive, retransmit backoff |
| `MoshConnection` | `network/MoshConnection.kt` | UDP I/O with packet encryption, timestamps, zlib compression, fragment reassembly |
| `MoshCrypto` | `crypto/MoshCrypto.kt` | AES-128-OCB via Bouncy Castle. Handles nonce encoding with direction bits |
| `UserStream` | `transport/UserStream.kt` | Accumulates keystrokes and resize events, computes state diffs |
| `WireFormat` | `proto/WireFormat.kt` | Minimal protobuf codec for `TransportInstruction`, `UserMessage`, `HostMessage` |
| `MoshLogger` | `MoshLogger.kt` | Logging interface — implement to bridge to your platform's logger |

## Protocol reference

The mosh protocol is described in:

- [Mosh: An Interactive Remote Shell for Mobile Users](https://mosh.org/mosh-paper.pdf) (USENIX ATC '12)
- [mosh source code](https://github.com/mobile-shell/mosh) (C++ reference implementation)
- [mosh protocol documentation](https://mosh.org/#techinfo)

Key protocol details implemented here:

- **Packet format**: `[8-byte nonce][AES-128-OCB(plaintext + 16-byte tag)]`
- **Plaintext format**: `[2-byte timestamp][2-byte timestamp_reply][fragment data]`
- **Fragment format**: `[8-byte fragment_id][2-byte flags+num][zlib-compressed protobuf]`
- **Nonce encoding**: High bit = direction (0 = client→server, 1 = server→client), low 63 bits = sequence number
- **SSP**: Each side maintains a numbered state. Diffs are sent referencing the last acknowledged state. Receiver applies diffs to advance state.

## Dependencies

- [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov-jdk18on`) — AES-128-OCB cipher
- [kotlinx-coroutines](https://github.com/Kotlin/kotlinx.coroutines) — async send/receive loops

## License

Apache License 2.0

The [mosh project](https://github.com/mobile-shell/mosh) is licensed under GPLv3. This is an independent reimplementation of the client-side protocol, not a derivative work of the C++ source code.
