.PHONY: all release rpm-if-available bump-patch _build_all clean test assembly pkg-stage deb rpm verify certs certs-clean fmt fmt-manifests scripting lint fix

VERSION_FILE := VERSION
VERSION      := $(shell cat $(VERSION_FILE))
ARCH         := amd64
PKG_NAME     := orphera-agent
PKG_DIR      := pkg/$(PKG_NAME)
DEB_FILE     := $(PKG_NAME)_$(VERSION)_$(ARCH).deb
RPM_FILE     := $(PKG_NAME)-$(VERSION)-1.noarch.rpm
RPM_TOP      := $(CURDIR)/pkg/rpmbuild
JAR_SRC      := agent/target/scala-3.8.4/agent-assembly-$(VERSION).jar
CERTS_DIR    := certs
CLI_JAR      := /usr/local/lib/orphera-orchestrator.jar
CLI_BIN      := /usr/local/bin/orphera
CLI_COMPLETION := /etc/bash_completion.d/orphera

all: clean test assembly deb rpm

release: bump-patch
	$(MAKE) _build_all

_build_all: clean test assembly deb rpm-if-available orpheracli scripting
	@echo "Released $$(cat $(VERSION_FILE))"

bump-patch:
	@awk -F. '{printf "%d.%d.%d", $$1, $$2, $$3+1}' $(VERSION_FILE) > $(VERSION_FILE).tmp
	@mv $(VERSION_FILE).tmp $(VERSION_FILE)
	@echo "Version bumped: $$(cat $(VERSION_FILE))"

# A new CA, which signs each node's own certificate (orphera issue-certs).
# Refuses if a CA already exists: replacing it means every node needs a new
# certificate (certs-clean, then make certs, then orphera issue-certs).
certs:
	@test ! -f $(CERTS_DIR)/ca.crt || (echo "$(CERTS_DIR)/ca.crt already exists. To renew node certificates: orphera issue-certs. To start again: make certs-clean" && exit 1)
	mkdir -p $(CERTS_DIR)
	openssl req -x509 -newkey rsa:4096 -nodes -days 3650 -subj "/CN=Orphera Dev CA" \
	  -keyout $(CERTS_DIR)/ca.key -out $(CERTS_DIR)/ca.crt 2>/dev/null
	chmod 600 $(CERTS_DIR)/ca.key
	@echo "Made $(CERTS_DIR)/ca.crt. Issue node certificates with: orphera issue-certs --ssh-user <user>"

certs-clean:
	@echo "This deletes the CA key and certificate in $(CERTS_DIR)/."
	@echo "Every node will stop being trusted until it is issued a certificate by a new CA (make certs, then orphera issue-certs)."
	@printf "Type 'delete' to continue: "; read ans; [ "$$ans" = delete ] || (echo "Cancelled." && exit 1)
	rm -f $(CERTS_DIR)/ca.crt $(CERTS_DIR)/ca.key $(CERTS_DIR)/ca.srl \
	  $(CERTS_DIR)/server.crt $(CERTS_DIR)/server.key $(CERTS_DIR)/server.csr

lint:
	sbt "scalafixAll --check"

# scalafix first: OrganizeImports rewrites import blocks, scalafmt has the last word.
fix:
	sbt scalafixAll
	$(MAKE) fmt

fmt:
	sbt scalafmt
	$(MAKE) fmt-manifests

# manifests/*.scala are standalone playbook scripts, compiled at run time
# by `orphera playbook`/`cluster-playbook` — none of them are declared as
# a sourceDirectory of any project in build.sbt, so plain `sbt scalafmt`
# (which only ever touches each project's own configured Compile/Test
# sources) never reaches them, formatted or not. sbt-scalafmt's own
# `scalafmtOnly <files>` command formats arbitrary files regardless of
# whether they're part of a project's source set, which is exactly what's
# needed here — no build.sbt changes (e.g. adding manifests as an
# unmanagedSourceDirectory) that would risk pulling 30+ standalone,
# single-file-compiled playbook scripts onto one shared compile
# classpath.
#
# Real run finding #1: `sbt "scalafmtOnly manifests/*.scala"` (the glob
# quoted as part of the sbt command string) fails — bash does not expand
# a glob inside double quotes, so sbt/scalafmt received the nine literal
# characters `*.scala` and tried to open a file actually named
# `manifests/*.scala`, which doesn't exist ("Failed to read"). Fixed by
# expanding the glob with `ls` inside a `$$(...)` command substitution —
# that substitution still runs even inside the outer double quotes (only
# a bare `*` is blocked by quotes, not `$$(...)`), so sbt receives real,
# already-expanded filenames.
#
# Real run finding #2: even with the glob expanded, relative paths
# (`manifests/Foo.scala`) still failed to read in every subproject scope
# (common/agent/orchestrator/scripting) — only the root scope succeeded.
# `scalafmtOnly` is unscoped, so it runs once per subproject in the
# aggregate build, and each run resolves a relative path against THAT
# subproject's own base directory (e.g. `common/manifests/Foo.scala`),
# not the repo root where `manifests/` actually lives. Fixed by using
# `cd manifests && pwd` to build an absolute directory prefix, so every
# scope resolves the exact same unambiguous path regardless of its own
# base directory.
fmt-manifests:
	sbt "scalafmtOnly $$(dir=$$(cd manifests && pwd); ls -1 $$dir/*.scala | tr '\n' ' ')"

reload:
	sbt reload

lines:
	find . -name "*.scala" -not -path "*/target/*" -not -path "*/project/*" | xargs wc -l | tail -1

deploy-agents:
	sbt "orchestrator/runMain orphera.orchestrator.Main deploy-agent orphera-agent_$(VERSION)_amd64.deb"

check-versions:
	sbt "orchestrator/runMain orphera.orchestrator.Main version"

check-uptimes:
	sbt "orchestrator/runMain orphera.orchestrator.Main uptime"

# Reboots every node in the inventory, hypervisors included, so it refuses to
# run unless ORPHERA_ALLOW_DESTRUCTIVE=1. To reboot some nodes: orphera reboot --nodes ...
reboot-all:
	@test "$$ORPHERA_ALLOW_DESTRUCTIVE" = "1" || { \
	  echo "make reboot-all reboots EVERY node in the inventory, hypervisors included." >&2; \
	  echo "Run it with ORPHERA_ALLOW_DESTRUCTIVE=1 if that is what you want." >&2; exit 1; }
	sbt "orchestrator/runMain orphera.orchestrator.Main reboot"

# Installs the orchestrator under one fixed name, so `orphera` always runs the
# jar built last (not whichever versioned file in /usr/local/lib sorts highest),
# and installs the launcher with it. Copy-then-rename, so a run that starts
# mid-install never sees a half-written jar.
orpheracli:
	sbt orchestrator/assembly
	sudo cp orchestrator/target/scala-3.8.4/orchestrator-assembly-$(VERSION).jar $(CLI_JAR).new
	sudo mv $(CLI_JAR).new $(CLI_JAR)
	sudo install -m 755 utils/orphera $(CLI_BIN)
	sudo install -D -m 644 orphera-completion.bash $(CLI_COMPLETION)
	@echo "Installed orchestrator $(VERSION) as $(CLI_JAR); tab completion applies to new shells"

scripting:
	sbt scripting/assembly

sleep30:
	sleep 30

clean:
	sbt clean
	rm -rf pkg $(DEB_FILE)
	rm -f orphera-agent_*_amd64.deb orphera-agent-*.rpm

test:
	sbt test "orchestrator/Test/runMain orphera.orchestrator.kolla.ContainerCheck" "orchestrator/Test/runMain orphera.orchestrator.conf.ConfCheck" "orchestrator/Test/runMain orphera.orchestrator.SecretsCheck" "orchestrator/Test/runMain orphera.orchestrator.ThreadInfoCheck" "orchestrator/Test/runMain orphera.orchestrator.InventoryCheck" "orchestrator/Test/runMain orphera.orchestrator.LocationsCheck"

assembly:
	sbt agent/assembly
	@test -f $(JAR_SRC) || (echo "ERROR: expected jar not found at $(JAR_SRC)" && exit 1)

# Starts from an empty staging tree, so files dropped from the package (such
# as the old wildcard server.key) cannot be carried over from an earlier build.
pkg-stage: assembly
	rm -rf $(PKG_DIR)
	mkdir -p $(PKG_DIR)/DEBIAN
	mkdir -p $(PKG_DIR)/opt/orphera-agent
	mkdir -p $(PKG_DIR)/etc/orphera-agent/certs
	mkdir -p $(PKG_DIR)/etc/systemd/system
	mkdir -p $(PKG_DIR)/var/lib/orphera/network-backups

	cp $(JAR_SRC) $(PKG_DIR)/opt/orphera-agent/orphera-agent.jar
	cp packaging/run.sh $(PKG_DIR)/opt/orphera-agent/run.sh
	chmod +x $(PKG_DIR)/opt/orphera-agent/run.sh

	cp packaging/agent.env $(PKG_DIR)/etc/orphera-agent/agent.env
	cp packaging/orphera-agent.service $(PKG_DIR)/etc/systemd/system/orphera-agent.service

	# Only the CA certificate (public). Each node's own key and certificate are
	# issued over SSH into /etc/orphera-agent/tls by bootstrap / issue-certs.
	@test -f $(CERTS_DIR)/ca.crt || (echo "ERROR: $(CERTS_DIR)/ca.crt not found - run: make certs" && exit 1)
	cp $(CERTS_DIR)/ca.crt $(PKG_DIR)/etc/orphera-agent/certs/

	sed "s/@VERSION@/$(VERSION)/" packaging/control.template > $(PKG_DIR)/DEBIAN/control
	cp packaging/postinst $(PKG_DIR)/DEBIAN/postinst
	cp packaging/prerm $(PKG_DIR)/DEBIAN/prerm
	cp packaging/conffiles $(PKG_DIR)/DEBIAN/conffiles
	chmod +x $(PKG_DIR)/DEBIAN/postinst $(PKG_DIR)/DEBIAN/prerm

deb: pkg-stage
	dpkg-deb --build --root-owner-group $(PKG_DIR) $(DEB_FILE)
	@echo "Built $(DEB_FILE)"

# Red Hat-family (Rocky) package from the same staged tree as the .deb. Needs
# rpmbuild (on Ubuntu: sudo apt install rpm). `make release` builds it too when
# rpmbuild is installed (rpm-if-available) and skips it otherwise, so a host
# without rpmbuild still builds the .deb as before.
rpm: pkg-stage
	@command -v rpmbuild >/dev/null || (echo "ERROR: rpmbuild not found (Ubuntu: sudo apt install rpm)" && exit 1)
	rm -rf $(RPM_TOP)
	mkdir -p $(RPM_TOP)
	sed "s/@VERSION@/$(VERSION)/" packaging/orphera-agent.spec.template > $(RPM_TOP)/orphera-agent.spec
	rpmbuild -bb --define "_topdir $(RPM_TOP)" --define "stagedir $(CURDIR)/$(PKG_DIR)" --define "_binary_payload w19.zstdio" $(RPM_TOP)/orphera-agent.spec
	cp $(RPM_TOP)/RPMS/noarch/$(RPM_FILE) .
	@echo "Built $(RPM_FILE)"

rpm-if-available:
	@if command -v rpmbuild >/dev/null; then $(MAKE) rpm; else echo "rpmbuild not found: skipping the .rpm"; fi

verify: deb
	dpkg-deb --info $(DEB_FILE)
	dpkg-deb --contents $(DEB_FILE)
