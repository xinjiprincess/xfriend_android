# GitHub import — 2026-10-30

This repository contains the local working-tree snapshot of XFriend Android 0.19.0, including development changes and handoff documents. It is not a verified mirror of the authenticated source server at http://8.137.105.223:3000/lyon/xfriend_android.git; server access was unavailable. Historical commits were not imported.

Build outputs, APKs, signing keys, local SDK configuration and caches are excluded. The pre-existing embedded OAuth client secret was removed from the published copy after GitHub secret protection rejected it. Configure your authorized OAuth client at build time using the environment variables `XFRIEND_PROVIDER_POOL_CLIENT_ID` and `XFRIEND_PROVIDER_POOL_CLIENT_SECRET`. No secret values should be committed. These fields default to empty; the corresponding provider account refresh requires valid configuration. Ordinary model API configuration remains separate. Build-time injection is not secure secret storage on a distributed APK.

The import itself does not establish successful tests, device verification or APK builds for this snapshot. Refer to version-specific delivery documents for their actual validation scope.
