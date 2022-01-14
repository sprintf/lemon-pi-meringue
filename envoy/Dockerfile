FROM envoyproxy/envoy:v1.20.1

COPY envoy.yaml envoy.yaml
ADD https://github.com/ahmetb/runsd/releases/download/v0.0.0-rc.15/runsd /bin/runsd
RUN chmod +x /bin/runsd
ENTRYPOINT ["runsd", "--", "envoy", "-c", "envoy.yaml", "-l", "info"]
