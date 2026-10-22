// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"bytes"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"runtime/debug"
	"runtime/pprof"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// readUptime is GetUptime: seconds since boot and the 1-minute load average,
// or zeros if /proc cannot be read.
func readUptime() *pb.UptimeInfo {
	up, err1 := firstFloat("/proc/uptime")
	load, err2 := firstFloat("/proc/loadavg")
	if err1 != nil || err2 != nil {
		return &pb.UptimeInfo{}
	}
	return &pb.UptimeInfo{UptimeSeconds: int64(up), LoadAverage_1M: load}
}

func firstFloat(path string) (float64, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	fields := strings.Fields(string(data))
	if len(fields) == 0 {
		return 0, nil
	}
	v, err := strconv.ParseFloat(fields[0], 64)
	if err != nil {
		return 0, nil
	}
	return v, nil
}

// ---------------------------------------------------------------------------
// GetThreadInfo
//
// The protocol's fields were named for the JVM. The Go agent fills them with
// the nearest Go equivalents, so `orphera thread-info` keeps working:
//
//   os_threads       OS threads of the process (/proc/self/status), as before
//   jvm_threads      live goroutines
//   jvm_peak         most goroutines seen (sampled every second) since start
//                    or the last reset
//   jvm_daemon       0 (Go has no daemon threads)
//   jvm_started      goroutines started for streamed tasks since start
//   heap_used_bytes  Go heap in use
//   heap_max_bytes   the GOMEMLIMIT soft limit, or -1 if none is set
//   states           goroutines by what they are waiting on (running,
//                    select, IO wait, ...), largest first
//   groups           the process's OS threads by name, as before

type goroutineStats struct {
	peak    atomic.Int64
	started atomic.Int64
	once    sync.Once
}

var gstats goroutineStats

func (g *goroutineStats) sample() {
	n := int64(runtime.NumGoroutine())
	for {
		p := g.peak.Load()
		if n <= p || g.peak.CompareAndSwap(p, n) {
			return
		}
	}
}

// startSampling begins sampling the goroutine count every second.
func (g *goroutineStats) startSampling() {
	g.once.Do(func() {
		g.sample()
		go func() {
			for range time.Tick(time.Second) {
				g.sample()
			}
		}()
	})
}

func readThreadInfo(resetPeak bool) *pb.ThreadInfo {
	gstats.sample()
	// The peak is read before any reset, so the caller sees the value up to now.
	peak := gstats.peak.Load()
	if resetPeak {
		gstats.peak.Store(int64(runtime.NumGoroutine()))
	}
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	heapMax := debug.SetMemoryLimit(-1)
	if heapMax == math.MaxInt64 {
		heapMax = -1
	}
	status := parseStatus(readLines("/proc/self/status"))
	osThreads, _ := strconv.Atoi(status["Threads"])
	return &pb.ThreadInfo{
		OsThreads:     int32(osThreads),
		JvmThreads:    int32(runtime.NumGoroutine()),
		JvmPeak:       int32(peak),
		JvmDaemon:     0,
		JvmStarted:    gstats.started.Load(),
		Cpus:          int32(runtime.NumCPU()),
		RssBytes:      kilobytes(status["VmRSS"]),
		HeapUsedBytes: int64(ms.HeapAlloc),
		HeapMaxBytes:  heapMax,
		States:        named(counts(goroutineStates())),
		Groups:        named(counts(osThreadGroups())),
	}
}

var goroutineHeader = regexp.MustCompile(`(?m)^goroutine \d+ \[([^\],]+)`)

// goroutineStates is the state of every goroutine, from the runtime's
// goroutine dump.
func goroutineStates() []string {
	var buf bytes.Buffer
	if err := pprof.Lookup("goroutine").WriteTo(&buf, 2); err != nil {
		return nil
	}
	var states []string
	for _, m := range goroutineHeader.FindAllSubmatch(buf.Bytes(), -1) {
		states = append(states, string(m[1]))
	}
	return states
}

// osThreadGroups is every OS thread of this process (/proc/self/task/*/comm),
// grouped by name.
func osThreadGroups() []string {
	tasks, err := os.ReadDir("/proc/self/task")
	if err != nil {
		return nil
	}
	var names []string
	for _, t := range tasks {
		comm, err := os.ReadFile(filepath.Join("/proc/self/task", t.Name(), "comm"))
		if err == nil {
			names = append(names, threadGroupName(string(comm)))
		}
	}
	return names
}

func readLines(path string) []string {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	return strings.Split(string(data), "\n")
}

// The pure parts of the thread report, as in the Scala ThreadStats.

var trailingNumber = regexp.MustCompile(`[-_# ]?[0-9]+$`)

// threadGroupName is a thread's name without its number, so the threads of one
// pool count together; a name that is only digits, or empty, is kept.
func threadGroupName(comm string) string {
	trimmed := strings.TrimSpace(comm)
	stripped := trailingNumber.ReplaceAllString(trimmed, "")
	if stripped == "" {
		return trimmed
	}
	return stripped
}

type nameCount struct {
	name  string
	count int
}

// counts is how often each name occurs, largest first and alphabetical among
// equals.
func counts(names []string) []nameCount {
	m := map[string]int{}
	for _, n := range names {
		m[n]++
	}
	out := make([]nameCount, 0, len(m))
	for n, c := range m {
		out = append(out, nameCount{n, c})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].count != out[j].count {
			return out[i].count > out[j].count
		}
		return out[i].name < out[j].name
	})
	return out
}

func named(cs []nameCount) []*pb.NameCount {
	out := make([]*pb.NameCount, len(cs))
	for i, c := range cs {
		out[i] = &pb.NameCount{Name: validUTF8(c.name), Count: int32(c.count)}
	}
	return out
}

// parseStatus reads /proc/self/status lines (Key:<tab>value) into a map.
func parseStatus(lines []string) map[string]string {
	m := map[string]string{}
	for _, l := range lines {
		if k, v, ok := strings.Cut(l, ":"); ok {
			m[strings.TrimSpace(k)] = strings.TrimSpace(v)
		}
	}
	return m
}

// kilobytes is a /proc size such as "212340 kB" in bytes.
func kilobytes(v string) int64 {
	f := strings.Fields(v)
	if len(f) == 0 {
		return 0
	}
	n, err := strconv.ParseInt(f[0], 10, 64)
	if err != nil {
		return 0
	}
	return n * 1024
}
