.PHONY: all release bump-patch _build_all clean test assembly pkg-stage deb verify certs certs-clean

VERSION_FILE := VERSION
VERSION      := $(shell cat $(VERSION_FILE))
ARCH         := amd64
PKG_NAME     := orphera-agent
PKG_DIR      := pkg/$(PKG_NAME)
DEB_FILE     := $(PKG_NAME)_$(VERSION)_$(ARCH).deb
JAR_SRC      := agent/target/scala-3.8.4/agent-assembly-$(VERSION).jar
CERTS_DIR    := certs

all: clean test assembly deb

release: bump-patch
	$(MAKE) _build_all

_build_all: clean test assembly deb
	@echo "Released $$(cat $(VERSION_FILE))"

bump-patch:
	@awk -F. '{printf "%d.%d.%d", $$1, $$2, $$3+1}' $(VERSION_FILE) > $(VERSION_FILE).tmp
	@mv $(VERSION_FILE).tmp $(VERSION_FILE)
	@echo "Version bumped: $$(cat $(VERSION_FILE))"

fmt:
	sbt scalafmt

reload:
	sbt reload

clean:
	sbt clean
	rm -rf pkg $(DEB_FILE)
	rm -f orphera-agent_*_amd64.deb

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

verify: deb
	dpkg-deb --info $(DEB_FILE)
	dpkg-deb --contents $(DEB_FILE)
