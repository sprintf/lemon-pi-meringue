FROM envoyproxy/envoy:v1.20.1

COPY envoy.yaml envoy.yaml

ENTRYPOINT ["envoy", "-c", "envoy.yaml", "-l", "debug"]