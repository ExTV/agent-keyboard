<img align="left" width="80" height="80"
src=".github/repo_icon.png" alt="App icon">

# agent-keyboard

**agent-keyboard** is an Android IME — a full keyboard you can use day-to-day —
that also exposes a **secure AIDL API** so a co-signed agent app can type,
read, and navigate the active text field in any app, on any screen, without
needing an Accessibility Service.

It is a fork of [FlorisBoard](https://github.com/florisboard/florisboard).
All upstream keyboard behaviour (layouts, themes, clipboard manager, emoji
panel, extensions, etc.) is preserved unchanged. The only addition is the
agent API backend.

## Why a keyboard?

The keyboard is the canonical Android entry point for writing into a text
field: as the active IME you already hold a live `InputConnection` for the
focused editor. Routing agent input through the IME means no Accessibility
Service, no overlay tricks, no per-app integrations — if the field accepts
typing, the agent can drive it.

## API surface

AIDL is defined in
[`IKeyboardApi.aidl`](app/src/main/aidl/dev/patrickgold/florisboard/api/IKeyboardApi.aidl):

| Method | Purpose |
| --- | --- |
| `connect()` | Handshake. Returns a session token. |
| `getApiVersion()` | Protocol version. |
| `typeText(token, text)` | Commit text into the active field. |
| `pressKey(token, keyCode)` | Send a `KeyEvent` (e.g. `KEYCODE_ENTER`). |
| `deleteChars(token, count)` | Delete N chars backward. |
| `clearField(token)` | Empty the field. |
| `getCurrentText(token)` | Return full field contents. |
| `getSelectedText(token)` | Return current selection. |
| `getEditorInfo(token)` | Hint, input type, package name, password flag, selection. |
| `setCursorPosition(token, pos)` | Move the caret. |
| `selectRange(token, start, end)` | Select a range. |

## Security model

| Layer | Mechanism |
| --- | --- |
| **Caller identity** | `signature`-level permission `dev.patrickgold.florisboard.permission.AGENT_KEYBOARD_API`. Only apps signed with the same key as the keyboard can bind. Enforced by the OS. |
| **Session binding** | A fresh token is issued per bind via `connect()` and required on every subsequent call. Tokens are revoked when the binding drops. |
| **Rate limiting** | Token-bucket limiter, 120 chars/s sustained, 240 burst — bounds runaway agents. |
| **Field sensitivity** | Password and other sensitive input types are refused. The agent gets a clear `false` / `null` back. |

## Connecting from an agent app

The agent app must:

1. Be signed with the same keystore as `agent-keyboard`.
2. Declare `<uses-permission android:name="dev.patrickgold.florisboard.permission.AGENT_KEYBOARD_API"/>`.
3. Ship a copy of the `.aidl` files under the same package path
   (`dev/patrickgold/florisboard/api/`).
4. Bind to the service:

```kotlin
val intent = Intent("dev.patrickgold.florisboard.api.action.BIND").apply {
    setPackage("dev.patrickgold.florisboard")
}
context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

5. In `onServiceConnected`, call `IKeyboardApi.Stub.asInterface(binder).connect()`
   and reuse the returned token on subsequent calls.

The IME must, of course, be set as the user's active keyboard for any of the
text actions to take effect.

## Building

Standard Gradle build, same toolchain as upstream FlorisBoard:

```
./gradlew :app:assembleDebug
```

The agent API service is included in every build variant.

---

## Credits

This project is a fork of **FlorisBoard**, a free and open-source keyboard
for Android by [patrickgold](https://github.com/patrickgold) and
[The FlorisBoard Contributors](https://github.com/florisboard/florisboard/graphs/contributors).
All keyboard functionality in this repo — layouts, themes, clipboard manager,
emoji handling, extensions, spell checking, the entire IME pipeline — is
their work. The agent-keyboard fork only adds the AIDL API backend on top.

Upstream project: <https://github.com/florisboard/florisboard>

### Used libraries, components and icons (from upstream)

* [AndroidX libraries](https://github.com/androidx/androidx) by
  [Android Jetpack](https://github.com/androidx)
* [AboutLibraries](https://github.com/mikepenz/AboutLibraries) by
  [mikepenz](https://github.com/mikepenz)
* [Google Material icons](https://github.com/google/material-design-icons) by
  [Google](https://github.com/google)
* [JetPref preference library](https://github.com/patrickgold/jetpref) by
  [patrickgold](https://github.com/patrickgold)
* [KotlinX coroutines library](https://github.com/Kotlin/kotlinx.coroutines) by
  [Kotlin](https://github.com/Kotlin)
* [KotlinX serialization library](https://github.com/Kotlin/kotlinx.serialization) by
  [Kotlin](https://github.com/Kotlin)

Many thanks to [Nikolay Anzarov](https://www.behance.net/nikolayanzarov) ([@BloodRaven0](https://github.com/BloodRaven0)) for designing and providing the main app icons to the upstream project.

## License

```
Copyright 2020-2026 The FlorisBoard Contributors
Copyright 2026 ExTV (agent-keyboard fork)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

Thanks to [The FlorisBoard Contributors](https://github.com/florisboard/florisboard/graphs/contributors) for making this project possible.
