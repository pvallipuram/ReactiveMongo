JVM_MAX_MEM="1G"

JAVA_MAJOR=`java -version 2>&1 | head -n 1 | cut -d '"' -f 2 | sed -e 's/\.[0-9a-zA-Z_]*$//'`

if [ "v$JAVA_MAJOR" = "v10.0" -o "v$JAVA_MAJOR" = "v11.0" ]; then
  JVM_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70"
else
  JVM_MAX_MEM="1632M" # 1760M"
  JVM_OPTS="-Xmx$JVM_MAX_MEM -XX:ReservedCodeCacheSize=192m"
fi

#JVM_OPTS="-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap"

if [ ! `uname` = "Darwin" ]; then
  export _JAVA_OPTIONS="$JVM_OPTS"
fi
