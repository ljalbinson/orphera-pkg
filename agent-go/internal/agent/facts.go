// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"time"

	"golang.org/x/sys/unix"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// gatherFacts is GatherFacts (FactGatherer). Each part that cannot be read is
// left empty or zero rather than failing the call.
//
// The disk and interface lists reproduce what the JVM agent reported, down to
// the order, because the orchestrator depends on it: it templates ip_<name>
// from each interface's first address and ip_secondary from the second
// interface's first address.
func gatherFacts() *pb.Facts {
	osRelease := readOsRelease("/etc/os-release")
	f := &pb.Facts{
		Hostname:               strings.TrimSpace(readCommand("hostname")),
		OsId:                   osRelease["ID"],
		OsVersion:              osRelease["VERSION_ID"],
		KernelVersion:          strings.TrimSpace(readCommand("uname", "-r")),
		Architecture:           strings.TrimSpace(readCommand("uname", "-m")),
		CpuCount:               uint32(runtime.NumCPU()),
		MemoryTotalBytes:       readMemTotalBytes("/proc/meminfo"),
		Disks:                  readDisks("/etc/mtab"),
		Interfaces:             javaInterfaces(),
		CollectedAtEpochMillis: time.Now().UnixMilli(),
	}
	f.Hostname = validUTF8(f.Hostname)
	f.OsId = validUTF8(f.OsId)
	f.OsVersion = validUTF8(f.OsVersion)
	f.KernelVersion = validUTF8(f.KernelVersion)
	f.Architecture = validUTF8(f.Architecture)
	return f
}

// readCommand is the command's output with stderr mixed in, or "" if it
// could not be run.
func readCommand(name string, args ...string) string {
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		if _, isExit := err.(*exec.ExitError); !isExit {
			return ""
		}
	}
	return string(out)
}

// readOsRelease parses KEY=value lines; one pair of surrounding double quotes
// is removed, and a later line wins over an earlier one.
func readOsRelease(path string) map[string]string {
	m := map[string]string{}
	data, err := os.ReadFile(path)
	if err != nil {
		return m
	}
	for _, line := range strings.Split(strings.TrimSuffix(string(data), "\n"), "\n") {
		i := strings.IndexByte(line, '=')
		if i < 0 {
			continue
		}
		v := strings.TrimPrefix(line[i+1:], `"`)
		v = strings.TrimSuffix(v, `"`)
		m[line[:i]] = v
	}
	return m
}

var nonDigits = regexp.MustCompile(`[^0-9]`)

func readMemTotalBytes(path string) uint64 {
	f, err := os.Open(path)
	if err != nil {
		return 0
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if line := sc.Text(); strings.HasPrefix(line, "MemTotal:") {
			n, err := strconv.ParseUint(nonDigits.ReplaceAllString(line, ""), 10, 64)
			if err != nil {
				return 0
			}
			return n * 1024 // /proc/meminfo reports kB
		}
	}
	return 0
}

// readDisks lists every mount the way Java's FileSystems.getFileStores did:
// every line of /etc/mtab in order, except those with the "ignore" option and
// those whose sizes cannot be read.
//
// mount_point carries the mount's device or source (/dev/sda1, tmpfs,
// proc), not the directory it is mounted on: the JVM agent filled it from
// FileStore.name(), which is the device, and this keeps the facts identical.
func readDisks(mtab string) []*pb.DiskInfo {
	f, err := os.Open(mtab)
	if err != nil {
		return nil
	}
	defer f.Close()
	var disks []*pb.DiskInfo
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) < 4 || strings.HasPrefix(fields[0], "#") {
			continue
		}
		device, dir, fstype, opts := unescapeMount(fields[0]), unescapeMount(fields[1]), unescapeMount(fields[2]), fields[3]
		if hasMountOption(opts, "ignore") {
			continue
		}
		var st unix.Statfs_t
		if unix.Statfs(dir, &st) != nil {
			continue
		}
		frsize := uint64(st.Frsize)
		if frsize == 0 {
			frsize = uint64(st.Bsize)
		}
		disks = append(disks, &pb.DiskInfo{
			MountPoint:     validUTF8(device),
			Filesystem:     validUTF8(fstype),
			TotalBytes:     frsize * st.Blocks,
			AvailableBytes: frsize * st.Bavail,
		})
	}
	return disks
}

func hasMountOption(opts, opt string) bool {
	for _, o := range strings.Split(opts, ",") {
		if o == opt || strings.HasPrefix(o, opt+"=") {
			return true
		}
	}
	return false
}

// unescapeMount decodes the octal escapes getmntent decodes (\040 space,
// \011 tab, \012 newline, \134 backslash).
func unescapeMount(s string) string {
	if !strings.Contains(s, `\`) {
		return s
	}
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		if s[i] == '\\' && i+1 < len(s) && s[i+1] == '\\' {
			b.WriteByte('\\')
			i++
			continue
		}
		if s[i] == '\\' && i+4 <= len(s) {
			switch s[i+1 : i+4] {
			case "040":
				b.WriteByte(' ')
				i += 3
				continue
			case "011":
				b.WriteByte('\t')
				i += 3
				continue
			case "012":
				b.WriteByte('\n')
				i += 3
				continue
			case "134":
				b.WriteByte('\\')
				i += 3
				continue
			}
		}
		b.WriteByte(s[i])
	}
	return b.String()
}

// PrintFactsForComparison writes the interface and disk facts in the form
// compare/Facts.java prints what the JVM sees, so the two can be diffed on a
// node (compare/compare-facts.sh). Used by `orphera-agent-go --print-facts`.
func PrintFactsForComparison(w io.Writer) {
	for _, i := range javaInterfaces() {
		fmt.Fprintf(w, "IF %s [%s] %s\n", i.Name, strings.Join(i.IpAddresses, ", "), i.MacAddress)
	}
	for _, d := range readDisks("/etc/mtab") {
		fmt.Fprintf(w, "DISK %s %s %d %d\n", d.MountPoint, d.Filesystem, d.TotalBytes, d.AvailableBytes)
	}
}
