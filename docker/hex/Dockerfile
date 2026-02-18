FROM elixir:1.16-slim
RUN mix local.hex --force && useradd -m extractor
WORKDIR /work
COPY extract.exs /usr/local/bin/extract.exs
USER extractor
ENTRYPOINT ["elixir", "/usr/local/bin/extract.exs"]
