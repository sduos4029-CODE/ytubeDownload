# Flux anonymous Instagram resolver (preview)

This is deployment-ready source, **not a running service**. No hosting account
is connected. Server-side extraction can still be limited by Instagram. Test the
reported Reel on the chosen host before describing the no-login issue as fixed.
No Instagram login, session cookies, scraping-provider secrets or account harvesting
are implemented. Only public post/Reel URLs are accepted; profiles and Threads are
not supported. Version 1 exposes direct HTTPS media formats, not manifests.

## Hosting preference

1. **Google Cloud Run** — [deployment instructions](GOOGLE_CLOUD.md).
2. **Render** — [fallback setup](../HOSTING.md#second-choice-render-personal-preview).

Both require an owner-controlled account and a separate live Reel test.

## Deploy on your own host

Use a host capable of running a long-lived Python 3.12 process or Docker container.
The Docker build context is the repository root:

```
docker build -f server/Dockerfile -t flux-resolver .
python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
```

Keep that generated access key private. Set `FLUX_API_TOKEN` in the host's secret
settings and `PORT=8080`. Put HTTPS in front of the internal port; do not expose
internal HTTP directly. Run as the supplied non-root user, with a read-only
filesystem, a small writable `/tmp`, memory/CPU/process limits, an upstream
connection/body limit and a 60-second request timeout. No persistent disk is needed.
The app refuses HTTP and redirects in release builds and encrypts the access key
with Android Keystore. Do not embed a shared access key in an APK.

`GET /healthz` reports process health, **not Instagram availability**.
Authenticated `GET /v1/status` verifies the service protocol and access key.
Authenticated `POST /v1/resolve` accepts only `{"url":"https://www.instagram.com/reel/DezDcSUR2Gg/"}`.
Use `Authorization: Bearer <access key>`. The response contains short-lived,
sanitized yt-dlp metadata. The app refreshes this for each download and keeps the
original post URL and item identity in history. Instagram cookies never enter
this service; the backend key is sent only to the configured origin.

## Limits and checks

Two concurrent extractions, 20 authenticated requests per minute, 45-second
subprocess deadline, 4 MB extractor output cap, 4 KB request cap, 20 post items,
100 direct formats per item. Unknown fields, arbitrary source URLs and credential
payloads are rejected. Response metadata is reconstructed from approved fields;
postprocessors, local filenames, cookie/authorization headers and non-Meta URLs
are excluded. TLS certificate validation remains enabled. No media is proxied.
Deploy an external rate limiter for Internet-facing unauthenticated connection
floods; the built-in limit protects extraction work, not volumetric attacks.

Run `python3 -m unittest discover -s server -p 'test_*.py'`. Live extraction is a
separate integration check; fixture tests do not establish that a public post is
retrievable from the eventual production host.
