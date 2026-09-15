# Security policy

## Reporting a vulnerability

Report vulnerabilities privately through GitHub's private vulnerability
reporting: open the repository's **Security** tab and choose **Report a
vulnerability**, or go straight to
[the report form](https://github.com/david-cant-code/pebble-app-degoogled/security/advisories/new).
Do not report a vulnerability in a public issue, pull request, or
discussion.

A useful report includes:

- the Gravel version and Android version you found it on;
- what an attacker needs (for example another app on the phone, a
  malicious watchapp, a position on the network, or physical access) and
  what they gain;
- steps or a proof of concept that reproduce it.

## What happens next

Security reports are acted on as soon as possible. The report stays
private while the issue is confirmed and fixed, and discussion about it
happens in the private report.

A vulnerability stays private until a fixed release is available on
F-Droid, because publishing it earlier would show attackers how to reach
users who have no update to install yet. Once the fix is available, the
report is published as a GitHub security advisory, crediting you unless
you ask not to be named. Please keep the details private until then.

If the vulnerability is also present in the upstream Core Devices app,
Core Devices is told privately before the advisory is published.

## Supported versions

Security fixes go into a new release; only the latest release is
supported.

## Scope

In scope is the app as built from this repository, including the upstream
code it ships. Vulnerabilities in PebbleOS watch firmware, in third-party
watchapps, or in the web services Gravel talks to belong with those
projects; if you are not sure where something belongs, report it here.
