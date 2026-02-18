FROM perl:5.38-slim
RUN cpanm --notest CPAN::Meta JSON::PP YAML::Tiny && useradd -m extractor
WORKDIR /work
COPY extract.pl /usr/local/bin/extract.pl
RUN chmod +x /usr/local/bin/extract.pl
USER extractor
ENTRYPOINT ["perl", "/usr/local/bin/extract.pl"]
