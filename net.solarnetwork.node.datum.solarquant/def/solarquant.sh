#!/usr/bin/env bash
#
# SolarQuant Docker container management script.
#
# Usage: solarquant <command> [args...]
#
# Commands:
#   start <image> <name>   Pull image and run container; prints host port to stdout.
#   stop  <name>           Stop and remove container.
#   status <name>          Print "running <port>" or "stopped" to stdout.
#
# All diagnostic output goes to stderr. Only machine-readable values go to
# stdout so the calling Java process can parse them reliably.
#
# Exit codes:
#   0  success
#   1  usage / argument error
#   2  docker command failed

set -euo pipefail

CONTAINER_PORT=8000
STOP_TIMEOUT=10

die() {
	echo "ERROR: $*" >&2
	exit 1
}

info() {
	echo "$*" >&2
}

do_start() {
	local image="${1:?image required}"
	local name="${2:?name required}"

	# Remove any leftover container with the same name (idempotent restart)
	if docker inspect "$name" >/dev/null 2>&1; then
		info "Removing existing container $name"
		docker stop "$name" --time "$STOP_TIMEOUT" >/dev/null 2>&1 || true
		docker rm -f "$name" >/dev/null 2>&1 || true
	fi

	info "Pulling image $image"
	if ! docker pull "$image" >&2; then
		die "Failed to pull image $image"
	fi

	info "Starting container $name from $image"
	if ! docker run -d --name "$name" -p "0:${CONTAINER_PORT}" "$image" >/dev/null; then
		die "Failed to start container $name"
	fi

	# Read the allocated host port
	local port_line
	port_line=$(docker port "$name" "$CONTAINER_PORT" 2>/dev/null) \
		|| die "Failed to read port mapping for $name"

	local port
	port="${port_line##*:}"

	if [[ -z "$port" ]]; then
		die "Could not parse host port from: $port_line"
	fi

	info "Container $name started on host port $port"
	echo "$port"
}

do_stop() {
	local name="${1:?name required}"

	if docker inspect "$name" >/dev/null 2>&1; then
		info "Stopping container $name"
		docker stop "$name" --time "$STOP_TIMEOUT" >/dev/null 2>&1 || true
		docker rm -f "$name" >/dev/null 2>&1 || true
		info "Container $name removed"
	else
		info "Container $name not found; nothing to stop"
	fi
}

do_status() {
	local name="${1:?name required}"

	local running
	running=$(docker inspect --format='{{.State.Running}}' "$name" 2>/dev/null) || running="false"

	if [[ "$running" == "true" ]]; then
		local port_line
		port_line=$(docker port "$name" "$CONTAINER_PORT" 2>/dev/null) || port_line=""
		local port="${port_line##*:}"
		echo "running ${port:-unknown}"
	else
		echo "stopped"
	fi
}

cmd="${1:-}"
shift || true

case "$cmd" in
	start)  do_start "$@" ;;
	stop)   do_stop "$@" ;;
	status) do_status "$@" ;;
	*)
		echo "Usage: solarquant {start|stop|status} [args...]" >&2
		exit 1
		;;
esac
