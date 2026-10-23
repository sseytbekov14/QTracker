#!/usr/bin/env bash
# The levels x volumes matrix: matrix.sh <tag> "<volumes>" "<users>"   (APP_JAR / CLIENT_JAR from the environment)
TAG=$1; VOLUMES=${2:-"500 5000 20000"}; LEVELS=${3:-"10 25 50 100 200"}
cd "$(dirname "$0")/../.."
for v in $VOLUMES; do for u in $LEVELS; do
  echo "#### $TAG $v controls, $u users $(date +%H:%M:%S)"
  loadtest/scripts/run.sh $v $TAG-$v-${u}u --users=$u --warmup=60 --rampup=30 --duration=300 2>&1 | grep -E 'TOTAL|^list\.|^dash\.page|^card\.page|^move|^save|^export|^file|^create|hikari_pending|hikari_active|heap_used|live_after|gc_pause_max|signed in|lock waits|ERROR lines|Started|FAILED'
done; done
echo "#### $TAG done $(date +%H:%M:%S)"
