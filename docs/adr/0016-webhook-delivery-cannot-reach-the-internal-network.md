# 0016. Webhook delivery cannot reach the internal network

- Status: Accepted
- Date: 2026-10-16

## Context
Merchants choose their webhook URLs, and the api sends requests to them from inside the platform's network,
where the signer, the api's internal surface and the database live. Without care, a webhook URL is a way to
make the platform call its own internals (server-side request forgery).

## Decision
- Webhook URLs must use HTTPS.
- The host is resolved once per delivery; the delivery is refused if any resolved address is loopback,
  private, link-local, carrier-grade NAT, multicast, unspecified, or an IPv6 unique-local address. The
  connection is then made to that resolved address, with the original host name for TLS, so the name cannot be
  re-resolved to a different address in between.
- Redirects are not followed. Connect and read timeouts are short, and only a bounded part of the response is
  read.
- The same check runs when a merchant registers the URL, so mistakes surface early, and again on every
  delivery, because DNS can change.

## Consequences
- A merchant cannot use webhooks to reach the platform's internal services.
- Merchants whose endpoints redirect have to register the final URL.
