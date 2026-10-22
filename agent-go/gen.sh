#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Regenerates internal/pb from the protocol shared with the Scala agent and
# orchestrator (common/src/main/protobuf/Protocol.proto). The .proto is read,
# never changed: the Go package is given here with -M rather than by adding a
# go_package option to the shared file.
#
# Needs protoc, protoc-gen-go v1.35.2 and protoc-gen-go-grpc v1.5.1 on PATH:
#   go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.35.2
#   go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.5.1
set -e
cd "$(dirname "$0")"
proto_dir=../common/src/main/protobuf
pkg=github.com/ljalbinson/orphera-pkg/agent-go/internal/pb
mkdir -p internal/pb
protoc -I "$proto_dir" \
  --go_out=internal/pb --go_opt=paths=source_relative --go_opt=MProtocol.proto=$pkg \
  --go-grpc_out=internal/pb --go-grpc_opt=paths=source_relative --go-grpc_opt=MProtocol.proto=$pkg \
  Protocol.proto
