# App signing key

Every Press Sheet build is signed with this key so each new APK installs over the
old one and keeps your layout (Android refuses an update signed with
a different key).

Keep this repository **private** — the key lives here. To lock it down further,
add these repository secrets on GitHub (Settings → Secrets and variables →
Actions) and then delete this folder; the build uses the secrets first:

- SIGNING_KEYSTORE_B64 — `base64 -w0 signing/release.jks`
- SIGNING_STORE_PASSWORD / SIGNING_KEY_PASSWORD — the password in signing/key.properties
- SIGNING_KEY_ALIAS — presssheet

Never lose this key: without it, an update means uninstalling first, which
erases your layout.
