# Third-party notices

## Tabler Icons

The launcher includes a small selection of icons from Tabler Icons.

MIT License

Copyright (c) 2020-2026 Paweł Kuna

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Inter

The launcher bundles Inter 4.1 Regular, SemiBold and Bold under the SIL Open Font License 1.1.
The license is shipped in Compose resources and is available in the repository at
[inter_ofl.txt](shared/src/commonMain/composeResources/files/licenses/inter_ofl.txt).

## Skiko 0.144.6

The launcher includes Skiko with its native runtime. Skiko is licensed under Apache-2.0;
the separate upstream NOTICE attributes code adapted from the Android Open Source Project.
The unmodified [NOTICE](licenses/skiko-0.144.6/NOTICE) is included in application resources
at the same relative path, in addition to the licenses already supplied in dependency JARs.
Source: [Skiko v0.144.6 NOTICE](https://raw.githubusercontent.com/JetBrains/skiko/v0.144.6/NOTICE).

## libffi in JNA 5.18.1

JNA's native dispatch library includes libffi. Its copyright and permission notice are separate
from JNA's own Apache-2.0/LGPL licensing. The unmodified [libffi LICENSE](licenses/jna-5.18.1/libffi-LICENSE)
is included in application resources at the same relative path.
Source: [libffi LICENSE shipped with JNA 5.18.1](https://raw.githubusercontent.com/java-native-access/jna/5.18.1/native/libffi/LICENSE).

## Skia m144-22f58c9fd4

Skiko 0.144.6 uses Skia `m144-22f58c9fd4` in its native runtime. The unmodified
[Skia BSD license](licenses/skia-m144-22f58c9fd4/LICENSE), including Google's copyright,
is included in application resources at the same relative path. It is separate from Skiko's
Apache-2.0 license and AOSP NOTICE.
Source: [Skia LICENSE at the pinned tag](https://raw.githubusercontent.com/JetBrains/skia/m144-22f58c9fd4/LICENSE).

These notices cover the identified components; they are not a complete inventory of all native
Skia or other transitive components. Recheck the notices when dependency versions change.

## Node.js runtime

When no compatible system Node.js is available, the launcher downloads an unmodified official Node.js
runtime matching Windows x64, Linux x86_64 or macOS Intel/Apple Silicon from `https://nodejs.org/dist/`
and verifies it against the `SHASUMS256.txt` of the same pinned release. The private portable runtime
is stored under the platform's per-user HH Clicker data directory; nothing is installed system-wide
and PATH is not modified.

Node.js is distributed under the MIT license. The launcher preserves its `LICENSE` beside the local
portable Node executable; dependency notices remain published in the verified official distribution.

## Google Chrome

Google Chrome is **not** redistributed with this project. When Chrome is missing, and only after the
user presses the button that states Google's terms are accepted, the launcher downloads the official
package from `https://dl.google.com/`. On Windows this is the Chrome for Business x64 MSI, verified
against Google LLC and handed to `msiexec`. Debian 13 uses the official amd64 DEB, checked for package
identity and architecture before installation with apt. macOS uses Google's universal PKG, checked
with the macOS signature tools before the system installer is invoked. Other Linux distributions
receive manual installation instructions. The browser remains subject to the
[Google Chrome and ChromeOS Additional Terms of Service](https://www.google.com/chrome/terms/).
