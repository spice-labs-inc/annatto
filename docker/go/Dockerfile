FROM golang:1.22-alpine
RUN apk add --no-cache jq unzip
RUN mkdir -p /work/out && chmod 777 /work /work/out
WORKDIR /work
COPY extract.sh /usr/local/bin/extract.sh
COPY download.sh /usr/local/bin/download.sh
RUN chmod +x /usr/local/bin/extract.sh /usr/local/bin/download.sh
