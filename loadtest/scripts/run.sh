#!/usr/bin/env bash
# From the repository root in Git Bash, after: docker run (PostgreSQL 18.4 on 127.0.0.1:5433, see the report),
#   mvnw -Ploadtest -DskipTests package  and  mvn -f loadtest/pom.xml package
# One load test run: run.sh <controls> <name> [client args...]
# Stops the loadtest app, regenerates <controls> controls, starts the app, resets pg_stat_statements, runs the
# client, saves the top statements and lock waits, stops the app.
set -u
cd "$(dirname "$0")/../.."
CONTROLS=$1; NAME=$2; shift 2
RES=target-loadtest/results
JAVA_APP_OPTS=${JAVA_APP_OPTS:--Xms256m -Xmx768m}
PSQL="docker exec qtracker-loadtest-pg psql -U postgres -d qtracker_loadtest -X"

# Server-side time per route (Actuator http.server.requests): count, total and max seconds, before the app stops
server_times() {
  local cj; cj=$(mktemp); local t
  t=$(curl -s -c $cj -b $cj http://localhost:8092/login | grep -o 'name="_csrf" value="[^"]*"' | sed 's/.*value="//;s/"//')
  curl -s -c $cj -b $cj -o /dev/null --data-urlencode "username=lt.soqm.008@loadtest.local" --data-urlencode "password=LoadTest-2026" --data-urlencode "_csrf=$t" http://localhost:8092/login
  echo "uri,count,total_s,mean_ms,max_s" > $RES/$NAME-server-times.csv
  for uri in $(curl -s -b $cj "http://localhost:8092/actuator/metrics/http.server.requests" | grep -o '"tag":"uri","values":\[[^]]*\]' | sed 's/.*\[//;s/\]//;s/"//g' | tr ',' ' '); do
    case $uri in /actuator*|UNKNOWN|/js*|/css*|/favicon*|root) continue;; esac
    curl -s -b $cj "http://localhost:8092/actuator/metrics/http.server.requests?tag=uri:$(printf %s "$uri" | sed 's/{/%7B/g;s/}/%7D/g')" \
      | awk -v u="$uri" '{ c=$0; match(c, /"COUNT","value":[0-9.E]+/); n=substr(c, RSTART+16, RLENGTH-16); match(c, /"TOTAL_TIME","value":[0-9.E]+/); t=substr(c, RSTART+21, RLENGTH-21); match(c, /"MAX","value":[0-9.E]+/); m=substr(c, RSTART+14, RLENGTH-14); if (n>0) printf "%s,%d,%.1f,%.1f,%.2f\n", u, n, t, t/n*1000, m }' >> $RES/$NAME-server-times.csv
  done
  rm -f $cj
  sort -t, -k3 -rn $RES/$NAME-server-times.csv | head -12
}

stop_app() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'qtracker-loadtest[.]jar|jars.app-' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
  sleep 2
}

stop_app
echo "== generate $CONTROLS"
SPRING_PROFILES_ACTIVE=prod,loadtest java -Xmx1g -jar ${APP_JAR:-target-loadtest/qtracker-loadtest.jar} --server.port=0 \
  --notifications.scheduler.enabled=false --notifications.dispatcher.enabled=false \
  --loadtest.generate.controls=$CONTROLS > target-loadtest/logs/gen-$NAME.log 2>&1
grep -E 'Load test data done' target-loadtest/logs/gen-$NAME.log | sed 's/.*: Load/Load/'
$PSQL -q -c "VACUUM ANALYZE" >/dev/null

echo "== start app ($JAVA_APP_OPTS)"
JFR_OPTS=""; [ -n "${JFR:-}" ] && JFR_OPTS="-XX:StartFlightRecording=filename=target-loadtest/logs/$NAME.jfr,settings=profile,delay=90s,duration=120s"
SPRING_PROFILES_ACTIVE=prod,loadtest nohup java $JAVA_APP_OPTS $JFR_OPTS -Xlog:gc:file=target-loadtest/logs/gc-$NAME.log \
  -jar ${APP_JAR:-target-loadtest/qtracker-loadtest.jar} > target-loadtest/logs/app-$NAME.log 2>&1 &
for i in $(seq 1 120); do grep -q -E 'Started Qtracker|APPLICATION FAILED' target-loadtest/logs/app-$NAME.log && break; sleep 1; done
grep -E 'Started Qtracker|APPLICATION FAILED' target-loadtest/logs/app-$NAME.log | sed 's/.*: //'

$PSQL -q -c "SELECT pg_stat_statements_reset()" >/dev/null
SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
java -jar ${CLIENT_JAR:-loadtest/target/qtracker-loadtest-client.jar} --name=$NAME "$@" 2>&1 | tee $RES/$NAME-console.txt | grep -v '^\[' | tail -60

$PSQL -c "SELECT calls, round(total_exec_time::numeric) AS total_ms, round(mean_exec_time::numeric,2) AS mean_ms, round(max_exec_time::numeric,1) AS max_ms, rows, shared_blks_hit + shared_blks_read AS blks, left(regexp_replace(query, '\s+', ' ', 'g'), 220) AS query FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) ORDER BY total_exec_time DESC LIMIT 20" > $RES/$NAME-pgss-time.txt
$PSQL -c "SELECT calls, round(total_exec_time::numeric) AS total_ms, round(mean_exec_time::numeric,2) AS mean_ms, left(regexp_replace(query, '\s+', ' ', 'g'), 220) AS query FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) ORDER BY calls DESC LIMIT 20" > $RES/$NAME-pgss-calls.txt
$PSQL -c "SELECT relname, seq_scan, seq_tup_read, idx_scan, n_live_tup FROM pg_stat_user_tables ORDER BY seq_tup_read DESC LIMIT 12" > $RES/$NAME-tables.txt
docker logs --since $SINCE qtracker-loadtest-pg 2>&1 | grep -E 'still waiting|deadlock|acquired' > $RES/$NAME-locks.txt
echo "lock waits logged: $(grep -c 'still waiting' $RES/$NAME-locks.txt), deadlocks: $(grep -c deadlock $RES/$NAME-locks.txt)"
echo "app ERROR lines: $(grep -c ' ERROR ' target-loadtest/logs/app-$NAME.log), OOM: $(grep -c OutOfMemoryError target-loadtest/logs/app-$NAME.log)"
server_times
stop_app
