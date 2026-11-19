# Optional DNS blocking safety gate

**Status: conditional GO for an isolated prototype; NO-GO for release until every release gate below passes.**

This contract governs [issue #16](https://github.com/ghanta1996/GlideTV/issues/16). It does not enable DNS blocking and does not approve a release by itself.

## Platform facts

- A normal third-party app cannot silently change Android's global Private DNS setting. `WRITE_SECURE_SETTINGS` is [not available to third-party apps](https://developer.android.com/reference/android/Manifest.permission#WRITE_SECURE_SETTINGS), so Glide must not request it, use ADB as a product requirement, or claim it can restore a setting it cannot own.
- Android's supported app boundary is [`VpnService`](https://developer.android.com/develop/connectivity/vpn). The system owns the consent screen, only one app can be the prepared VPN service, and starting another VPN stops the active one.
- A VPN must run as a foreground service while active and present a persistent status notification. Glide must opt out of always-on VPN with `android.net.VpnService.SUPPORTS_ALWAYS_ON=false`.
- Closing the VPN interface or receiving `onRevoke()` returns routing to Android. Glide must stop the service and close the interface rather than attempting to rewrite system DNS.
- Android reports whether an underlying network uses Private DNS. Apps performing DNS themselves [must not downgrade an active encrypted configuration](https://developer.android.com/reference/android/net/LinkProperties#isPrivateDnsActive()).

## Approved prototype boundary

The only approved design is a local, DNS-only `VpnService` with all of these properties:

1. Support Android TV / Google TV **10 or later (API 29+) only**. [`DnsResolver.rawQuery()`](https://developer.android.com/reference/android/net/DnsResolver#rawQuery(android.net.Network,%20byte[],%20int,%20java.util.concurrent.Executor,%20android.os.CancellationSignal,%20android.net.DnsResolver.Callback%3Cbyte[]%3E)) is unavailable on earlier supported Glide versions, and the project will not add a second, less safe resolver path.
2. Call `VpnService.prepare()` immediately before activation and continue only after explicit system consent.
3. Refuse to start while any other VPN transport is active. Never replace another VPN, always-on VPN, work-profile VPN, or lockdown configuration.
4. Route only dedicated synthetic IPv4 and IPv6 DNS endpoint addresses into the TUN interface. Do not install IPv4 or IPv6 default routes and do not inspect, proxy, or forward non-DNS application traffic.
5. Forward allowed raw DNS messages through Android's `DnsResolver` on an explicitly selected underlying non-VPN network. Do not select a new public resolver, send plaintext DNS when Private DNS is active, or retain a domain history.
6. Exclude Glide's own package from the VPN path. Weather, updates, scene downloads, and the phone remote are outside the blocker and keep their existing network boundaries.
7. Support the DNS transport cases Android actually sends to the synthetic endpoints, including IPv4/IPv6 and UDP/TCP behavior. Malformed, fragmented, oversized, cancelled, and timed-out work must not crash or wedge networking.
8. Run only after a direct user action. Do not register a boot receiver, silently reconnect, or support Android always-on/lockdown mode.

The route-only assumption is a prototype hypothesis, not a repository fact. Packet-level tests must demonstrate that no non-DNS traffic enters the interface before release.

## Consent and visible state

Before opening Android's VPN consent screen, Glide must explain:

- DNS requests reveal domains contacted by apps, but Glide processes them locally and keeps no query log.
- Android shows a VPN indicator even though Glide is not an anonymity, location-changing, or full-traffic VPN.
- Only one VPN can be active, so Glide is unavailable when another VPN is connected.
- Apps that use their own encrypted DNS, hard-coded IP addresses, relays, or cached addresses can bypass DNS blocking.
- Blocking can cause false positives or app failures, and disabling it restores normal Android routing.

While active, Home must show a clear, non-ambiguous state and the foreground service must show a persistent notification with a **Disable** action. On Android versions that require notification permission, Glide must obtain it before activation; denial leaves the feature off.

## Supported-device matrix

| Environment | Gate outcome |
| --- | --- |
| Android TV / Google TV 8 or 9 | Unsupported; feature remains hidden or explains that Android 10+ is required. |
| Android TV / Google TV 10–13 | Prototype candidate after consent, route-isolation, restoration, and Private DNS tests pass. |
| Android TV / Google TV 14+ | Prototype candidate only with the required foreground-service type/permission and successful runtime enforcement tests. |
| Firmware without a usable VPN consent activity or persistent foreground notification | Unsupported; do not attempt a fallback. |
| Existing VPN, always-on VPN, lockdown mode, or managed profile policy | Refuse activation and leave the existing configuration untouched. |
| Captive portal, unvalidated network, or no usable underlying network | Do not start, or stop and fail open if the condition appears while active. |
| Strict or opportunistic Private DNS | Supported only after tests prove forwarding remains encrypted and uses the configured system resolver. Any downgrade is a release blocker. |

## Restoration and failure behavior

- **Disable:** close the TUN file descriptor, cancel pending DNS work, stop foreground mode, remove the notification, stop the service, and show inactive only after teardown completes.
- **Consent revoked or another VPN selected:** treat `onRevoke()` as authoritative, close resources without waiting for the main thread, and update Home on its next resume.
- **Worker crash, repeated resolver error, malformed packet flood, network loss, or underlying-network change:** fail open by tearing down the VPN. Do not leave a connected interface that cannot answer DNS.
- **Process death:** hold the interface only in the service process so Android closes it with the process. Persist no "active" preference that can misreport restoration.
- **Reboot:** remain off. The user must activate the feature again.
- **Partial startup:** if consent, foreground notification, interface establishment, network selection, or worker startup fails, close anything already opened and report the specific failure.

No failure path may write Private DNS, Wi-Fi, Ethernet, proxy, or other global network settings.

## Rules and update policy

- The first release must use a deterministic, versioned ruleset bundled in the signed APK. There is no background rules download or unsigned remote update.
- The source, compatible redistribution licence, generation command, rule count, normalization rules, and SHA-256 digest must be committed with the generated data.
- Matching is case-insensitive after IDNA normalization and applies to exact domains and their subdomains. The parser must reject IP addresses, public-suffix-wide entries, malformed names, and unsupported rule syntax.
- A small reviewed allowlist must take precedence over blocking to recover essential TV services from known false positives.
- Blocked requests receive a standards-valid local DNS response. Glide must not redirect domains to an external server or inject advertising of its own.
- Rules change only through reviewed app code changes. Each rules change therefore requires `versionName`, `versionCode`, and changelog updates and releases from `main`.

## Privacy and security boundaries

- Parse the minimum fields needed to match and answer a query. Do not log, persist, upload, or expose queried domains through the phone remote.
- Aggregate in-memory counters may report total allowed, blocked, and failed requests; they reset when the service stops and contain no domain names.
- Use Android's underlying system resolver rather than introducing a Glide-operated or third-party DNS provider.
- Apply strict bounds to packet size, outstanding queries, per-client work, response time, memory, and worker count.
- Treat every packet and DNS name as untrusted input. Parser fuzz/property tests and malformed-packet tests are required.
- Document that DNS blocking is best effort, not a security product, parental control, anonymity service, or universal ad blocker.

## Release gate for issue #16

Release remains **NO-GO** until one implementation proves all of the following:

- Unit tests cover rule normalization, allowlist precedence, DNS name compression, valid blocked responses, UDP/TCP framing, IPv4/IPv6 packet handling, checksums, truncation, timeouts, cancellation, and hostile input bounds.
- Instrumented packet evidence shows only traffic to the synthetic DNS endpoints enters the TUN interface; ordinary TCP/UDP traffic stays on the underlying network.
- Actual Android TV tests cover API 29 and at least one API 34+ device/emulator, plus one compatible physical TV before release.
- Strict and opportunistic Private DNS tests show no plaintext downgrade and no resolver substitution.
- Enable, consent cancellation, notification denial, disable, revoke, another VPN, network loss/change, captive portal, service termination, process death, and reboot all restore normal connectivity without manual repair.
- Allowed and blocked lookups, DNS failures, app-owned encrypted DNS bypass, false-positive recovery, CPU, memory, and sustained-query behavior are documented.
- Home and notification states include real screenshots/video, D-pad navigation remains unchanged, and required CI passes.
- The release is a MINOR version from `main`, with both Android version fields and the changelog updated.

If route isolation, encrypted Private DNS preservation, safe competing-VPN behavior, persistent visibility, or automatic restoration cannot be demonstrated on the supported matrix, close issue #16 as **NO-GO** and ship no blocker.
