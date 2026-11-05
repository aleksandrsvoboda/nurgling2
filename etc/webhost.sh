#!/bin/sh
# Nurgling Web: play from a phone, tablet or PC browser on this network.
# Open one of the addresses printed below and enter the host PIN shown here.
# Home network only - never forward its port to the internet.
DIR=$(cd "$(dirname "$0")" && pwd) || exit 1
cd "$DIR" || exit 1
if [ -x runtime/bin/java ]; then
  J="$DIR/runtime/bin/java"
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  J="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  J=java
else
  echo "Nurgling Web could not find Java. Start Nurgling once with nurgling.sh, which installs it."
  exit 1
fi
exec "$J" --add-exports=java.desktop/sun.awt=ALL-UNNAMED -Xss8m -Xmx512m -Dnurgling.webhost.xmx=1g -cp "$DIR/hafen.jar" nurgling.web.WebHost "$@"
