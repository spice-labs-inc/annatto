FROM elixir:1.16-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl jq ca-certificates && rm -rf /var/lib/apt/lists/*
RUN mix local.hex --force
WORKDIR /work
RUN mkdir -p /work/out && chmod 777 /work /work/out
COPY extract.exs /usr/local/bin/extract.exs
COPY download.sh /usr/local/bin/download.sh
RUN chmod +x /usr/local/bin/download.sh /usr/local/bin/extract.exs
ENTRYPOINT ["elixir", "/usr/local/bin/extract.exs"]
