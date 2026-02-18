FROM continuumio/miniconda3:latest
RUN conda install -y conda-package-handling && useradd -m extractor
WORKDIR /work
COPY extract.py /usr/local/bin/extract.py
USER extractor
ENTRYPOINT ["python3", "/usr/local/bin/extract.py"]
