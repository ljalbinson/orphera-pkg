#!/bin/sh
set -e
cd /etc/orphera-agent
exec java -cp /opt/orphera-agent/orphera-agent.jar orphera.agent.Main
