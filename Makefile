# SPDX-License-Identifier: Apache-2.0
.PHONY: all release rpm-if-available go-agent-if-available bump-patch _build_all clean test assembly pkg-stage deb rpm verify certs certs-clean fmt fmt-manifests scripting lint fix cli-stage cli-deb cli-rpm cli-tar cli-package check-package install uninstall check-ilo check-os10 native-utils vmctl move-vm start-vm stop-vm reboot-vm list-vms galera-cold-start scan-vm-sizes check-native-utils check-vmctl check-move-vm check-vm-scripts check-galera-cold-start check-scan-vm-sizes gh-check

VERSION_FILE := VERSION
VERSION      := $(shell cat $(VERSION_FILE))
ARCH         := amd64
PKG_NAME     := orphera-agent
PKG_DIR      := pkg/$(PKG_NAME)
DEB_FILE     := $(PKG_NAME)_$(VERSION)_$(ARCH).deb
RPM_FILE     := $(PKG_NAME)-$(VERSION)-1.noarch.rpm
RPM_TOP      := $(CURDIR)/pkg/rpmbuild
JAR_SRC      := agent/target/scala-3.8.4/agent-assembly-$(VERSION).jar
CERTS_DIR    := $(or $(ORPHERA_CERTS_DIR),certs)
CLI_JAR      := /usr/local/lib/orphera-orchestrator.jar
SCRIPTING_JAR := /usr/local/lib/orphera-scripting.jar
CLI_BIN      := /usr/local/bin/orphera
# The utils/ scripts that are commands of their own (vmctl, ...). `make orpheracli` puts them in UTIL_BIN;
# the packages and `make install` put them in bin/ beside orphera.
UTIL_BIN     := /opt/orphera/bin
UTILITIES    := $(shell grep -v '^[[:space:]]*\#' packaging/cli/utilities | grep -v '^[[:space:]]*$$')
CLI_COMPLETION := /etc/bash_completion.d/orphera

all: clean test assembly deb rpm

release: bump-patch
	$(MAKE) _build_all

_build_all: clean test assembly deb rpm-if-available go-agent-if-available orpheracli scripting cli-package
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

# Counts the source in the repository: files git tracks, plus new ones not yet added, and never what .gitignore
# excludes (the package staging trees pkg/, pkg-cli/ and pkg-cli-rpm/ hold copies of the manifests, and build output
# is elsewhere). The protobuf-generated Go in internal/pb/ is left out. Prints one table of the Scala, Go, Python and shell
# source, by part: raw lines and lines without comments and blanks (utils/count-lines.py).
lines:
	git ls-files -co --exclude-standard | grep -v '/internal/pb/' | python3 utils/count-lines.py

deploy-agents:
	sbt "orchestrator/runMain orphera.orchestrator.Main deploy-agent orphera-agent_$(VERSION)_amd64.deb"

check-versions:
	sbt "orchestrator/runMain orphera.orchestrator.Main version"

check-uptimes:
	sbt "orchestrator/runMain orphera.orchestrator.Main uptime"

# Reboots every node in the inventory, hypervisors included, so it refuses to
# run unless ORPHERA_ALLOW_DESTRUCTIVE=1. To reboot some nodes: orphera reboot --nodes ...
# Checks inventory.yaml and config/*.yaml against schemas/ (needs python3-jsonschema and python3-yaml).
check-schemas:
	utils/validate-schemas.py

# Loads the real inventory and configs into a SQLite copy of the database tables with utils/yaml-to-sql.py, reads them
# back with utils/sql-to-yaml.py and checks they are equal (needs python3-yaml and python3-jsonschema; no database server).
check-roundtrip:
	utils/test-sql-roundtrip.py

# Checks that the shell scripts read the inventory and VM configs from ORPHERA_INVENTORY and ORPHERA_CONFIG_DIR (no lab needed).
check-scripts:
	utils/test-shell-sources.sh

# Tests utils/ilo.py against a mock iLO on localhost (no iLO and no lab needed).
check-ilo:
	python3 utils/test-ilo.py

# Tests utils/os10.py against a mock Dell OS10 RESTCONF server on localhost (no switch and no lab needed).
check-os10:
	python3 utils/test-os10.py

# Scripts of utils/ in Scala, compiled to native executables with Scala Native: a build of its own in utils-native/
# (it needs sbt and clang to build, and no JVM to run). Each is installed in utils/ beside its shell version, which
# stays, named as it without .sh. `make native-utils` builds them all in one sbt run; each also has its own target.
NATIVE_UTILS := vmctl:vmctl moveVm:move-vm startVm:start-vm stopVm:stop-vm rebootVm:reboot-vm listVms:list-vms galeraColdStart:galera-cold-start scanVmSizes:scan-vm-sizes

native-utils:
	utils/build-native.sh $(NATIVE_UTILS)

vmctl move-vm start-vm stop-vm reboot-vm list-vms galera-cold-start scan-vm-sizes:
	utils/build-native.sh $(filter %:$@,$(NATIVE_UTILS))

# Each native executable against its shell version (no lab needed; see the scripts for what they compare).
check-native-utils: check-vmctl check-move-vm check-vm-scripts check-galera-cold-start check-scan-vm-sizes

# Runs utils/vmctl (make vmctl) and utils/vmctl.sh with the same arguments against a stand-in orphera and
# checks they print the same and exit the same (no lab needed).
check-vmctl:
	utils/test-vmctl.sh

# Runs utils/move-vm (make move-vm) and utils/move-vm.sh with the same arguments against a stand-in orphera and
# checks they print the same, exit the same, send the same scripts to the hypervisors and leave the same config
# file (no lab needed; about a minute, for the waits it includes).
check-move-vm:
	utils/test-move-vm.sh

# start-vm, stop-vm, reboot-vm and list-vms (make native-utils) against their shell versions, with a stand-in orphera.
check-vm-scripts:
	utils/test-vm-scripts.sh

# galera-cold-start against galera-cold-start.sh, with a stand-in orphera playing tst3, tst4 and tst5.
check-galera-cold-start:
	utils/test-galera-cold-start.sh

# scan-vm-sizes against scan-vm-sizes.sh, with a stand-in orphera playing the hypervisors and guests.
check-scan-vm-sizes:
	utils/test-scan-vm-sizes.sh

# Fails if a tracked source file has no SPDX-License-Identifier header and is not listed in utils/spdx-exempt.txt.
# The latest CI run on GitHub (needs gh, logged in: gh auth login).
gh-check:
	gh run list --limit 1

check-spdx:
	utils/spdx.py --check

# Fails if a tracked file holds a lab password, a quoted cephx key or a private key.
check-secrets:
	utils/check-no-secrets.sh

reboot-all:
	@test "$$ORPHERA_ALLOW_DESTRUCTIVE" = "1" || { \
	  echo "make reboot-all reboots EVERY node in the inventory, hypervisors included." >&2; \
	  echo "Run it with ORPHERA_ALLOW_DESTRUCTIVE=1 if that is what you want." >&2; exit 1; }
	sbt "orchestrator/runMain orphera.orchestrator.Main reboot"

# Installs the orchestrator under one fixed name, so `orphera` always runs the
# jar built last (not whichever versioned file in /usr/local/lib sorts highest),
# and installs the launcher with it. Copy-then-rename, so a run that starts
# mid-install never sees a half-written jar.
# Installs the CLI and the jar that compiles and runs .scala playbooks (Installation.scala
# looks for it at $(SCRIPTING_JAR)), so neither needs a build tree.
# Also on macOS (zsh, no /etc/profile.d, no bash completion directory, and /usr/local/lib may not exist): the
# directories are created first, `install -D` (not in the BSD install) is not used, and the PATH entry for
# $(UTIL_BIN) goes in /etc/paths.d instead of /etc/profile.d.
orpheracli:
	sbt orchestrator/assembly scripting/assembly
	sudo install -d -m 755 $(dir $(CLI_JAR)) $(dir $(SCRIPTING_JAR)) $(dir $(CLI_BIN))
	sudo cp orchestrator/target/scala-3.8.4/orchestrator-assembly-$(VERSION).jar $(CLI_JAR).new
	sudo mv $(CLI_JAR).new $(CLI_JAR)
	sudo cp scripting/target/scala-3.8.4/scripting-assembly-$(VERSION).jar $(SCRIPTING_JAR).new
	sudo mv $(SCRIPTING_JAR).new $(SCRIPTING_JAR)
	sudo install -m 755 utils/orphera $(CLI_BIN)
	sudo install -d -m 755 $(UTIL_BIN)
	for u in $(UTILITIES); do sudo install -m 755 utils/orphera-utility $(UTIL_BIN)/$$u; done
	@if [ "$$(uname -s)" = Darwin ]; then \
	  echo "$(UTIL_BIN)" | sudo tee /etc/paths.d/orphera >/dev/null; \
	else \
	  sudo install -d -m 755 /etc/profile.d $(dir $(CLI_COMPLETION)); \
	  sudo install -m 644 packaging/cli/orphera-utils-profile.sh /etc/profile.d/orphera-utils.sh; \
	  sudo install -m 644 orphera-completion.bash $(CLI_COMPLETION); \
	fi
	@echo "Installed orchestrator $(VERSION) as $(CLI_JAR) and the scripting jar as $(SCRIPTING_JAR), and $(UTILITIES) in $(UTIL_BIN) (on the PATH of new login shells); tab completion applies to new bash shells on Linux"

scripting:
	sbt scripting/assembly

sleep30:
	sleep 30

# The orphera command-line package (standard directories under /usr: bin, lib/orphera, share/orphera,
# share/doc/orphera; see packaging/cli/stage.sh): deb, rpm and tarball. Separate from the
# agent package above; staged in $(CLI_STAGE) so the two never touch each other.
CLI_STAGE := pkg-cli
CLI_DEB := orphera_$(VERSION)_all.deb
CLI_RPM := orphera-$(VERSION)-1.noarch.rpm
CLI_TAR := orphera-$(VERSION).tar.gz

cli-stage:
	sbt orchestrator/assembly scripting/assembly
	rm -rf $(CLI_STAGE)
	packaging/cli/stage.sh $(CLI_STAGE) $(VERSION) \
	  orchestrator/target/scala-3.8.4/orchestrator-assembly-$(VERSION).jar \
	  scripting/target/scala-3.8.4/scripting-assembly-$(VERSION).jar

cli-deb: cli-stage
	dpkg-deb --build --root-owner-group $(CLI_STAGE) $(CLI_DEB)
	@echo "Built $(CLI_DEB)"

cli-rpm: cli-stage
	rpmbuild -bb packaging/cli/orphera.spec.template \
	  --define "_topdir $(CURDIR)/$(CLI_STAGE)-rpm" --define "version $(VERSION)" \
	  --define "stagedir $(CURDIR)/$(CLI_STAGE)"
	cp $(CLI_STAGE)-rpm/RPMS/noarch/$(CLI_RPM) .
	@echo "Built $(CLI_RPM)"

# Unpacks as orphera-<version>/ (bin, lib/orphera, share/orphera, ...). Set ORPHERA_SITE_DIR to run it without root.
cli-tar: cli-stage
	tar --owner=0 --group=0 --transform 's,^,orphera-$(VERSION)/,' -C $(CLI_STAGE)/usr -czf $(CLI_TAR) bin lib share
	@echo "Built $(CLI_TAR)"

# Installs the command line from the jars already built (it does not build: run `make cli-stage` or
# `sbt orchestrator/assembly scripting/assembly` first, as yourself), in the same layout as the packages:
#   sudo make install                          # PREFIX=/usr/local
#   make install PREFIX=$$HOME/.local          # no root; /etc/orphera is then not created
#   make install DESTDIR=/tmp/root PREFIX=/usr # staged, for a distribution's own packaging
# It refuses to replace a checkout launcher that `make orpheracli` put at the same place.
PREFIX ?= /usr/local
CLI_ORCH_JAR := orchestrator/target/scala-3.8.4/orchestrator-assembly-$(VERSION).jar
CLI_SCRIPTING_JAR := scripting/target/scala-3.8.4/scripting-assembly-$(VERSION).jar

install:
	@[ -f $(CLI_ORCH_JAR) ] && [ -f $(CLI_SCRIPTING_JAR) ] || { echo "the $(VERSION) jars are not built: run 'make cli-stage' (not as root) first" >&2; exit 1; }
	@if grep -qs 'Runs the orchestrator directly from its assembled jar' "$(DESTDIR)$(PREFIX)/bin/orphera"; then 	  echo "$(DESTDIR)$(PREFIX)/bin/orphera is the checkout launcher from 'make orpheracli': choose another PREFIX, or remove it first" >&2; exit 1; fi
	@tmp=$$(mktemp -d) && trap 'rm -rf "$$tmp"' EXIT && 	  packaging/cli/stage.sh --prefix $(PREFIX) --no-deb "$$tmp/s" $(VERSION) $(CLI_ORCH_JAR) $(CLI_SCRIPTING_JAR) >/dev/null && 	  rm -rf "$(DESTDIR)$(PREFIX)/lib/orphera" "$(DESTDIR)$(PREFIX)/share/orphera" "$(DESTDIR)$(PREFIX)/share/doc/orphera" && 	  mkdir -p "$(DESTDIR)$(PREFIX)" && cp -R "$$tmp/s$(PREFIX)/." "$(DESTDIR)$(PREFIX)/" && 	  if mkdir -p "$(DESTDIR)/etc/orphera/config" "$(DESTDIR)/etc/orphera/certs" 2>/dev/null; then :; 	  else echo "note: could not create $(DESTDIR)/etc/orphera (no matter for a per-user install)"; fi
	@echo "Installed orphera $(VERSION) under $(DESTDIR)$(PREFIX)"

# Removes what `make install` put under PREFIX. Never touches /etc/orphera or anyone's ~/.config/orphera.
uninstall:
	rm -f "$(DESTDIR)$(PREFIX)/bin/orphera" "$(DESTDIR)$(PREFIX)/share/bash-completion/completions/orphera"
	for u in $(UTILITIES); do rm -f "$(DESTDIR)$(PREFIX)/bin/$$u"; done
	rm -rf "$(DESTDIR)$(PREFIX)/lib/orphera" "$(DESTDIR)$(PREFIX)/share/orphera" "$(DESTDIR)$(PREFIX)/share/doc/orphera"

cli-package: cli-deb cli-tar
	@if command -v rpmbuild >/dev/null 2>&1; then $(MAKE) cli-rpm; else echo "rpmbuild not installed: skipping $(CLI_RPM)"; fi

# No build needed: stages the package with stand-in jars and checks layout, launcher and scripts.
check-package:
	utils/test-cli-package.sh

clean:
	sbt clean
	rm -rf pkg $(DEB_FILE) $(CLI_STAGE) $(CLI_STAGE)-rpm
	rm -f orphera_*_all.deb orphera-*.noarch.rpm orphera-*.tar.gz
	rm -f orphera-agent_*_amd64.deb orphera-agent-*.rpm

test:
	sbt test "orchestrator/Test/runMain orphera.orchestrator.kolla.ContainerCheck" "orchestrator/Test/runMain orphera.orchestrator.conf.ConfCheck" "orchestrator/Test/runMain orphera.orchestrator.SecretsCheck" "orchestrator/Test/runMain orphera.orchestrator.ThreadInfoCheck" "orchestrator/Test/runMain orphera.orchestrator.AuditLogCheck" "orchestrator/Test/runMain orphera.orchestrator.InventoryCheck" "orchestrator/Test/runMain orphera.orchestrator.LocationsCheck" "orchestrator/Test/runMain orphera.orchestrator.InstallationCheck" "orchestrator/Test/runMain orphera.orchestrator.CaInitCheck" "orchestrator/Test/runMain orphera.orchestrator.DbDocumentsCheck" "orchestrator/Test/runMain orphera.orchestrator.Os10Check" "orchestrator/Test/runMain orphera.orchestrator.IloCheck" "agent/Test/runMain orphera.agent.AgentGuardCheck"

assembly:
	sbt agent/assembly
	@test -f $(JAR_SRC) || (echo "ERROR: expected jar not found at $(JAR_SRC)" && exit 1)

# Starts from an empty staging tree, so files dropped from the package (such
# as the old wildcard server.key) cannot be carried over from an earlier build.
pkg-stage: assembly
	rm -rf $(PKG_DIR)
	mkdir -p $(PKG_DIR)/DEBIAN
	mkdir -p $(PKG_DIR)/opt/orphera-agent
	mkdir -p $(PKG_DIR)/etc/orphera-agent
	mkdir -p $(PKG_DIR)/etc/systemd/system
	mkdir -p $(PKG_DIR)/var/lib/orphera/network-backups

	cp $(JAR_SRC) $(PKG_DIR)/opt/orphera-agent/orphera-agent.jar
	cp packaging/run.sh $(PKG_DIR)/opt/orphera-agent/run.sh
	chmod +x $(PKG_DIR)/opt/orphera-agent/run.sh

	cp packaging/agent.env $(PKG_DIR)/etc/orphera-agent/agent.env
	cp packaging/orphera-agent.service $(PKG_DIR)/etc/systemd/system/orphera-agent.service

	# No certificates: the agent never reads the CA (only the orchestrator verifies it), and each
	# node's own key and certificate are issued over SSH into /etc/orphera-agent/tls by bootstrap /
	# issue-certs. So this package is the same for every site, and building it needs no CA.

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

# The Go agent is the default for deploy-agent and bootstrap, so a release builds
# its packages too (same version plus +go). A host without Go skips them, with a
# warning: deploy-agent would then find only a package of an older release.
go-agent-if-available:
	@if command -v go >/dev/null; then $(MAKE) -C agent-go deb && { if command -v rpmbuild >/dev/null; then $(MAKE) -C agent-go rpm; fi; }; else echo "WARNING: go not found: the Go agent packages were not built; deploy-agent will use older ones or fail"; fi

rpm-if-available:
	@if command -v rpmbuild >/dev/null; then $(MAKE) rpm; else echo "rpmbuild not found: skipping the .rpm"; fi

verify: deb
	dpkg-deb --info $(DEB_FILE)
	dpkg-deb --contents $(DEB_FILE)
