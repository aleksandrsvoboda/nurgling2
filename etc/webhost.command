#!/bin/sh
# Double-click in Finder to start Nurgling Web; keep the Terminal window open while playing.
cd "$(dirname "$0")" && exec /bin/sh ./webhost.sh "$@"
