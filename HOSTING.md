# Connect the Flux Instagram preview

The message **"Flux could not retrieve this Instagram post's details"** comes
from direct phone extraction. The 2.2 preview does not include an automatically
hosted resolver. Installing it alone does not fix this failure.

## Deploy a personal preview

[Open the Flux deployment setup on Render](https://render.com/deploy?repo=https%3A%2F%2Fgithub.com%2Fsduos4029-CODE%2FytubeDownload%2Ftree%2Fflux%2F2.2-server-preview)

1. Sign in to Render (you can use GitHub). The prepared Blueprint selects one
   **Free** web service with Docker and HTTPS; review the plan before deploying.
2. When prompted for `FLUX_API_TOKEN`, create a private access key containing
   **32–128 characters**, using letters, digits, `_` or `-` only. Save it in your
   password manager. This is a new server key, not an Instagram password. The app
   and backend both enforce these characters; do not use Render's generic
   generated base64 secret instead.
3. Deploy and wait until Render shows the service as live. Copy its HTTPS address
   (for example, the address ending in `.onrender.com` shown by Render).
4. Open that address followed by `/healthz` in your browser. Wait until it returns
   JSON containing `"service":"flux-resolver"` and `"status":"running"`.
5. In Flux 2.2 preview, open **Settings → Public Instagram server → Set up server**.
   Enter the HTTPS address **without `/healthz`**, then your access key. Tap
   **Verify and connect**, return to Discover and search the same Reel again.

Do not post the access key in chat, GitHub issues, screenshots or the repository.
If you share the service address for troubleshooting, share only the address.

If the quick-deploy link does not select the branch, create a new Blueprint in
the Render dashboard, select `sduos4029-CODE/ytubeDownload`, choose branch
`flux/2.2-server-preview`, and use the root `render.yaml` file.

## What this verifies

`/healthz` checks that the process is running. **Verify and connect** checks the
Flux protocol and server key. Neither proves that Instagram will serve a Reel
from that host. Search and complete a real download to test that separately.

The reported Reel and a control post resolved anonymously from the GitHub CI
server, but no Render deployment or phone transfer has been verified. Instagram
can reject requests from this host, and CDN URLs may expire or be restricted.
If the server returns no formats, there is still no working fix from that host.

Render's Free service is suitable for a personal preview, not a production
download service. It sleeps after 15 minutes without requests and can take about
a minute to wake. Open `/healthz` and wait before connecting in Flux; an initial
request can otherwise time out or receive Render's loading page. Free services
also have usage limits. This configuration creates no database or persistent
disk, proxies no video bytes, and disables automatic deploys from future code
pushes. A production deployment still needs appropriate connection/rate limits.

Official references used for this setup:

- [Deploy button and branch selection](https://render.com/docs/deploy-to-render)
- [Blueprint specification](https://render.com/docs/blueprint-spec)
- [Free service behavior and limits](https://render.com/docs/free)
