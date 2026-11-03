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

## Node.js runtime

When no compatible system Node.js is available, the launcher downloads an unmodified official Node.js
runtime for Windows x64 from `https://nodejs.org/dist/` and verifies it against the `SHASUMS256.txt`
of the same pinned release. It stores only its private portable runtime under the current user's
LOCALAPPDATA; nothing is installed system-wide and PATH is not modified.

Node.js is distributed under the MIT license. The launcher preserves its `LICENSE` beside the local
portable `node.exe`; dependency notices remain published in the verified official distribution.

## Google Chrome

Google Chrome is **not** redistributed with this project. When Chrome is missing, and only after the
user presses the button that states Google's terms are accepted, the launcher downloads the official
Google Chrome for Business x64 MSI from `https://dl.google.com/`, verifies its Authenticode signature
against Google LLC and hands it to `msiexec`. The browser remains subject to the
[Google Chrome and ChromeOS Additional Terms of Service](https://www.google.com/chrome/terms/).
