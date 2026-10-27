# Google Cloud Run: first hosting choice

Use Google Cloud Run first; [Render is the fallback](../HOSTING.md#second-choice-render-personal-preview).
This guide is prepared deployment configuration, **not an already running or
verified Instagram service**. The existing Flux 2.2 preview accepts a Cloud Run
HTTPS address; an APK update is not required to connect it.

## Before starting

Sign into [Google Cloud Console](https://console.cloud.google.com/) with your own
Google account. Create or choose a project and enable billing. Cloud Run has
usage allowances, but builds, image storage, secrets and network traffic can
incur charges. Set a budget alert; an alert is not a spending cap. The settings
below limit instances, not total charges. Do not paste Google credentials or the
Flux access key into chat, source control or the APK.

Use Cloud Shell from that console. You need permission to enable APIs, build
images, manage the registry and secret, create a service account, assign its
secret access, and deploy Cloud Run. If your organization blocks public Cloud
Run services or the build account lacks permissions, have its administrator
review the error; do not disable organization controls.

## 1. Select the project and prepare services

These commands create resources in your selected Google account. Review them
before running. Replace `YOUR_PROJECT_ID` with the actual project ID. The region
is an example; choose a supported region appropriate for you.

```bash
gcloud config set project YOUR_PROJECT_ID
FLUX_PROJECT_ID="$(gcloud config get-value project)"
FLUX_REGION=asia-south1
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com
gcloud artifacts repositories create flux --repository-format=docker --location="$FLUX_REGION"
gcloud iam service-accounts create flux-resolver --display-name="Flux resolver runtime"
FLUX_RUNTIME_SA="flux-resolver@${FLUX_PROJECT_ID}.iam.gserviceaccount.com"
```

Use unique resource names if they already exist. A new project may take time to
enable services and provision the build service account. Follow any Cloud Build
permission errors for the actual account Google selects rather than granting
project-wide Owner access.

## 2. Create the private Flux server key

Generate a random access key in your password manager: **32–128 ASCII letters,
digits, `_` or `-`**. Keep a copy there to enter in Flux later. This is a server
key, not an Instagram password. In Cloud Shell, paste it at the hidden prompt:

```bash
read -r -s -p 'Flux server key: ' FLUX_SERVER_KEY
printf '\n'
printf '%s' "$FLUX_SERVER_KEY" | gcloud secrets create flux-api-token --data-file=-
unset FLUX_SERVER_KEY
gcloud secrets add-iam-policy-binding flux-api-token --member="serviceAccount:${FLUX_RUNTIME_SA}" --role=roles/secretmanager.secretAccessor
```

The key passes through standard input, not a command-line argument or committed
file. If secret creation fails, stop and resolve it before deploying. This guide
uses version `1` of a newly created secret; do not overwrite another service's
existing secret. The runtime identity receives access to this secret only.

## 3. Build and deploy

Clone the prepared branch (or enter your existing checkout of it):

```bash
git clone --branch flux/2.2.1-server-status --single-branch https://github.com/sduos4029-CODE/ytubeDownload.git flux-cloud
cd flux-cloud
FLUX_IMAGE="${FLUX_REGION}-docker.pkg.dev/${FLUX_PROJECT_ID}/flux/flux-resolver:preview"
gcloud builds submit --config=server/cloudbuild.yaml --substitutions="_IMAGE=${FLUX_IMAGE}" .
```

Continue only when the build succeeds. The build configuration uses the existing
`server/Dockerfile` with the repository root as context, including the pinned,
checksum-checked extractor. It does not send the Flux secret to the build.

```bash
gcloud run deploy flux-resolver --image="$FLUX_IMAGE" --region="$FLUX_REGION" --service-account="$FLUX_RUNTIME_SA" --set-secrets=FLUX_API_TOKEN=flux-api-token:1 --port=8080 --cpu=1 --memory=512Mi --concurrency=2 --min-instances=0 --max-instances=1 --timeout=60s --allow-unauthenticated
gcloud run services describe flux-resolver --region="$FLUX_REGION" --format='value(status.url)'
```

Public network access is intentional: Flux does not implement Google IAM login.
The resolver still requires its own bearer key for `/v1/status` and `/v1/resolve`;
only `/healthz` is public. Google terminates HTTPS and forwards the request to the
container's internal HTTP port. Never share or embed the bearer key. The app's
backend sees post links and your requesting IP. Built-in extraction limits are
not protection against every Internet connection flood; this is a personal
preview, not a production public download service.

## 4. Connect and test

1. Open the printed HTTPS address with `/healthz` appended. Expect JSON containing
   `"service":"flux-resolver"`, `"protocol":1` and `"status":"running"`.
2. In Flux, open **Settings → Public Instagram server → Set up server**. Enter
   the service address **without `/healthz`**, then the private key from your
   password manager. Tap **Verify and connect**.
3. Search `https://www.instagram.com/reel/DezDcSUR2Gg/`. Confirm the thumbnail
   appears and formats are returned, then complete a download on your phone and
   check that playback includes audio.

Health and key checks do **not** verify Instagram retrieval. No deployment or
phone transfer on Google has been tested here. Instagram may reject Google IPs;
resolved CDN URLs can also fail from the phone. If metadata or the download
fails, record the stage and use Flux's safe copied connection status; never share
the secret. Test Render separately if Google is unusable. Do not describe the
original issue as fixed until the actual Reel downloads on the phone.

## Stop or remove the preview

Cloud Run can scale to zero, but that does not delete stored images or secrets.
Deleting the service stops it accepting new requests:

```bash
gcloud run services delete flux-resolver --region="$FLUX_REGION"
```

If you no longer need the dedicated preview resources, remove its registry,
secret and runtime account in Google Cloud Console after confirming nothing
else uses them. Deleting the service alone does not remove those resources.

Official references:

- [Deploy container images to Cloud Run](https://cloud.google.com/run/docs/deploying)
- [Cloud Run secrets and runtime identity](https://cloud.google.com/run/docs/configuring/services/secrets)
- [Build and push Docker images](https://cloud.google.com/build/docs/build-push-docker-image)
- [Cloud Run pricing](https://cloud.google.com/run/pricing)
