.PHONY: all release rpm-if-available bump-patch _build_all clean test assembly pkg-stage deb rpm verify certs certs-clean fmt fmt-manifests scripting

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

all: clean test assembly deb

release: bump-patch
	$(MAKE) _build_all

_build_all: clean test assembly deb rpm-if-available orpheracli scripting
	@echo "Released $$(cat $(VERSION_FILE))"

bump-patch:
	@awk -F. '{printf "%d.%d.%d", $$1, $$2, $$3+1}' $(VERSION_FILE) > $(VERSION_FILE).tmp
	@mv $(VERSION_FILE).tmp $(VERSION_FILE)
	@echo "Version bumped: $$(cat $(VERSION_FILE))"

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

reboot-all:
	sbt "orchestrator/runMain orphera.orchestrator.Main reboot"

orpheracli:
	sbt orchestrator/assembly
	sudo cp -p orchestrator/target/scala-3.8.4/orchestrator-assembly-$(VERSION).jar /usr/local/lib

scripting:
	sbt scripting/assembly

sleep30:
	sleep 30

clean:
	sbt clean
	rm -rf pkg $(DEB_FILE)
	rm -f orphera-agent_*_amd64.deb orphera-agent-*.rpm

test:
	sbt test

assembly:
	sbt agent/assembly
	@test -f $(JAR_SRC) || (echo "ERROR: expected jar not found at $(JAR_SRC)" && exit 1)

pkg-stage: assembly
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

	@test -f $(CERTS_DIR)/server.crt || (echo "ERROR: $(CERTS_DIR)/server.crt not found - generate certs first" && exit 1)
	cp $(CERTS_DIR)/server.crt $(CERTS_DIR)/server.key $(CERTS_DIR)/ca.crt $(PKG_DIR)/etc/orphera-agent/certs/

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
