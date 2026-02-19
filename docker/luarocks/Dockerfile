FROM ubuntu:24.04

RUN apt-get update && apt-get install -y --no-install-recommends \
        lua5.4 luarocks curl jq unzip ca-certificates && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p /work/out && chmod 777 /work /work/out

WORKDIR /work

COPY extract.lua /usr/local/bin/extract.lua
COPY download.sh /usr/local/bin/download.sh
RUN chmod +x /usr/local/bin/extract.lua /usr/local/bin/download.sh
