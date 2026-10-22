// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"fmt"
	"strings"
	"sync"

	"google.golang.org/grpc"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// queue is an unbounded queue of events, the counterpart of the Scala
// agent's Queue.unbounded[IO, Event]. Offering never blocks, so a task keeps
// running at full speed even if the caller reads slowly or has gone away.
type queue struct {
	mu     sync.Mutex
	items  []*pb.Event
	signal chan struct{} // receives one value whenever items becomes non-empty
}

func newQueue() *queue {
	return &queue{signal: make(chan struct{}, 1)}
}

// offer adds an event. Strings are made valid UTF-8 first: the JVM agent
// decoded process output with replacement characters, and Go's protobuf
// marshalling rejects an invalid string outright, which would end the stream.
func (q *queue) offer(e *pb.Event) {
	e.Message = validUTF8(e.Message)
	q.mu.Lock()
	q.items = append(q.items, e)
	q.mu.Unlock()
	select {
	case q.signal <- struct{}{}:
	default:
	}
}

func (q *queue) progress(msg string) { q.offer(&pb.Event{Kind: pb.Event_PROGRESS, Message: msg}) }
func (q *queue) output(line string)  { q.offer(&pb.Event{Kind: pb.Event_OUTPUT, Message: line}) }

// result offers the terminal RESULT event.
func (q *queue) result(msg string, exitCode int32, success bool) {
	q.offer(&pb.Event{Kind: pb.Event_RESULT, Message: msg, ExitCode: exitCode, Success: success})
}

// failed offers a FAILED RESULT built from err, as the Scala agent did for an
// error raised inside a task.
func (q *queue) failed(err error) {
	q.result("FAILED: "+err.Error(), 1, false)
}

// take removes and returns the next event, waiting for one, or returns false
// when done is closed first.
func (q *queue) take(done <-chan struct{}) (*pb.Event, bool) {
	for {
		q.mu.Lock()
		if len(q.items) > 0 {
			e := q.items[0]
			q.items[0] = nil
			q.items = q.items[1:]
			q.mu.Unlock()
			return e, true
		}
		q.mu.Unlock()
		select {
		case <-q.signal:
		case <-done:
			return nil, false
		}
	}
}

// streamTask runs work in a detached goroutine and streams its events to the
// caller until a RESULT event, which is the only thing that ends the stream.
//
// As in the Scala agent, a task that fails before sending its RESULT would
// otherwise leave the stream, and the orchestrator reading it, open forever
// (seen with a failed `apt-get update`). So an error returned by work, or a
// panic inside it, becomes a FAILED RESULT here. If work already sent its
// RESULT, the extra event is queued behind it and never read.
//
// The goroutine is not stopped if the caller goes away: the task finishes,
// exactly as the detached fiber did.
func streamTask(stream grpc.ServerStreamingServer[pb.Event], work func(q *queue) error) error {
	q := newQueue()
	gstats.started.Add(1)
	go func() {
		defer func() {
			if r := recover(); r != nil {
				q.failed(fmt.Errorf("%v", r))
			}
		}()
		if err := work(q); err != nil {
			q.failed(err)
		}
	}()
	done := stream.Context().Done()
	for {
		e, ok := q.take(done)
		if !ok {
			return stream.Context().Err()
		}
		if err := stream.Send(e); err != nil {
			return err
		}
		if e.Kind == pb.Event_RESULT {
			return nil
		}
	}
}

func validUTF8(s string) string {
	return strings.ToValidUTF8(s, "�")
}
