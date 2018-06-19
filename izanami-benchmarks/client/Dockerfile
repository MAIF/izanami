FROM debian:jessie
RUN mkdir -p /client
WORKDIR /client
COPY . /client
RUN apt-get update -y && apt-get install curl tar build-essential libssl-dev git -y
RUN git clone https://github.com/giltene/wrk2.git wrk2
RUN cd wrk2 && make && cp wrk /usr/local/bin
RUN curl -O https://dl.google.com/go/go1.10.2.linux-amd64.tar.gz
RUN tar xf go1.10.2.linux-amd64.tar.gz && chown -R root:root ./go && mv go /usr/local && mkdir /gopath

ENV GOPATH=/gopath
ENV PATH=$PATH:/usr/local/go/bin:/gopath/bin

RUN go get -u github.com/rakyll/hey
CMD [ "/bin/sh", "client.sh" ]