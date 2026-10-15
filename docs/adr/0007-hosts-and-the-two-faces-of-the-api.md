# 0007. Three hosts, and the two faces of the api

- Status: Accepted
- Date: 2026-10-15

## Context
Three audiences reach the system: merchants using the dashboard, merchants' servers calling the API, and their
customers paying on the checkout page. They authenticate differently (a session cookie, a secret API key,
nothing) and need different protections.

## Decision
- `cryptopayment.aksoyakin.com` — the web app: marketing pages, documentation, sign-in, the merchant dashboard
  and the admin panel.
- `api.cryptopayment.aksoyakin.com` — the public API, served by the api service directly.
- `pay.cryptopayment.aksoyakin.com` — the hosted checkout, served by the same web app and selected by host.
- The dashboard's session cookie is host-only, so it is never sent to the API or the checkout.
- The api serves two surfaces with separate security filter chains:
  - `/v1/**` — the public API, authenticated with API keys. The reverse proxy routes only this prefix.
  - `/internal/**` — used by the web app's server over the internal network, authenticated with the dashboard
    session. It has no route from outside, and a test asserts that.

## Consequences
- A cross-site request against the API cannot ride on a dashboard session; the customer-facing page shares no
  origin with the dashboard and carries its own Content Security Policy.
- Rate limits, CORS and any future firewall rules can target the public API alone.
- Three hosts need three DNS records and certificates. Second-level subdomains are not covered by a free
  proxied certificate at some DNS providers, in which case the records are DNS-only and the reverse proxy
  obtains certificates itself.
