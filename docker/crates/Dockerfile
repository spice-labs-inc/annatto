FROM rust:1.77-slim
RUN useradd -m extractor && apt-get update && apt-get install -y jq && rm -rf /var/lib/apt/lists/*
WORKDIR /work
COPY extract.sh /usr/local/bin/extract.sh
RUN chmod +x /usr/local/bin/extract.sh
USER extractor
ENTRYPOINT ["extract.sh"]
