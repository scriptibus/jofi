#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Jofi contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Container healthcheck: exits 0 when Spring Boot's /actuator/health reports UP.
# Uses bash's /dev/tcp so the runtime image needs no curl or wget (smaller attack surface).
set -euo pipefail

port="${SERVER_PORT:-8080}"
exec 3<>"/dev/tcp/127.0.0.1/${port}"
printf 'GET /actuator/health HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
response="$(cat <&3)"
exec 3<&-

[[ "${response}" == "HTTP/1."?" 200"* && "${response}" == *'"status":"UP"'* ]]
