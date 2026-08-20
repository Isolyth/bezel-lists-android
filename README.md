# lists-android

The lists client as an Android app, speaking bezel's native transport:
Iroh QUIC, HTTP/1.1 per bi-stream, no IP or port configured anywhere —
just the server's endpoint id and a capability token.

## Shape

- **`../../bezel-client/`** — the Rust core: dials by endpoint id (or a
  full `EndpointAddr` JSON), holds one connection, one bi-stream per
  request. Compiled as `libbezel_client.so` via `cargo ndk` into
  `app/src/main/jniLibs/`. Its contract is pinned by host-side tests
  against a real core over real QUIC.
- **Kotlin shell** — `Bezel.kt` is the JNI surface (configure + request,
  JSON in/out); `ListsApp.kt` is the whole UI: connect screen, list
  chips, add row, entry editor with frontmatter-style attributes.

The phone mints a random 32-byte iroh identity on first launch and keeps
it, so `source.addr` names this device stably across sessions. The
client string is `Lists (Android) v0.1`.

## Build

```sh
cd ../../bezel-client
cargo ndk -t arm64-v8a -o ../apps/lists-android/app/src/main/jniLibs build --release
cd ../apps/lists-android
./gradlew assembleDebug     # apk lands in app/build/outputs/apk/debug/
```

On NixOS the NDK's prebuilt toolchain needs its ELF interpreter and
rpath patched (patchelf against nix glibc/zlib/libstdc++), and gradle
needs a real JDK 17 registered via `org.gradle.java.installations.paths`
in gradle.properties.

## Connect

Paste the core's iroh endpoint id (logged at startup) and a token:

```sh
bezel mint --facets 'lists/v1,facet' --verbs read,write --ttl 0 --user you
```
