# SPDX-License-Identifier: Apache-2.0
#
# The Go agent. Independent of the sbt build: nothing here touches agent/,
# common/ or the packaging of the Scala agent.

VERSION := $(shell cat ../VERSION)
LDFLAGS := -s -w -X main.version=$(VERSION)
GOBUILD := CGO_ENABLED=0 go build -trimpath -ldflags "$(LDFLAGS)"

.PHONY: all build amd64 arm64 test gen clean

all: test build

build: amd64 arm64

amd64:
	GOOS=linux GOARCH=amd64 $(GOBUILD) -o bin/orphera-agent-go-amd64 ./cmd/agent

arm64:
	GOOS=linux GOARCH=arm64 $(GOBUILD) -o bin/orphera-agent-go-arm64 ./cmd/agent

test:
	go vet ./...
	go test -race ./...

# Regenerate internal/pb from common/src/main/protobuf/Protocol.proto.
gen:
	./gen.sh

clean:
	rm -rf bin
