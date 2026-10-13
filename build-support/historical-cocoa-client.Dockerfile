FROM ruby:3.3.8-slim
RUN apt-get update && apt-get install -y --no-install-recommends git build-essential libffi-dev curl unzip rsync && rm -rf /var/lib/apt/lists/* && gem install cocoapods --version 1.16.2 --no-document
