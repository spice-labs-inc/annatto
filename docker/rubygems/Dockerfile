FROM ruby:3.3-slim
RUN apt-get update && apt-get install -y jq curl && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /work/out && chmod 777 /work /work/out
WORKDIR /work
COPY extract.rb /usr/local/bin/extract.rb
COPY download.sh /usr/local/bin/download.sh
RUN chmod +x /usr/local/bin/extract.rb /usr/local/bin/download.sh
