FROM ubuntu:24.04
RUN apt-get update && apt-get install -y lua5.4 luarocks && rm -rf /var/lib/apt/lists/* && useradd -m extractor
WORKDIR /work
COPY extract.lua /usr/local/bin/extract.lua
USER extractor
ENTRYPOINT ["lua5.4", "/usr/local/bin/extract.lua"]
