#!/usr/bin/env bash
# One user, no load: the average time of each page and call, after a warm-up, with the app already started on
# :8092. probe.sh <name>  ->  target-loadtest/results/<name>-probe.csv
cd "$(dirname "$0")/../.."
NAME=$1; OUT=target-loadtest/results/$NAME-probe.csv
CJ=$(mktemp)
login() {
  rm -f $CJ
  local t; t=$(curl -s -c $CJ -b $CJ http://localhost:8092/login | grep -o 'name="_csrf" value="[^"]*"' | sed 's/.*value="//;s/"//')
  curl -s -c $CJ -b $CJ -o /dev/null --data-urlencode "username=$1" --data-urlencode "password=LoadTest-2026" \
    --data-urlencode "_csrf=$t" http://localhost:8092/login
}
avg() {
  for w in $(seq 1 20); do curl -s -o /dev/null -b $CJ "http://localhost:8092$2"; done
  local tot=0 ms
  for k in $(seq 1 15); do
    ms=$(curl -s -o /dev/null -b $CJ -w '%{time_total}' "http://localhost:8092$2"); tot=$(awk -v a=$tot -v b=$ms 'BEGIN{print a+b}')
  done
  awk -v u="$1" -v p="$2" -v t=$tot 'BEGIN{printf "%s,%s,%.0f\n", u, p, t/15*1000}' | tee -a $OUT
}
echo "user,request,avg_ms" > $OUT
login lt.soqm.001@loadtest.local
for p in / /component/ALL "/component/ALL?sort=owner&page=5" /component/HR /kdn /api/dashboard/admin/frequency \
    "/api/dashboard/deadline-countdown?days=3&limit=10" /view-control/100 /api/controls/100/changelog; do avg soqm "$p"; done
login lt.edit.001@loadtest.local
OWN=$(awk -F, 'NR>1 && $3!="DRAFT" && index($7, "lt.edit.001@") {print $1; exit}' target-loadtest/data/controls.csv)
for p in / /component/ALL /api/dashboard/my/frequency /api/dashboard/my/component /view-control/$OWN; do avg edit "$p"; done
login lt.kdn.001@loadtest.local
KDN=$(awk -F, 'NR>1 && $5=="true" && $3!="DRAFT" {print $1; exit}' target-loadtest/data/controls.csv)
for p in /kdn /view-control/$KDN; do avg kdn "$p"; done
rm -f $CJ
