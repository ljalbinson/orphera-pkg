// SPDX-License-Identifier: Apache-2.0

// Package agent is the Go implementation of the Orphera agent: the same gRPC
// service (common/src/main/protobuf/Protocol.proto) and behaviour as the
// Scala agent in agent/, so the orchestrator can talk to either.
package agent

import (
	"context"

	"google.golang.org/grpc"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// Version is the agent version reported by GetVersion, set at build time from
// the repository's VERSION file.
var Version = "dev"

// Service implements the Agent gRPC service.
type Service struct {
	pb.UnimplementedAgentServer
	network *networkReloader
}

// NewService returns the service. Call Init before serving.
func NewService() *Service {
	return &Service{network: newNetworkReloader()}
}

// Init takes the known-good network snapshot (if there is none yet) and
// starts the goroutine sampler for GetThreadInfo.
func (s *Service) Init() {
	s.network.initKnownGood()
	gstats.startSampling()
}

func (s *Service) Install(req *pb.InstallPackages, stream grpc.ServerStreamingServer[pb.Event]) error {
	return streamTask(stream, func(q *queue) error {
		return byPackageManager(q,
			func() error { return aptInstall(req, q) },
			func() error { return dnfInstall(req, q) })
	})
}

func (s *Service) Remove(req *pb.RemovePackages, stream grpc.ServerStreamingServer[pb.Event]) error {
	return streamTask(stream, func(q *queue) error {
		return byPackageManager(q,
			func() error { return aptRemove(req, q) },
			func() error { return dnfRemove(req, q) })
	})
}

func (s *Service) RunAutoRemove(req *pb.AutoRemove, stream grpc.ServerStreamingServer[pb.Event]) error {
	return streamTask(stream, func(q *queue) error {
		return byPackageManager(q,
			func() error { return aptAutoRemove(req, q) },
			func() error { return dnfAutoRemove(req, q) })
	})
}

func (s *Service) RunDistUpgrade(req *pb.DistUpgrade, stream grpc.ServerStreamingServer[pb.Event]) error {
	return streamTask(stream, func(q *queue) error {
		return byPackageManager(q,
			func() error { return aptDistUpgrade(req, q) },
			func() error { return dnfDistUpgrade(req, q) })
	})
}

func (s *Service) CopyFile(stream grpc.BidiStreamingServer[pb.FileChunk, pb.Event]) error {
	return streamTask(stream, func(q *queue) error { return receiveFile(stream, q) })
}

func (s *Service) CheckFile(_ context.Context, req *pb.FileCheck) (*pb.FileCheckResult, error) {
	return checkFile(req), nil
}

func (s *Service) ReloadNetwork(_ context.Context, req *pb.NetworkReload) (*pb.NetworkReloadResult, error) {
	return s.network.reload(req)
}

func (s *Service) ConfirmNetwork(_ context.Context, req *pb.NetworkConfirm) (*pb.Empty, error) {
	s.network.confirm(req.BackupId)
	return &pb.Empty{}, nil
}

// InstallDebPackage refuses on a node without a certificate of its own: the
// package no longer ships one, so after the upgrade the agent could not
// start, and this RPC is the only way in (no ssh to recover with).
func (s *Service) InstallDebPackage(req *pb.InstallDeb, stream grpc.ServerStreamingServer[pb.Event]) error {
	return streamTask(stream, func(q *queue) error {
		if !PerNodeCertPresent() {
			q.result("Refusing to install: "+MissingCertMessage, 1, false)
			return nil
		}
		return byPackageManager(q,
			func() error { return debInstall(req, q) },
			func() error { return rpmInstall(req, q) })
	})
}

func (s *Service) RemoteFetchFile(req *pb.FetchFile, stream grpc.ServerStreamingServer[pb.FileData]) error {
	return sendFile(req, stream)
}

func (s *Service) GatherFacts(context.Context, *pb.FactsRequest) (*pb.Facts, error) {
	return gatherFacts(), nil
}

func (s *Service) TriggerReboot(_ context.Context, req *pb.RebootRequest) (*pb.RebootAck, error) {
	return triggerReboot(req)
}

func (s *Service) TriggerShutdown(_ context.Context, req *pb.ShutdownRequest) (*pb.ShutdownAck, error) {
	return triggerShutdown(req)
}

func (s *Service) GetVersion(context.Context, *pb.VersionRequest) (*pb.VersionInfo, error) {
	return &pb.VersionInfo{Version: Version}, nil
}

func (s *Service) GetUptime(context.Context, *pb.UptimeRequest) (*pb.UptimeInfo, error) {
	return readUptime(), nil
}

func (s *Service) GetThreadInfo(_ context.Context, req *pb.ThreadInfoRequest) (*pb.ThreadInfo, error) {
	return readThreadInfo(req.ResetPeak), nil
}

func (s *Service) ExecuteCommand(req *pb.RunCommandRequest, stream grpc.ServerStreamingServer[pb.Event]) error {
	return streamTask(stream, func(q *queue) error { return runCommand(req, q) })
}
