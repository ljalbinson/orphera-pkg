// SPDX-License-Identifier: Apache-2.0

// Command agent is the Orphera agent written in Go. It serves the same gRPC
// protocol as the Scala agent, on the same port, with the same token and
// certificates, so it can stand in for it on a node.
package main

import (
	"crypto/tls"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/agent"
	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// version is set at build time: -ldflags "-X main.version=$(cat ../VERSION)".
var version = "dev"

const port = 50051

func main() {
	if len(os.Args) > 1 && (os.Args[1] == "--version" || os.Args[1] == "-v") {
		fmt.Println(version)
		return
	}
	if len(os.Args) > 1 && os.Args[1] == "--print-facts" {
		agent.PrintFactsForComparison(os.Stdout)
		return
	}
	if err := run(); err != nil {
		fmt.Println(err)
		os.Exit(1)
	}
}

func run() error {
	t, set := os.LookupEnv("ORPHERA_TOKEN")
	token, err := agent.RequireToken(t, set)
	if err != nil {
		return err
	}
	certFile, keyFile, ok := agent.ChooseCert()
	if !ok {
		return fmt.Errorf("%s", agent.MissingCertMessage)
	}
	cert, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return fmt.Errorf("cannot load TLS certificate %s: %w", certFile, err)
	}

	agent.Version = version
	svc := agent.NewService()
	svc.Init()

	opts := append(agent.AuthInterceptors(token),
		grpc.Creds(credentials.NewTLS(&tls.Config{
			Certificates: []tls.Certificate{cert},
			MinVersion:   tls.VersionTLS12,
		})))
	server := grpc.NewServer(opts...)
	pb.RegisterAgentServer(server, svc)

	lis, err := net.Listen("tcp", fmt.Sprintf(":%d", port))
	if err != nil {
		return err
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-stop
		fmt.Println("Agent shutting down")
		done := make(chan struct{})
		go func() { server.GracefulStop(); close(done) }()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			server.Stop()
		}
	}()

	fmt.Printf("Orphera Agent listening on %d (TLS, %s)\n", port, certFile)
	return server.Serve(lis)
}
