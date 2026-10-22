#!/usr/bin/env bash
# Markdown tables of the matrix: summary.sh <tag> ["<volumes>"] ["<levels>"]  (target-loadtest/results/<tag>-<v>-<u>u-*.csv)
TAG=$1; VOLUMES=${2:-"500 5000 20000"}; LEVELS=${3:-"10 25 50 100 200"}
cd "$(dirname "$0")/../.."
R=target-loadtest/results
p95() { awk -F, -v l="$2" '$1==l {print $5; f=1} END {if (!f) print "-"}' "$1"; }
col() { awk -F, -v l="$2" -v c="$3" '$1==l {print $c; f=1} END {if (!f) print "-"}' "$1"; }
met() { awk -F, -v m="$2" '$1==m {printf "%.0f", $2; f=1} END {if (!f) print "-"}' "$1"; }
echo "| Контролов | Польз. | RPS | p95 всё | list.all | list.component | list.kdn | Dashboard | карточка | переход | сохранение | экспорт списка | файл 3-5 МБ | 5xx / 4xx / таймауты | Hikari ожид. max | heap max, МБ | после GC max, МБ |"
echo "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|"
for v in $VOLUMES; do for u in $LEVELS; do
  s=$R/$TAG-$v-${u}u-summary.csv; m=$R/$TAG-$v-${u}u-metrics-summary.csv
  [ -f "$s" ] || continue
  printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s / %s / %s | %s | %s | %s |\n" "$v" "$u" \
    "$(col $s TOTAL 3)" "$(p95 $s TOTAL)" "$(p95 $s list.all)" "$(p95 $s list.component)" "$(p95 $s list.kdn)" \
    "$(p95 $s dash.page)" "$(p95 $s card.page)" "$(p95 $s move.forward)" "$(p95 $s save.details)" \
    "$(p95 $s export.list)" "$(p95 $s file.download.big)" "$(col $s TOTAL 8)" "$(col $s TOTAL 9)" "$(col $s TOTAL 11)" \
    "$(met $m hikari_pending)" "$(met $m heap_used_mb)" "$(met $m live_after_gc_mb)"
done; done
