FROM continuumio/miniconda3:latest

RUN apt-get update && apt-get install -y --no-install-recommends jq curl && \
    rm -rf /var/lib/apt/lists/* && \
    conda install -y conda-package-handling zstandard && \
    conda clean -afy

RUN mkdir -p /work/out && chmod 777 /work /work/out

WORKDIR /work

COPY extract.py /usr/local/bin/extract.py
COPY download.sh /usr/local/bin/download.sh
RUN chmod +x /usr/local/bin/extract.py /usr/local/bin/download.sh
