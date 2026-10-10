#!/usr/bin/env bash
# Runs Paper with its console on a FIFO so an external process can inject commands.
#
# The `exec 9<>console.in` is the whole trick: opening a FIFO O_RDWR never blocks, and
# holding that descriptor keeps a writer attached to the pipe for the lifetime of this
# script. Java therefore sees a stdin that can block on read but never reports EOF, which
# is exactly how a real console behaves. Opening it O_WRONLY instead would deadlock, since
# the shell would block waiting for a reader that cannot start until the shell proceeds.
set -u
cd "$(dirname "$0")"

rm -f console.in
mkfifo console.in
exec 9<>console.in

exec java -Xms1G -Xmx2G -XX:+UseG1GC -jar folia.jar --nogui < console.in
