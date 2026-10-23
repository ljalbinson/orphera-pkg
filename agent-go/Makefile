# SPDX-License-Identifier: Apache-2.0
#
# The Go agent. Independent of the sbt build: nothing here changes agent/,
# common/ or the Scala agent's packaging (the shared service unit, agent.env,
# prerm and conffiles are read from ../packaging, not copied or edited).

# The Go agent reports, and its package carries, the repository version with
# "+go" added: 0.1.203+go. dpkg orders it after the Scala package of the same
# release (0.1.203) and before the next one (0.1.204), and `orphera version`
# shows which agent a node is running. deploy-agent waits for a node to report
# exactly the package's version, so it only confirms once the Go agent itself
# has started.
VERSION   := $(shell cat ../VERSION)+go
ARCH      := amd64
LDFLAGS   := -s -w -X main.version=$(VERSION)
GOBUILD   := CGO_ENABLED=0 go build -trimpath -ldflags "$(LDFLAGS)"
CERTS_DIR := $(or $(ORPHERA_CERTS_DIR),../certs)
PKG_DIR   := pkg/orphera-agent-$(ARCH)
DEB_FILE  := orphera-agent_$(VERSION)_$(ARCH).deb
RPM_ARCH  := $(if $(filter arm64,$(ARCH)),aarch64,x86_64)
RPM_TOP   := $(CURDIR)/pkg/rpmbuild-$(ARCH)
RPM_FILE  := orphera-agent-$(VERSION)-1.$(RPM_ARCH).rpm

.PHONY: all build amd64 arm64 test gen stage deb rpm clean

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

# The orphera-agent packages with the Go agent in them, for
#   orphera deploy-agent --agent agent-go/$(DEB_FILE) --nodes ...
#   orphera deploy-agent --agent agent-go/$(RPM_FILE) --nodes ...
# Same package name, files and service as the Scala packages, so either
# replaces the other in place. For arm64 hosts: make deb ARCH=arm64 (or rpm).
#
# Built here in agent-go/ rather than in the repository root on purpose:
# deploy-agent without --agent picks the newest orphera-agent package in the
# current directory, and that should stay the Scala package until you choose.

# The tree both packages are built from (DEBIAN/ is dropped for the rpm).
stage: test $(ARCH)
	rm -rf $(PKG_DIR)
	mkdir -p $(PKG_DIR)/DEBIAN \
	  $(PKG_DIR)/opt/orphera-agent \
	  $(PKG_DIR)/etc/orphera-agent/certs \
	  $(PKG_DIR)/etc/systemd/system \
	  $(PKG_DIR)/var/lib/orphera/network-backups
	install -m 0755 bin/orphera-agent-go-$(ARCH) $(PKG_DIR)/opt/orphera-agent/orphera-agent-go
	install -m 0755 packaging/deb/run.sh $(PKG_DIR)/opt/orphera-agent/run.sh
	install -m 0644 ../packaging/agent.env $(PKG_DIR)/etc/orphera-agent/agent.env
	install -m 0644 ../packaging/orphera-agent.service $(PKG_DIR)/etc/systemd/system/orphera-agent.service
	@test -f $(CERTS_DIR)/ca.crt || (echo "ERROR: $(CERTS_DIR)/ca.crt not found - run: make certs (in the repository root)" && exit 1)
	install -m 0644 $(CERTS_DIR)/ca.crt $(PKG_DIR)/etc/orphera-agent/certs/ca.crt
	sed -e "s/@VERSION@/$(VERSION)/" -e "s/@ARCH@/$(ARCH)/" packaging/deb/control.template > $(PKG_DIR)/DEBIAN/control
	install -m 0755 packaging/deb/postinst $(PKG_DIR)/DEBIAN/postinst
	install -m 0755 ../packaging/prerm $(PKG_DIR)/DEBIAN/prerm
	install -m 0644 ../packaging/conffiles $(PKG_DIR)/DEBIAN/conffiles

deb: stage
	dpkg-deb --build --root-owner-group $(PKG_DIR) $(DEB_FILE)
	@echo "Built $(DEB_FILE). Deploy with: orphera deploy-agent --agent agent-go/$(DEB_FILE) --nodes <nodes>"

# Needs rpmbuild (on Ubuntu: sudo apt install rpm).
rpm: stage
	@command -v rpmbuild >/dev/null || (echo "ERROR: rpmbuild not found (Ubuntu: sudo apt install rpm)" && exit 1)
	rm -rf $(RPM_TOP)
	mkdir -p $(RPM_TOP)
	sed "s/@VERSION@/$(VERSION)/" packaging/rpm/orphera-agent-go.spec.template > $(RPM_TOP)/orphera-agent.spec
	rpmbuild -bb --target $(RPM_ARCH) --define "_topdir $(RPM_TOP)" --define "stagedir $(CURDIR)/$(PKG_DIR)" \
	  --define "_binary_payload w19.zstdio" $(RPM_TOP)/orphera-agent.spec
	cp $(RPM_TOP)/RPMS/$(RPM_ARCH)/$(RPM_FILE) .
	@echo "Built $(RPM_FILE). Deploy with: orphera deploy-agent --agent agent-go/$(RPM_FILE) --nodes <nodes>"

clean:
	rm -rf bin pkg orphera-agent_*.deb orphera-agent-*.rpm
