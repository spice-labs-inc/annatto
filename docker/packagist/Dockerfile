FROM php:8.3-cli
RUN apt-get update && apt-get install -y --no-install-recommends jq curl unzip libzip-dev && rm -rf /var/lib/apt/lists/*
RUN docker-php-ext-install zip
RUN mkdir -p /work/out && chmod 777 /work /work/out
WORKDIR /work
COPY extract.php /usr/local/bin/extract.php
COPY download.sh /usr/local/bin/download.sh
RUN chmod +x /usr/local/bin/extract.php /usr/local/bin/download.sh
