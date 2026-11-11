// SPDX-License-Identifier: Apache-2.0

package agent

import (
	"os"
	"path/filepath"
	"strings"
)

// ProtectedDir is the agent's own directory: its token (agent.env) and its
// TLS key and CA certificate (tls/). CopyFile, RemoteFetchFile and CheckFile
// do not touch it; the token and certificates are set over SSH
// (utils/set-agent-token.sh, orphera issue-certs). As in the Scala agent.
//
// This is not a sandbox: ExecuteCommand runs any command as root, so whoever
// holds the token can still reach any file. The token and mutual TLS are what
// keep others out.
const ProtectedDir = "/etc/orphera-agent"

// protectedRefusal is why path is refused, or "" if it is not.
func protectedRefusal(path string) string {
	if !isProtected(path, ProtectedDir) {
		return ""
	}
	return "Refused: " + path + " is in " + ProtectedDir + ", which holds this agent's token and TLS key; " +
		"copy and fetch do not touch it (the token and certificates are set over SSH: " +
		"utils/set-agent-token.sh, orphera issue-certs)"
}

// isProtected is whether path is dir or under it, after making it absolute,
// resolving "..", and following symbolic links (for a file that does not
// exist yet, those of its directory), so another name for the same place
// counts too.
func isProtected(path, dir string) bool {
	d := realPath(dir)
	p := resolvePath(path)
	return p == d || strings.HasPrefix(p, d+string(filepath.Separator))
}

func resolvePath(path string) string {
	abs, err := filepath.Abs(path)
	if err != nil {
		abs = filepath.Clean(path)
	}
	if _, err := os.Lstat(abs); err == nil {
		return realPath(abs)
	}
	return filepath.Join(realPath(filepath.Dir(abs)), filepath.Base(abs))
}

// realPath is path with symbolic links followed, as far as it exists.
func realPath(path string) string {
	abs, err := filepath.Abs(path)
	if err != nil {
		abs = filepath.Clean(path)
	}
	if r, err := filepath.EvalSymlinks(abs); err == nil {
		return r
	}
	return abs
}
