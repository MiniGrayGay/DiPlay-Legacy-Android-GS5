# Credentials and release packaging

Public release distribution has resumed at the maintainer's request. The APK intentionally bundles the experimental accessory certificate and matching key described in docs/THIRD_PARTY_NOTICES.md. Anyone with the APK can extract them. Local compilation, Git history removal and obfuscation do not make a bundled shared key confidential or revoke previous copies.

The public Git tree and corresponding source archive exclude accessory keys and Android release-signing secrets. CI checks reject credential containers and private-key blocks in tracked files. Synthetic test identities are generated at runtime. Source builds have no automatic private-asset import; release packaging requires an explicit local directory and permits only the two expected runtime files.

The Android APK-signing key is separate, stays local and is never bundled in the APK. Current acceptance of the experimental accessory identity does not establish Apple certification or guarantee future compatibility.

Because the accessory identity is shared by every installed copy, Apple could stop accepting it at any time, which would break authentication for all installations at once; there is no per-user fallback identity. The two runtime identity files are also the one part of the release APK that the published corresponding source does not cover: they are provisioned from a local directory at release time and excluded from the source archive (see README, "Source and credits").

# Update integrity

Release discovery (GitHub API or the releases Atom feed) and the SHA-256 checksum file are fetched only from github.com. Mirrors (ghproxy.net, gh-proxy.com) may serve the APK itself for reachability, but never the checksum it is verified against. Before installation, the downloaded APK must also match the release tag's version and carry a signing certificate identical to the installed app, so a hostile or broken mirror cannot substitute a differently signed build. Update traffic is HTTPS-only with an allowlisted redirect host set; on old Android versions a bundled CA store and Conscrypt TLS 1.2 are used instead of the frozen system store.

# Intentional trust decisions

- The USB lockdown channel to the iPhone uses a trust manager that accepts any certificate. That is intentional: lockdown runs over the paired USB link and trust comes from the pairing record, not from a certificate chain.
- Cleartext HTTP is enabled app-wide in the manifest. The audited HTTP-stack consumer is the optional remote MFi client, which refuses plain HTTP beyond local addresses (see docs/PRIVACY.md). The CarPlay transports themselves are raw-socket LAN protocols and do not consult this flag; it is retained for vendor HTTP behaviors on legacy ROMs and narrowed here to the consumers above rather than removed blind.
- One component is exported because other software must reach it: MapEmbedService refuses bind requests unless the driver enabled launcher map sharing, and revokes live embeds when it is turned off.

Review diagnostic reports before posting. Never include credentials or pairing records in public issues. Use GitHub private vulnerability reporting for sensitive findings.
