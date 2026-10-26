# syntax=docker/dockerfile:1
FROM python:3.12-slim AS extractor
WORKDIR /build
COPY android/tools /build/android/tools
RUN --mount=type=secret,id=proxy_ca python -c "import os,ssl,urllib.request; ctx=ssl.create_default_context(cafile='/run/secrets/proxy_ca' if os.path.isfile('/run/secrets/proxy_ca') else None); open('/tmp/upstream','wb').write(urllib.request.urlopen('https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp',context=ctx,timeout=60).read())" && python android/tools/package-extractor.py /tmp/upstream
FROM python:3.12-slim
WORKDIR /app
COPY server/*.py /app/server/
COPY --from=extractor /build/android/app/src/main/res/raw/ytdlp /app/android/app/src/main/res/raw/ytdlp
RUN useradd --uid 10001 --create-home flux
USER 10001
ENV PORT=8080 PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8080
CMD ["python", "server/flux_server.py"]
