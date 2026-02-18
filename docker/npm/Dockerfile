FROM node:21-slim
RUN mkdir -p /work/out /tmp/npm-cache && chmod 777 /work /work/out /tmp/npm-cache
WORKDIR /work
ENV npm_config_cache=/tmp/npm-cache
COPY extract.sh /usr/local/bin/extract.sh
COPY download.sh /usr/local/bin/download.sh
RUN chmod +x /usr/local/bin/extract.sh /usr/local/bin/download.sh
