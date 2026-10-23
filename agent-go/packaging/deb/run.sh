#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -e
cd /etc/orphera-agent
exec /opt/orphera-agent/orphera-agent-go
