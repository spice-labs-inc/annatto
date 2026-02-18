FROM python:3.12-slim
RUN pip install --no-cache-dir pkginfo
RUN mkdir -p /work/out && chmod 777 /work /work/out
WORKDIR /work
COPY extract.py /usr/local/bin/extract.py
COPY download.sh /usr/local/bin/download.sh
RUN chmod +x /usr/local/bin/extract.py /usr/local/bin/download.sh
