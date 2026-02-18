FROM php:8.3-cli
RUN useradd -m extractor
WORKDIR /work
COPY extract.php /usr/local/bin/extract.php
USER extractor
ENTRYPOINT ["php", "/usr/local/bin/extract.php"]
