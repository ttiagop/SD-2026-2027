#!/usr/bin/env bash
# Uso: bash executar.sh compilar | no <k> <i> | produtor | processador | raft-check [falhas.txt] | causal-check
# Os ficheiros de cada ensaio ficam em $RAFT04_ENSAIO (por omissão ensaios/manual).
set -euo pipefail
base=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
acao=${1:-ajuda}
if [ "$#" -gt 0 ]; then shift; fi

if [ "$acao" = compilar ]; then
    for projeto in raft04-no raft04-produtor raft04-processador; do
        rm -rf "$base/bin/$projeto"; mkdir -p "$base/bin/$projeto"
        javac --release 11 -d "$base/bin/$projeto" "$base/$projeto/src/raft04/"*.java
    done
    echo "Três projetos compilados."
    exit
fi

N=${RAFT04_N:-3}
case "$acao" in
    no)           projeto=raft04-no;          classe=PartitionNode;  args=("$@" "$N") ;;
    produtor)     projeto=raft04-produtor;    classe=Producer;       args=("$N" "$@") ;;
    processador)  projeto=raft04-processador; classe=AlarmProcessor; args=("$N" "$@") ;;
    raft-check)   projeto=raft04-no;          classe=RaftCheck;      args=("$N" . "$@") ;;
    causal-check) projeto=raft04-no;          classe=CausalCheck;    args=("$N" . "$@") ;;
    *) echo "Uso: bash executar.sh compilar|no <k> <i>|produtor|processador|raft-check [falhas.txt]|causal-check"; exit 1 ;;
esac

ensaio=${RAFT04_ENSAIO:-$base/ensaios/manual}
mkdir -p "$ensaio"
cd -- "$ensaio"
exec java -Dstdout.encoding=UTF-8 "-Draft04.periodo=${RAFT04_PERIODO:-2000}" "-Draft04.fator=${RAFT04_FATOR:-3}" \
    ${RAFT04_ELEICAO_MIN:+"-Draft04.eleicaoMin=$RAFT04_ELEICAO_MIN"} \
    ${RAFT04_ELEICAO_MAX:+"-Draft04.eleicaoMax=$RAFT04_ELEICAO_MAX"} \
    -cp "$base/bin/$projeto" "raft04.$classe" "${args[@]}"
