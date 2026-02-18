FROM ruby:3.3-slim
RUN useradd -m extractor
WORKDIR /work
COPY extract.rb /usr/local/bin/extract.rb
USER extractor
ENTRYPOINT ["ruby", "/usr/local/bin/extract.rb"]
