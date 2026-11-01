#!/usr/bin/env bash
set -euo pipefail
base=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
acao=${1:-ajuda}
if [ "$#" -gt 0 ]; then shift; fi

if [ "$acao" = compilar ]; then
    for projeto in rep04-produtor rep04-lider rep04-replica rep04-processador; do
        mkdir -p "$base/bin/$projeto"
        javac --release 11 -d "$base/bin/$projeto" "$base/$projeto/src/rep04/"*.java
    done
    echo "Quatro projetos compilados."
    exit
fi

case "$acao" in
    lider) projeto=rep04-lider; classe=PartitionLeader ;;
    replica) projeto=rep04-replica; classe=PartitionReplica ;;
    produtor) projeto=rep04-produtor; classe=Producer ;;
    processador) projeto=rep04-processador; classe=AlarmProcessor ;;
    heartbeat-check) projeto=rep04-replica; classe=HeartbeatCheck ;;
    causal-check) projeto=rep04-replica; classe=CausalCheck ;;
    partition-check) projeto=rep04-replica; classe=PartitionCheck ;;
    *) echo "Uso: bash executar.sh compilar|lider|replica|produtor|processador|heartbeat-check|causal-check|partition-check [argumentos]"; exit 1 ;;
esac

if [ ! -f "$base/bin/$projeto/rep04/$classe.class" ]; then
    echo "Execute primeiro: bash executar.sh compilar" >&2
    exit 1
fi

# Todos os processos do ensaio usam a mesma pasta de ficheiros.
ensaio=${REP04_ENSAIO:-$base/ensaios/manual}
mkdir -p "$ensaio"
cd -- "$ensaio"
exec java "-Drep04.periodo=${REP04_PERIODO:-5000}" "-Drep04.fator=${REP04_FATOR:-3}" \
    -cp "$base/bin/$projeto" "rep04.$classe" "$@"
