#!/bin/sh
#
# Copyright 2015 the original author or authors.
# Licensed under the Apache License, Version 2.0
#

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
APP_HOME="`pwd -P`"

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
MAX_FD="maximum"

warn() {
    echo "$*"
}
die() {
    echo
    echo "$*"
    echo
    exit 1
}

# Locate java
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found."
fi

# Increase file descriptor limit
MAX_FD_LIMIT=`ulimit -H -n 2>/dev/null`
if [ "$?" -eq "0" ]; then
    if [ "$MAX_FD" = "maximum" -o "$MAX_FD" = "max" ]; then
        MAX_FD="$MAX_FD_LIMIT"
    fi
    ulimit -n $MAX_FD
fi

# Collect classpath
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the OS for path normalization
case "`uname`" in
    CYGWIN* )
        CLASSPATH=`cygpath --path --mixed "$CLASSPATH"`
        ;;
    MINGW* )
        CLASSPATH=`cygpath --path --mixed "$CLASSPATH"`
        ;;
esac

exec "$JAVACMD" \
  $DEFAULT_JVM_OPTS \
  $JAVA_OPTS \
  $GRADLE_OPTS \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
