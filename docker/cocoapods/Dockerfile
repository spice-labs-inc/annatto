FROM ruby:3.3-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl jq \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work
RUN mkdir -p /work/out && chmod 777 /work /work/out

COPY extract.rb /usr/local/bin/extract.rb
RUN chmod +x /usr/local/bin/extract.rb

ENTRYPOINT ["ruby", "/usr/local/bin/extract.rb"]
