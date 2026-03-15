# ssp-transport

Pure Kotlin implementation of the client-side [State Synchronization Protocol (SSP)](https://mosh.org/mosh-paper.pdf) for Android and JVM. SSP is the UDP-based protocol used by [Mosh](https://mosh.org/) (Mobile Shell) that provides roaming and resilience over unreliable networks.

**This project is not affiliated with or endorsed by the Mosh project.** It is an independent implementation of the SSP client transport protocol.

Extracted from [Haven](https://github.com/GlassOnTin/Haven), an Android SSH client.

## What it does

- **AES-128-OCB encryption** — Authenticated packet encryption per the SSP protocol spec
- **UDP transport** — Packet framing with timestamps, fragment reassembly, and zlib compression
- **SSP state machine** — Keepalive, retransmit with exponential backoff, ack tracking
- **Protobuf wire format** — Encoder/decoder for SSP transport messages
- **Network roaming** — Detects stalled connections and rebinds the UDP socket for IP changes
- **Coroutine-based** — Send/receive loops run on `Dispatchers.IO`, instant wake on input

## What it doesn't do

This is a **client-side transport only**. It does not include:

- SSH bootstrapping (connecting to the server, running `mosh-server new`, parsing `MOSH CONNECT`)
- Terminal emulation (use [termlib](https://github.com/connectbot/termlib), etc.)
- A server implementation
- Local echo / prediction

You need an SSH client to start `mosh-server` on the remote host, parse the `MOSH CONNECT <port> <key>` response, then pass those to `SspTransport`.

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
  └── WireFormat       Protobuf encode/decode for SSP messages
```

## Protocol reference

The SSP protocol is described in:

- [Mosh: An Interactive Remote Shell for Mobile Users](https://mosh.org/mosh-paper.pdf) (USENIX ATC '12) — Keith Winstein and Hari Balakrishnan

## Dependencies

- [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov-jdk18on`) — AES-128-OCB cipher
- [kotlinx-coroutines](https://github.com/Kotlin/kotlinx.coroutines) — async send/receive loops

## License

GPLv3 — see [LICENSE](LICENSE).

This is a derivative work of the [Mosh](https://github.com/mobile-shell/mosh) protocol (GPLv3, Copyright Keith Winstein and others).
