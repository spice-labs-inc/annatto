FROM perl:5.38-slim
RUN cpanm --notest CPAN::Meta JSON::PP YAML::Tiny \
    && apt-get update && apt-get install -y --no-install-recommends curl jq \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /work/out && chmod 777 /work /work/out
WORKDIR /work
COPY extract.pl /usr/local/bin/extract.pl
COPY download.sh /usr/local/bin/download.sh
RUN chmod +x /usr/local/bin/extract.pl /usr/local/bin/download.sh
