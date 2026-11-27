Vendored third-party sources (unmodified):

- `uxplay/lib` — UxPlay lib/ at commit 566f3460e3cbb16c2c33a7ac607d3d2d0dd09b14
  (https://github.com/FDH2/UxPlay). The `dns_sd` and `mdnsd` dnssd backends and the
  CMake files were removed; `../dnssd_android.c` is the Android backend instead.
  License: GPL-3.0 (`uxplay/LICENSE`); individual files carry LGPL-2.1 / MIT headers.
- `libplist` — libplist 2.6.0 (https://github.com/libimobiledevice/libplist),
  C library sources only. License: LGPL-2.1 (`libplist/COPYING`).
- `openssl/` — not in git; built by `scripts/build-openssl.sh` (OpenSSL 3.0, Apache-2.0).
