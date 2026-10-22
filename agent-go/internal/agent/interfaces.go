// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"bufio"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"unsafe"

	"golang.org/x/sys/unix"

	"github.com/ljalbinson/orphera-pkg/agent-go/internal/pb"
)

// The network interfaces part of the facts, in exactly the form and order the
// JVM agent reported them (java.net.NetworkInterface on Linux, as in JDK 21's
// NetworkInterface.c), because the orchestrator templates ip_<name> and
// ip_secondary from the first address of each interface and the second
// interface.
//
// The JDK builds its list from the IPv4 addresses (ioctl SIOCGIFCONF) and
// then the IPv6 ones (/proc/net/if_inet6), and adds every new interface and
// every address at the FRONT of its list. So the interfaces come out in
// reverse order of discovery, and an interface's IPv6 addresses come before
// its IPv4 ones, each group reversed. IPv6 addresses are written in full,
// without "::" shortening and with the interface as the scope
// ("fe80:0:0:0:a00:27ff:fe4e:1b2c%eth0"). Only interfaces that are up and
// running, and not loopback, are kept.

// rawAddr is one address as the JDK discovered it.
type rawAddr struct {
	ifName string // as reported, possibly an alias such as eth0:1
	ip     net.IP // 4 or 16 bytes
}

// javaNetif is one entry of the JDK's interface list.
type javaNetif struct {
	name  string
	addrs []net.IP // most recently added first
}

// javaInterfaceList reproduces the JDK's addif: v4 then v6 addresses, each
// new interface and each address put at the front. An alias (eth0:1) whose
// parent's flags can be read adds its address to the parent; otherwise it is
// an interface of its own.
func javaInterfaceList(v4, v6 []rawAddr, parentReadable func(string) bool) []*javaNetif {
	var list []*javaNetif // front of the JDK's list is index 0
	add := func(a rawAddr) {
		name := a.ifName
		if i := strings.IndexByte(name, ':'); i >= 0 && parentReadable(name[:i]) {
			name = name[:i]
		}
		var cur *javaNetif
		for _, n := range list {
			if n.name == name {
				cur = n
				break
			}
		}
		if cur == nil {
			cur = &javaNetif{name: name}
			list = append([]*javaNetif{cur}, list...)
		}
		cur.addrs = append([]net.IP{a.ip}, cur.addrs...)
	}
	for _, a := range v4 {
		add(a)
	}
	for _, a := range v6 {
		add(a)
	}
	return list
}

// javaHostAddress is InetAddress.getHostAddress for an interface address.
func javaHostAddress(ip net.IP, ifName string) string {
	if v4 := ip.To4(); v4 != nil && len(ip) == net.IPv4len {
		return v4.String()
	}
	parts := make([]string, 8)
	for i := 0; i < 8; i++ {
		parts[i] = strconv.FormatUint(uint64(ip[2*i])<<8|uint64(ip[2*i+1]), 16)
	}
	return strings.Join(parts, ":") + "%" + ifName
}

// interfaceOS is what the list needs from the system, so tests can stand in
// for it.
type interfaceOS struct {
	ipv4   func() ([]rawAddr, error)
	ipv6   func() []rawAddr
	flags  func(name string) (uint16, error)
	hwaddr func(name string) ([]byte, error)
}

func javaInterfaces() []*pb.NetworkInterface {
	return javaInterfacesWith(interfaceOS{ipv4: ioctlIPv4, ipv6: procIPv6, flags: ioctlFlags, hwaddr: ioctlHwaddr})
}

// javaInterfacesWith returns nil wherever the JVM agent's enumeration would
// have thrown (the Scala code then reported no interfaces at all).
func javaInterfacesWith(sys interfaceOS) []*pb.NetworkInterface {
	v4, err := sys.ipv4()
	if err != nil {
		return nil
	}
	list := javaInterfaceList(v4, sys.ipv6(), func(name string) bool {
		_, err := sys.flags(name)
		return err == nil
	})
	var out []*pb.NetworkInterface
	for _, n := range list {
		fl, err := sys.flags(n.name)
		if err != nil {
			return nil
		}
		if fl&unix.IFF_LOOPBACK != 0 || fl&unix.IFF_UP == 0 || fl&unix.IFF_RUNNING == 0 {
			continue
		}
		hw, err := sys.hwaddr(n.name)
		if err != nil {
			return nil
		}
		mac := ""
		if !allZero(hw) {
			hexes := make([]string, len(hw))
			for i, b := range hw {
				hexes[i] = fmt.Sprintf("%02x", b)
			}
			mac = strings.Join(hexes, ":")
		}
		addrs := make([]string, len(n.addrs))
		for i, ip := range n.addrs {
			addrs[i] = javaHostAddress(ip, n.name)
		}
		out = append(out, &pb.NetworkInterface{Name: validUTF8(n.name), IpAddresses: addrs, MacAddress: mac})
	}
	return out
}

func allZero(b []byte) bool {
	for _, x := range b {
		if x != 0 {
			return false
		}
	}
	return true
}

// ---------------------------------------------------------------------------
// System access

// ifreq is struct ifreq on 64-bit Linux: a 16-byte name and a 24-byte union.
type ifreq struct {
	name [unix.IFNAMSIZ]byte
	data [24]byte
}

// ifconf is struct ifconf on 64-bit Linux.
type ifconf struct {
	length int32
	_      int32
	buf    uintptr
}

func ioctlPtr(fd int, req uint, arg unsafe.Pointer) error {
	_, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), uintptr(req), uintptr(arg))
	if errno != 0 {
		return errno
	}
	return nil
}

func newIfreq(name string) (*ifreq, error) {
	if len(name) >= unix.IFNAMSIZ {
		return nil, errors.New("interface name too long: " + name)
	}
	r := &ifreq{}
	copy(r.name[:], name)
	return r, nil
}

// ioctlIPv4 lists the IPv4 addresses with SIOCGIFCONF, in the kernel's order.
func ioctlIPv4() ([]rawAddr, error) {
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_DGRAM, 0)
	if err != nil {
		return nil, err
	}
	defer unix.Close(fd)
	var ifc ifconf
	if err := ioctlPtr(fd, unix.SIOCGIFCONF, unsafe.Pointer(&ifc)); err != nil {
		return nil, err
	}
	n := int(ifc.length) / int(unsafe.Sizeof(ifreq{}))
	if n == 0 {
		return nil, nil
	}
	reqs := make([]ifreq, n)
	ifc.buf = uintptr(unsafe.Pointer(&reqs[0]))
	err = ioctlPtr(fd, unix.SIOCGIFCONF, unsafe.Pointer(&ifc))
	if err != nil {
		return nil, err
	}
	n = int(ifc.length) / int(unsafe.Sizeof(ifreq{}))
	var out []rawAddr
	for _, r := range reqs[:n] {
		family := *(*uint16)(unsafe.Pointer(&r.data[0]))
		if family != unix.AF_INET {
			continue
		}
		ip := net.IPv4(r.data[4], r.data[5], r.data[6], r.data[7]).To4()
		out = append(out, rawAddr{ifName: cString(r.name[:]), ip: ip})
	}
	return out, nil
}

func cString(b []byte) string {
	for i, c := range b {
		if c == 0 {
			return string(b[:i])
		}
	}
	return string(b)
}

// procIPv6 lists the IPv6 addresses from /proc/net/if_inet6, in file order,
// if this host has IPv6 (as the JDK's ipv6_available decides).
func procIPv6() []rawAddr {
	const path = "/proc/net/if_inet6"
	if fd, err := unix.Socket(unix.AF_INET6, unix.SOCK_STREAM, 0); err != nil {
		return nil
	} else {
		unix.Close(fd)
	}
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	return parseIfInet6(bufio.NewScanner(f))
}

func parseIfInet6(sc *bufio.Scanner) []rawAddr {
	var out []rawAddr
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) < 6 || len(fields[0]) != 32 {
			continue
		}
		b, err := hex.DecodeString(fields[0])
		if err != nil {
			continue
		}
		out = append(out, rawAddr{ifName: fields[5], ip: net.IP(b)})
	}
	return out
}

func ioctlFlags(name string) (uint16, error) {
	r, err := newIfreq(name)
	if err != nil {
		return 0, err
	}
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_DGRAM, 0)
	if err != nil {
		return 0, err
	}
	defer unix.Close(fd)
	if err := ioctlPtr(fd, unix.SIOCGIFFLAGS, unsafe.Pointer(r)); err != nil {
		return 0, err
	}
	return *(*uint16)(unsafe.Pointer(&r.data[0])), nil
}

// ioctlHwaddr is the interface's 6-byte hardware address (SIOCGIFHWADDR).
func ioctlHwaddr(name string) ([]byte, error) {
	r, err := newIfreq(name)
	if err != nil {
		return nil, err
	}
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_DGRAM, 0)
	if err != nil {
		fd, err = unix.Socket(unix.AF_INET6, unix.SOCK_DGRAM, 0)
		if err != nil {
			return nil, err
		}
	}
	defer unix.Close(fd)
	if err := ioctlPtr(fd, unix.SIOCGIFHWADDR, unsafe.Pointer(r)); err != nil {
		return nil, err
	}
	hw := make([]byte, 6)
	copy(hw, r.data[2:8]) // sockaddr: 2-byte family, then the address
	return hw, nil
}
